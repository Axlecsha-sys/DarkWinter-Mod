// Samples authoritative positions and delivery permissions for admitted voice participants.
package com.dark.winter.online.server;

import com.dark.winter.online.OnlineJson;
import com.dark.winter.online.identity.ProfileFiles;
import java.nio.file.*;
import net.minecraft.world.level.storage.LevelResource;
import com.dark.winter.online.voice.*;
import com.dark.winter.online.world.IslandAccess.Permission;
import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/** Native spatial/global voice sampling only. Positions and permissions are never accepted from clients. */
public final class OnlineVoiceWorld {
    private static final Set<String> CORE_SETTINGS=Set.of("whisperRange","normalRange","shoutRange","globalEnabled","spatialObstacles");
    private static final Set<String> LEGACY_SETTINGS=Set.of("whisperRange","normalRange","shoutRange","radioRange","globalEnabled","spatialObstacles",
        "radioObstacles","radioRequiresItem","radioRequiresPower","radioRequiresChannel","radioRangeLimited");
    private static final class Preferences {
        VoiceRoutePolicy.Mode mode=VoiceRoutePolicy.Mode.SPATIAL;VoiceRoutePolicy.Speech speech=VoiceRoutePolicy.Speech.NORMAL;
        final Set<String> muted=new HashSet<>(),blocked=new HashSet<>();final Map<String,Double> gains=new HashMap<>();
    }
    private final MinecraftServer server;private final OnlineGameHost host;private final Predicate<String> profileBlocked;
    private final Map<String,Preferences> preferences=new HashMap<>();
    private VoiceRoutePolicy.Settings settings=VoiceRoutePolicy.Settings.defaults();
    private long revision;private final Path settingsFile;
    public OnlineVoiceWorld(MinecraftServer server,OnlineGameHost host,Predicate<String> profileBlocked,Supplier<Set<String>> speaking)throws IOException{
        if(server==null||host==null||!server.isSameThread()||OnlineGameHost.get(server)!=host)throw new IllegalArgumentException("Actual native voice authority required");
        this.server=server;this.host=host;this.profileBlocked=Objects.requireNonNull(profileBlocked);Objects.requireNonNull(speaking);
        settingsFile=server.getWorldPath(LevelResource.ROOT).resolve("data/darkwinter-online/voice.json");
        if(Files.exists(settingsFile,LinkOption.NOFOLLOW_LINKS)){
            JsonObject stored=ProfileFiles.read(settingsFile,65536);ProfileFiles.fields(stored,Set.of("schema","islandId","settings"),Set.of());
            long schema=ProfileFiles.number(stored,"schema");
            if((schema!=1&&schema!=2)||!ProfileFiles.string(stored,"islandId",36).equals(host.islandId()))throw new IOException("Неверные настройки голоса острова");
            try{
                if(!stored.has("settings")||!stored.get("settings").isJsonObject())throw new IOException("Нет параметров голосового канала");
                JsonObject raw=stored.getAsJsonObject("settings");
                if(schema==1){
                    // Read old prototype files without restoring any item, radio channel or battery flow.
                    ProfileFiles.fields(raw,LEGACY_SETTINGS,Set.of());
                    var old=OnlineJson.GSON.fromJson(raw,VoiceRoutePolicy.Settings.class);if(old==null)throw new IllegalArgumentException();
                    settings=core(old.whisperRange(),old.normalRange(),old.shoutRange(),old.globalEnabled(),old.spatialObstacles());
                }else{
                    ProfileFiles.fields(raw,CORE_SETTINGS,Set.of());
                    settings=core(number(raw,"whisperRange",4,.5,64),number(raw,"normalRange",24,.5,256),number(raw,"shoutRange",64,.5,1024),
                        OnlineJson.bool(raw,"globalEnabled",true),OnlineJson.bool(raw,"spatialObstacles",true));
                }
            }catch(RuntimeException invalid){throw new IOException("Некорректные параметры голоса; исходный файл сохранён",invalid);}
        }
    }
    private void authority()throws IOException{if(!server.isSameThread()||OnlineGameHost.get(server)!=host||host.access().epoch()!=host.epoch())throw new IOException("Нет действительного ведущего голосового канала");}
    public CompletableFuture<HostVoiceMixer.WorldSnapshot> capture(){
        CompletableFuture<HostVoiceMixer.WorldSnapshot> answer=new CompletableFuture<>();
        server.execute(()->{try{answer.complete(sample());}catch(Exception failure){answer.completeExceptionally(failure);}});return answer;
    }
    private HostVoiceMixer.WorldSnapshot sample()throws IOException{
        authority();Map<String,VoiceRoutePolicy.Member> members=new LinkedHashMap<>();Map<String,ServerPlayer> nativePlayers=new HashMap<>();
        for(ServerPlayer player:server.getPlayerList().getPlayers()){
            String id=host.actor(player);if(id==null||!host.can(player,Permission.VIEW))continue;
            Preferences prefs=preferences.computeIfAbsent(id,key->new Preferences());nativePlayers.put(id,player);
            VoiceRoutePolicy.Position point=new VoiceRoutePolicy.Position(player.level().dimension().identifier().toString(),player.getX(),player.getY(),player.getZ());
            boolean allowed=!profileBlocked.test(id);
            members.put(id,new VoiceRoutePolicy.Member(id,point,allowed,allowed&&host.can(player,Permission.VOICE_SPEAK),allowed&&host.can(player,Permission.VOICE_LISTEN),
                host.can(player,Permission.GLOBAL_VOICE),prefs.mode,prefs.speech,VoiceRoutePolicy.Radio.absent(),Set.copyOf(prefs.blocked),new VoiceRoutePolicy.Preferences(prefs.muted,prefs.gains)));
        }
        Map<VoiceRoutePolicy.Pair,VoiceRoutePolicy.Acoustics> acoustics=new HashMap<>();
        List<String> ids=new ArrayList<>(members.keySet());
        for(int a=0;a<ids.size();a++)for(int b=a+1;b<ids.size();b++){
            ServerPlayer first=nativePlayers.get(ids.get(a)),second=nativePlayers.get(ids.get(b));
            if(first.level()!=second.level())continue;
            VoiceRoutePolicy.Acoustics sampled=acoustics(first,second);
            if(sampled!=null){acoustics.put(new VoiceRoutePolicy.Pair(ids.get(a),ids.get(b)),sampled);acoustics.put(new VoiceRoutePolicy.Pair(ids.get(b),ids.get(a)),sampled);}
        }
        return new HostVoiceMixer.WorldSnapshot(host.sessionId(),host.epoch(),++revision,System.nanoTime(),settings,members,acoustics);
    }
    private VoiceRoutePolicy.Acoustics acoustics(ServerPlayer from,ServerPlayer to){
        Vec3 a=from.getEyePosition(),b=to.getEyePosition();double distance=a.distanceTo(b);var level=from.level();
        // Up to sixteen already-loaded samples. Never generate/load chunks to compute sound.
        int samples=Math.clamp((int)Math.ceil(distance/8),1,16);
        for(int i=0;i<=samples;i++){
            double t=i/(double)samples;BlockPos point=BlockPos.containing(a.lerp(b,t));if(!level.hasChunkAt(point))return null;
        }
        double spatial=1;
        if(distance<=settings.shoutRange()){
            HitResult hit=level.clip(new ClipContext(a,b,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,from));
            if(hit.getType()!=HitResult.Type.MISS)spatial=.15;
        }
        // Spatial approximation remains unchanged; the retired radio-quality field is unused.
        return new VoiceRoutePolicy.Acoustics(spatial,1);
    }
    public JsonObject settings(ServerPlayer player,JsonObject args)throws IOException{
        authority();if(!host.can(player,Permission.VIEW))throw new IOException("Нет допуска к голосовому каналу");
        OnlineJson.keys(args,"channel","speech","mutedId","muted","volumeId","volume","blockedIds",
            "whisperRange","normalRange","shoutRange","globalEnabled","spatialObstacles");
        String actor=host.actor(player);Preferences prefs=preferences.computeIfAbsent(actor,key->new Preferences());
        VoiceRoutePolicy.Mode mode=prefs.mode;VoiceRoutePolicy.Speech speech=prefs.speech;
        if(args.has("channel")){try{mode=VoiceRoutePolicy.Mode.valueOf(OnlineJson.string(args,"channel",16).toUpperCase(Locale.ROOT));}catch(IllegalArgumentException invalid){throw new IOException("Неизвестный голосовой канал");}}
        if(mode==VoiceRoutePolicy.Mode.RADIO)throw new IOException("Радиосвязь не входит в тестовый Online");
        if(mode==VoiceRoutePolicy.Mode.GLOBAL&&!host.can(player,Permission.GLOBAL_VOICE))throw new IOException("Глобальный голосовой канал не разрешён");
        if(args.has("speech")){try{speech=VoiceRoutePolicy.Speech.valueOf(OnlineJson.string(args,"speech",16).toUpperCase(Locale.ROOT));}catch(IllegalArgumentException invalid){throw new IOException("Неизвестный режим речи");}}
        Set<String> muted=new HashSet<>(prefs.muted),blocked=new HashSet<>(prefs.blocked);Map<String,Double> gains=new HashMap<>(prefs.gains);
        if(args.has("mutedId")){String target=id(args,"mutedId");if(OnlineJson.bool(args,"muted",true))muted.add(target);else muted.remove(target);}
        if(args.has("volumeId"))gains.put(id(args,"volumeId"),number(args,"volume",1,0,2));
        if(args.has("blockedIds")){
            JsonElement list=args.get("blockedIds");if(!list.isJsonArray()||list.getAsJsonArray().size()>16)throw new IOException("Слишком большой список голосовых блокировок");blocked.clear();
            for(JsonElement entry:list.getAsJsonArray()){if(!entry.isJsonPrimitive()||!entry.getAsString().matches("dw1_[A-Za-z0-9_-]{43}"))throw new IOException("Неверный ID");blocked.add(entry.getAsString());}
        }
        if(muted.size()>16||gains.size()>16)throw new IOException("Список участников превышает лимит");
        VoiceRoutePolicy.Settings next=settings;
        Set<String> global=CORE_SETTINGS;
        if(args.keySet().stream().anyMatch(global::contains)){
            if(!host.can(player,Permission.SYSTEMS))throw new IOException("Нет права настройки лабораторного звука");
            try{next=core(number(args,"whisperRange",settings.whisperRange(),.5,64),number(args,"normalRange",settings.normalRange(),.5,256),
                number(args,"shoutRange",settings.shoutRange(),.5,1024),
                OnlineJson.bool(args,"globalEnabled",settings.globalEnabled()),OnlineJson.bool(args,"spatialObstacles",settings.spatialObstacles()));}
            catch(IllegalArgumentException invalid){throw new IOException("Дальности звука должны возрастать: шёпот, речь, крик");}
        }
        if(!next.equals(settings))persist(next);
        prefs.mode=mode;prefs.speech=speech;prefs.muted.clear();prefs.muted.addAll(muted);prefs.blocked.clear();prefs.blocked.addAll(blocked);prefs.gains.clear();prefs.gains.putAll(gains);settings=next;
        JsonObject result=OnlineJson.ok();result.add("voice",state(player));return result;
    }
    private void persist(VoiceRoutePolicy.Settings value)throws IOException{
        JsonObject stored=new JsonObject();stored.addProperty("schema",2);stored.addProperty("islandId",host.islandId());stored.add("settings",coreJson(value));ProfileFiles.atomic(settingsFile,stored,true);
    }
    public JsonObject state(ServerPlayer player){
        String id=host.actor(player);Preferences prefs=preferences.computeIfAbsent(id,key->new Preferences());JsonObject out=new JsonObject();
        out.addProperty("canConfigure",host.can(player,Permission.SYSTEMS));out.addProperty("canInteract",host.can(player,Permission.INTERACT));out.addProperty("canSpeak",host.can(player,Permission.VOICE_SPEAK));out.addProperty("canListen",host.can(player,Permission.VOICE_LISTEN));
        out.addProperty("channel",prefs.mode.name().toLowerCase(Locale.ROOT));out.addProperty("speech",prefs.speech.name().toLowerCase(Locale.ROOT));
        out.add("laboratory",coreJson(settings));return out;
    }
    private static VoiceRoutePolicy.Settings core(double whisper,double normal,double shout,boolean global,boolean obstacles){
        var legacy=VoiceRoutePolicy.Settings.defaults();
        return new VoiceRoutePolicy.Settings(whisper,normal,shout,legacy.radioRange(),global,obstacles,
            legacy.radioObstacles(),legacy.radioRequiresItem(),legacy.radioRequiresPower(),legacy.radioRequiresChannel(),legacy.radioRangeLimited());
    }
    private static JsonObject coreJson(VoiceRoutePolicy.Settings value){
        JsonObject result=new JsonObject();result.addProperty("whisperRange",value.whisperRange());result.addProperty("normalRange",value.normalRange());
        result.addProperty("shoutRange",value.shoutRange());result.addProperty("globalEnabled",value.globalEnabled());result.addProperty("spatialObstacles",value.spatialObstacles());return result;
    }
    private static String id(JsonObject args,String key)throws IOException{String id=OnlineJson.string(args,key,64);if(!id.matches("dw1_[A-Za-z0-9_-]{43}"))throw new IOException("Неверный DarkWinter ID");return id;}
    private static double number(JsonObject args,String key,double fallback,double min,double max)throws IOException{
        if(!args.has(key))return fallback;JsonElement raw=args.get(key);if(!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isNumber())throw new IOException("Ожидается число");
        double value=raw.getAsDouble();if(!Double.isFinite(value)||value<min||value>max)throw new IOException("Значение вне диапазона");return value;
    }
}
