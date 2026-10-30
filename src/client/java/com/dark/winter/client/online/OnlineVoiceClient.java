// Coordinates admitted voice participants and explicitly selected local audio devices.
package com.dark.winter.client.online;

import com.dark.winter.online.OnlineJson;
import com.dark.winter.online.identity.*;
import com.dark.winter.online.transport.WebRtcTransport;
import com.dark.winter.online.voice.*;
import com.google.gson.*;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Local voice devices/control. The owner supplies only actually admitted native game participants. */
public final class OnlineVoiceClient implements AutoCloseable {
    @FunctionalInterface public interface Outbound { CompletableFuture<JsonObject> send(String recipient,JsonObject sealed); }
    public record SessionView(UUID sessionId,long epoch,boolean localHost,String hostId,String localId,
                              Map<String,IdentityStore.SignedPayload> nativeParticipants) {
        public SessionView { new VoiceSignaling.Context(sessionId,epoch,localHost,hostId,localId,nativeParticipants);nativeParticipants=Map.copyOf(nativeParticipants); }
        VoiceSignaling.Context common(){return new VoiceSignaling.Context(sessionId,epoch,localHost,hostId,localId,nativeParticipants);}
    }
    private static KeyMapping pttKey;
    private static synchronized KeyMapping key(){
        if(pttKey==null){
            var category=KeyMapping.Category.register(Identifier.fromNamespaceAndPath("darkwinter","voice"));
            // N is free as a plain native default; F3+N remains excluded by tick's debug-modifier guard.
            pttKey=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.darkwinter.voice_ptt",InputConstants.Type.KEYBOARD,InputConstants.KEY_N,category));
        }
        return pttKey;
    }
    private final Minecraft client;
    private final Supplier<IdentityStore> identity;
    private final Outbound outbound;
    private final JavaSoundVoiceDevices devices=new JavaSoundVoiceDevices();
    private final Map<String,VoicePeer> connected=new ConcurrentHashMap<>();
    private final Map<String,String> statuses=new ConcurrentHashMap<>();
    private volatile SessionView session;
    private WebRtcTransport.Config network=WebRtcTransport.Config.localOnly();
    private WebRtcVoiceTransport transport;
    private volatile VoiceSignaling signaling;
    private volatile HostVoiceMixer mixer;
    private volatile HostVoiceMixer.LocalPort localPort;
    private volatile VoicePeer upstream;
    private volatile JavaSoundVoiceDevices.Capture capture;
    private volatile JavaSoundVoiceDevices.Playback playback;
    private volatile List<JavaSoundVoiceDevices.Device> inventory=List.of();
    private volatile Supplier<CompletableFuture<HostVoiceMixer.WorldSnapshot>> hostSnapshots;
    private volatile Function<JsonObject,CompletableFuture<JsonObject>> settings;
    private String selectedInput="",selectedOutput="",channel="spatial",status="Голос выключен.";
    private volatile boolean microphoneEnabled,closed;
    private boolean outputEnabled,lastPtt,manualPtt,snapshotPending;
    private volatile long generation,testUntil;
    private long ticks;private boolean testWasEnabled;
    public OnlineVoiceClient(Minecraft client,Supplier<IdentityStore> identity,Outbound outbound){
        this.client=Objects.requireNonNull(client);this.identity=Objects.requireNonNull(identity);this.outbound=Objects.requireNonNull(outbound);key();
    }
    public void onHostSnapshot(Supplier<CompletableFuture<HostVoiceMixer.WorldSnapshot>> supplier){hostSnapshots=supplier;}
    public void onSettings(Function<JsonObject,CompletableFuture<JsonObject>> handler){settings=handler;}
    public HostVoiceMixer hostMixer(){return mixer;}
    public Set<String> actualSpeaking(){HostVoiceMixer active=mixer;return active==null?Set.of():active.view().speaking();}
    public void configureNetwork(WebRtcTransport.Config config){
        Objects.requireNonNull(config);if(session!=null||transport!=null)throw new IllegalStateException("Close native voice session before changing ICE policy");network=config;
    }
    public void refreshIceServers(List<WebRtcTransport.IceServer> servers){network=network.withIceServers(servers);WebRtcVoiceTransport current=transport;if(current!=null)current.refreshIceServers(servers);}
    /** Invoke on the client thread. Null means native admission ended, including a kick or world switch. */
    public void update(SessionView next){
        if(closed)return;
        SessionView previous=session;
        if(Objects.equals(previous,next))return;
        if(next==null||previous!=null&&!sameSession(previous,next))reset();
        session=next;
        if(next==null)return;
        ensureSession();
        signaling.update(next.common()).whenComplete((ignored,error)->{
            if(error!=null)client.execute(()->{if(session!=null&&sameSession(session,next)){status="Личность голосового сеанса не подтверждена.";disableMicrophone();}});
        });
    }
    private static boolean sameSession(SessionView a,SessionView b){return a.sessionId().equals(b.sessionId())&&a.epoch()==b.epoch()&&a.localHost()==b.localHost()&&a.hostId().equals(b.hostId())&&a.localId().equals(b.localId());}
    private void ensureSession(){
        if(signaling!=null)return;
        SessionView bound=session;long fence=generation;
        transport=new WebRtcVoiceTransport(new WebRtcVoiceTransport.Config(network,8,16));
        if(bound.localHost()){
            mixer=new HostVoiceMixer(bound.sessionId(),bound.epoch());
            localPort=new HostVoiceMixer.LocalPort(pcm->{JavaSoundVoiceDevices.Playback line=playback;if(line!=null)line.offerPcm10ms(pcm);});
            try{mixer.register(bound.localId(),localPort);}catch(IOException impossible){throw new IllegalStateException(impossible);}
            mixer.start();
        }
        signaling=new VoiceSignaling(identity,transport,(peer,sealed)->outbound.send(peer,sealed),new VoiceSignaling.Listener(){
            @Override public CompletableFuture<Void> connected(VoiceSignaling.Context authority,String verifiedPeer,VoicePeer peer){
                CompletableFuture<Void> result=new CompletableFuture<>();
                client.execute(()->{
                    SessionView current=session;
                    if(closed||fence!=generation||current==null||!sameSession(current,bound)||!current.nativeParticipants().containsKey(verifiedPeer)){
                        peer.close();result.completeExceptionally(new IOException("Native voice authority ended"));return;
                    }
                    connected.put(verifiedPeer,peer);statuses.put(verifiedPeer,"connected");
                    if(current.localHost()){
                        HostVoiceMixer currentMixer=mixer;
                        if(currentMixer==null){peer.close();result.completeExceptionally(new IOException("Host mixer unavailable"));return;}
                        currentMixer.attach(verifiedPeer,peer).whenComplete((value,error)->{if(error==null)result.complete(null);else result.completeExceptionally(error);});
                    }else{
                        if(!verifiedPeer.equals(current.hostId())){peer.close();result.completeExceptionally(new IOException("STAR voice accepts host only"));return;}
                        upstream=peer;lastPtt=false;
                        peer.setTransmit(false).whenComplete((value,error)->{if(error==null)result.complete(null);else result.completeExceptionally(error);});
                    }
                });return result;
            }
            @Override public void disconnected(String peerId,VoicePeer peer,String reason){
                client.execute(()->{
                    if(fence!=generation)return;connected.remove(peerId,peer);statuses.put(peerId,"disconnected");
                    if(mixer!=null)mixer.unregister(peerId);
                    if(upstream==peer){upstream=null;disableMicrophone();status="Голосовой канал прерван. Микрофон выключен.";}
                });
            }
            @Override public void status(String peerId,String state,String reason){client.execute(()->{if(fence==generation){statuses.put(peerId,state);status=state.equals("connected")?"Защищённый голосовой канал подключён.":"Голосовой канал недоступен; микрофон не включается автоматически.";}});}
        },Clock.systemUTC());
    }
    /** Root routes runtime control{type:dw_voice_v1,envelope:sealed} here. No plaintext SDP is accepted. */
    public CompletableFuture<Void> acceptControl(String from,JsonObject wire){
        VoiceSignaling broker=signaling;
        if(closed||session==null||broker==null)return CompletableFuture.failedFuture(new IOException("Нет подтверждённого игрового сеанса для голоса"));
        return broker.receive(from,wire);
    }
    /** Local UI only. Never expose this method as a remotely executable microphone/device command. */
    public CompletableFuture<JsonObject> localCommand(JsonObject supplied){
        CompletableFuture<JsonObject> result=new CompletableFuture<>();
        JsonObject args=supplied==null?new JsonObject():supplied.deepCopy();
        client.execute(()->{
            try{
                if(closed)throw new IOException("Голосовой модуль закрыт");
                Set<String> localKeys=Set.of("microphoneEnabled","outputEnabled","pushToTalk","inputDevice","outputDevice","refreshDevices","testMicrophone");
                boolean local=args.keySet().stream().anyMatch(localKeys::contains);
                if(local&&args.keySet().stream().anyMatch(key->!localKeys.contains(key)))throw new IOException("Изменяйте устройства отдельно от сетевого канала");
                if(!local){
                    if(session==null||settings==null)throw new IOException("Настройки голосового канала недоступны");
                    Function<JsonObject,CompletableFuture<JsonObject>> handler=settings;
                    long fence=generation;
                    handler.apply(args).whenComplete((reply,error)->client.execute(()->{
                        if(closed||fence!=generation){result.completeExceptionally(new IOException("Сеанс сменился"));return;}
                        if(error!=null){result.completeExceptionally(error);return;}
                        if(reply!=null&&reply.has("ok")&&reply.get("ok").getAsBoolean()&&args.has("channel"))channel=args.get("channel").getAsString();
                        result.complete(reply==null?OnlineJson.error("Нет ответа ведущего"):reply);
                    }));return;
                }
                OnlineJson.keys(args,localKeys.toArray(String[]::new));
                if(args.has("pushToTalk")&&!OnlineJson.bool(args,"pushToTalk",true))throw new IOException("Передача доступна только при удержании Push-to-Talk");
                if(args.has("inputDevice"))selectedInput=deviceChoice(args,"inputDevice");
                if(args.has("outputDevice"))selectedOutput=deviceChoice(args,"outputDevice");
                if(args.has("refreshDevices")&&OnlineJson.bool(args,"refreshDevices",false)){
                    devices.devices().whenComplete((list,error)->client.execute(()->{
                        if(error!=null){status="Устройства звука недоступны.";result.completeExceptionally(error);}
                        else{inventory=list;status="Выберите микрофон и устройство вывода.";result.complete(OnlineJson.ok());}
                    }));return;
                }
                if(args.has("testMicrophone")&&OnlineJson.bool(args,"testMicrophone",false)){
                    if(testUntil!=0)throw new IOException("Проверка микрофона уже идёт");
                    testWasEnabled=microphoneEnabled;
                    enableMicrophone().whenComplete((value,error)->client.execute(()->{
                        if(error!=null){result.completeExceptionally(error);return;}
                        testUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);status="Проверка уровня микрофона; звук не передаётся участникам.";result.complete(OnlineJson.ok());
                    }));return;
                }
                if(args.has("microphoneEnabled")){
                    if(OnlineJson.bool(args,"microphoneEnabled",false)){
                        enableMicrophone().whenComplete((value,error)->client.execute(()->{if(error==null)result.complete(OnlineJson.ok());else result.completeExceptionally(error);}));return;
                    }
                    disableMicrophone();result.complete(OnlineJson.ok());return;
                }
                if(args.has("outputEnabled")&&!OnlineJson.bool(args,"outputEnabled",false)){
                    outputEnabled=false;JavaSoundVoiceDevices.Playback line=playback;playback=null;if(line!=null)line.close();result.complete(OnlineJson.ok());return;
                }
                if(args.has("outputDevice")||args.has("outputEnabled")){
                    enableOutput().whenComplete((value,error)->client.execute(()->{if(error==null)result.complete(OnlineJson.ok());else result.completeExceptionally(error);}));return;
                }
                if(args.has("inputDevice")&&microphoneEnabled){
                    disableMicrophone().whenComplete((ignored,stopError)->client.execute(()->{
                        if(stopError!=null){result.completeExceptionally(stopError);return;}
                        enableMicrophone().whenComplete((value,error)->client.execute(()->{if(error==null)result.complete(OnlineJson.ok());else result.completeExceptionally(error);}));
                    }));return;
                }
                result.complete(OnlineJson.ok());
            }catch(Exception error){result.completeExceptionally(error);}
        });return result;
    }
    private static String deviceChoice(JsonObject args,String key)throws IOException {
        String value=OnlineJson.string(args,key,64);if(!value.matches("[0-9a-f]{64}"))throw new IOException("Выберите устройство из полученного списка");return value;
    }
    private CompletableFuture<Void> enableMicrophone(){
        if(capture!=null&&microphoneEnabled)return CompletableFuture.completedFuture(null);
        if(selectedInput.isEmpty())return CompletableFuture.failedFuture(new IOException("Сначала получите список и выберите микрофон"));
        long fence=generation;String selected=selectedInput;
        return devices.openCapture(selected,pcm->{
            if(closed||testUntil!=0||!microphoneEnabled)return;
            SessionView current=session;if(current==null)return;
            if(current.localHost()){HostVoiceMixer.LocalPort target=localPort;if(target!=null)target.offerInput(pcm);}
            else{VoicePeer target=upstream;if(target!=null)target.pushPcm10ms(pcm);}
        }).thenCompose(line->{
            CompletableFuture<Void> accepted=new CompletableFuture<>();
            client.execute(()->{
                if(closed||fence!=generation||!selected.equals(selectedInput)){line.close();accepted.completeExceptionally(new IOException("Включение микрофона отменено"));return;}
                capture=line;microphoneEnabled=true;lastPtt=false;status="Микрофон разрешён. Для передачи удерживайте клавишу Push-to-Talk.";accepted.complete(null);
            });return accepted;
        });
    }
    private CompletableFuture<Void> enableOutput(){
        JavaSoundVoiceDevices.Playback old=playback;playback=null;outputEnabled=false;
        if(selectedOutput.isEmpty())return CompletableFuture.failedFuture(new IOException("Выберите устройство вывода"));
        CompletableFuture<Void> barrier=old==null?CompletableFuture.completedFuture(null):old.terminated();
        if(old!=null)old.close();
        long fence=generation;String selected=selectedOutput;
        return barrier.thenCompose(ignored->devices.openPlayback(selected)).thenCompose(line->{
            CompletableFuture<Void> accepted=new CompletableFuture<>();client.execute(()->{
                if(closed||fence!=generation||!selected.equals(selectedOutput)){line.close();accepted.completeExceptionally(new IOException("Вывод отменён"));return;}
                playback=line;outputEnabled=true;accepted.complete(null);
            });return accepted;
        });
    }
    private CompletableFuture<Void> disableMicrophone(){
        microphoneEnabled=false;manualPtt=false;lastPtt=false;testUntil=0;
        JavaSoundVoiceDevices.Capture line=capture;capture=null;if(line!=null){line.setPushToTalk(false);line.close();}
        VoicePeer peer=upstream;if(peer!=null)peer.setTransmit(false);
        return line==null?CompletableFuture.completedFuture(null):line.terminated();
    }
    /** Optional explicit local PTT input; tick also reads the remappable N default. */
    public void setPushToTalk(boolean held){manualPtt=held;}
    public void tick(){
        if(closed)return;ticks++;
        if(testUntil!=0&&System.nanoTime()>=testUntil){testUntil=0;if(!testWasEnabled)disableMicrophone();lastPtt=false;}
        boolean testing=testUntil!=0;
        boolean focused=client.isWindowActive();
        boolean held=microphoneEnabled&&focused&&(testing||session!=null&&client.player!=null&&client.gui.screen()==null
                &&!client.options.keyDebugModifier.isDown()&&(manualPtt||key().isDown()));
        if(held!=lastPtt){
            lastPtt=held;JavaSoundVoiceDevices.Capture input=capture;if(input!=null)input.setPushToTalk(held);
            VoicePeer target=upstream;if(target!=null)target.setTransmit(held&&!testing);
        }
        JavaSoundVoiceDevices.Capture mic=capture;
        if(mic!=null&&mic.state().status().equals("FAILED")){status="Микрофон отключён или недоступен.";disableMicrophone();}
        VoicePeer remote=upstream;
        if(remote!=null)for(int frame=0;frame<64;frame++){
            Optional<VoicePeer.DecodedAudio> audio=remote.pollDecoded();if(audio.isEmpty())break;
            JavaSoundVoiceDevices.Playback output=playback;if(output!=null)output.offer(audio.orElseThrow());
        }
        SessionView current=session;
        Supplier<CompletableFuture<HostVoiceMixer.WorldSnapshot>> supplier=hostSnapshots;
        if(current!=null&&current.localHost()&&mixer!=null&&supplier!=null&&!snapshotPending&&ticks%2==0){
            snapshotPending=true;long fence=generation;
            try{supplier.get().whenComplete((value,error)->client.execute(()->{
                snapshotPending=false;if(closed||fence!=generation||mixer==null)return;
                if(error!=null||value==null){status="Ожидаются актуальные права и состояние мира для голоса.";return;}
                try{mixer.update(value);}catch(RuntimeException invalid){status="Состояние голосового мира отклонено.";}
            }));}catch(RuntimeException unavailable){snapshotPending=false;status="Голосовой адаптер мира недоступен.";}
        }
    }
    public JsonObject snapshot(){
        JsonObject out=new JsonObject();out.addProperty("available",session!=null);out.addProperty("microphoneEnabled",microphoneEnabled);
        out.addProperty("outputEnabled",outputEnabled);out.addProperty("pushToTalk",true);out.addProperty("transmitting",lastPtt&&testUntil==0);
        out.addProperty("status",status);out.addProperty("selectedInput",selectedInput);out.addProperty("selectedOutput",selectedOutput);
        out.addProperty("channel",channel);out.addProperty("connectedPeers",connected.size());out.addProperty("pttKey",key().getTranslatedKeyMessage().getString());
        JsonArray inputs=new JsonArray(),outputs=new JsonArray();
        for(var device:inventory){JsonObject row=new JsonObject();row.addProperty("id",device.id());row.addProperty("name",device.name());if(device.input())inputs.add(row.deepCopy());if(device.output())outputs.add(row);}
        out.add("inputs",inputs);out.add("outputs",outputs);
        JavaSoundVoiceDevices.Capture mic=capture;out.addProperty("level",mic==null?0:mic.state().level());
        return out;
    }
    private void reset(){
        generation++;disableMicrophone();outputEnabled=false;
        JavaSoundVoiceDevices.Playback output=playback;playback=null;if(output!=null)output.close();
        if(signaling!=null)signaling.close();if(mixer!=null)mixer.close();if(transport!=null)transport.close();
        signaling=null;mixer=null;localPort=null;transport=null;upstream=null;connected.clear();statuses.clear();snapshotPending=false;
        status="Голос выключен; микрофон не включается автоматически.";
    }
    @Override public void close(){if(closed)return;reset();session=null;closed=true;devices.close();}
}
