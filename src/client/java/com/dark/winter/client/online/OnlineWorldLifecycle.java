// Coordinates acknowledged, stopped-save transitions between native online worlds.
package com.dark.winter.client.online;

import com.dark.winter.client.TestingSessions;
import com.dark.winter.testing.TestingArchiveScope;
import com.dark.winter.online.OnlineJson;
import com.dark.winter.online.server.OnlineGameHost;
import com.dark.winter.online.world.*;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Coordinates a real stopped-save transition. Acknowledgements never count as a committed world edit. */
final class OnlineWorldLifecycle implements OnlineLabCoordinator.Lifecycle {
    interface Backend {
        OnlineGameHost host();
        MinecraftServer server();
        IslandPolicy policy();
        CompletableFuture<OnlineGameHost> reopen(MinecraftServer target,IslandAccess.View access,IslandPolicy.View policy);
        CompletableFuture<Void> prepareStopped(Path target,boolean internalExtraction,IslandAccess.View access,Map<String,UUID> actualAliases);
        default Optional<TestingArchiveScope.Token> archiveContext(Path actualWorld)throws IOException{return Optional.empty();}
        void status(String text);
    }
    private static final class Flow {
        final OnlineLabCoordinator.Intent intent;final MinecraftServer server;final OnlineGameHost host;
        final IslandAccess.View access;final IslandPolicy.View policy;final boolean frozen;
        final String nonce=UUID.randomUUID().toString();final Set<String> peers;final Map<String,UUID> actualAliases;
        final Set<String> acknowledgements=ConcurrentHashMap.newKeySet();final CompletableFuture<Void> ready=new CompletableFuture<>();
        final long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(10),ackDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        volatile long nextNotice;volatile boolean sending;
        Flow(OnlineLabCoordinator.Intent intent,MinecraftServer server,OnlineGameHost host,IslandAccess.View access,IslandPolicy.View policy,Set<String> peers){
            this.intent=intent;this.server=server;this.host=host;this.access=access;this.policy=policy;this.peers=Set.copyOf(peers);frozen=server.tickRateManager().isFrozen();
            Map<String,UUID> aliases=new HashMap<>();for(ServerPlayer player:server.getPlayerList().getPlayers()){String id=host.actor(player);if(id!=null&&host.can(player,IslandAccess.Permission.VIEW))aliases.put(id,player.getUUID());}actualAliases=Map.copyOf(aliases);
        }
    }
    private static final class Pending {
        final Flow flow;final MinecraftServer target;final CompletableFuture<OnlineLabCoordinator.Rebound> result=new CompletableFuture<>();
        volatile boolean opening,checking,resumeFrozen;volatile OnlineGameHost opened;
        Pending(Flow flow,MinecraftServer target){this.flow=flow;this.target=target;}
    }
    private final Minecraft client;private final Backend backend;
    private volatile Flow flow;private volatile Pending pending;
    OnlineWorldLifecycle(Minecraft client,Backend backend){this.client=Objects.requireNonNull(client);this.backend=Objects.requireNonNull(backend);}
    boolean active(){return flow!=null;}
    @Override public Optional<TestingArchiveScope.Token> archiveContext(Path actualWorld)throws IOException{
        return backend.archiveContext(actualWorld);
    }
    private static boolean independent(String action){return Set.of("branch","import","recover_copy").contains(action);}
    @Override public boolean allowInternalReturnAfterFailure(OnlineLabCoordinator.Intent intent,MinecraftServer source){
        Flow current=flow;
        return current!=null&&current.intent.operationId().equals(intent.operationId())&&System.nanoTime()<current.deadline
            &&current.ready.isDone()&&!current.ready.isCompletedExceptionally()&&source!=null
            &&TestingSessions.internalExtraction(source)&&OnlineGameHost.get(source)==null;
    }
    UUID previousSession(){Flow current=flow;return current==null?null:current.host.sessionId();}
    boolean admissionAllowed(OnlineGameHost host,String peer){
        if(!host.transitioning())return true;
        Flow current=flow;Pending request=pending;
        return current!=null&&request!=null&&request.flow==current&&request.target==backend.server()
            &&OnlineGameHost.get(request.target)==host&&current.peers.contains(peer);
    }
    public CompletableFuture<Void> beforeTransition(OnlineLabCoordinator.Intent intent){
        if(independent(intent.action()))return fail("Независимый остров открывается после закрытия общего сеанса");
        MinecraftServer server=client.getSingleplayerServer();Flow previous=flow;
        if(previous!=null){
            if(!previous.intent.operationId().equals(intent.operationId()))return fail("Другой переход ещё выполняется");
            if(TestingSessions.internalExtraction(server)&&sameSource(server,intent))return CompletableFuture.completedFuture(null);
            return fail("Повторный переход не подтверждён");
        }
        OnlineGameHost host=backend.host();
        if(server==null||host==null||backend.server()!=server||!sameSource(server,intent))return fail("Исходный сетевой мир изменился");
        return onServer(server,()->{
            if(flow!=null||OnlineGameHost.get(server)!=host||!host.sessionId().equals(intent.sessionId())||host.epoch()!=intent.epoch()||!host.islandId().equals(intent.islandId()))throw new IOException("Устаревшая власть перехода");
            ServerPlayer actor=server.getPlayerList().getPlayer(intent.actorGameId());
            if(actor==null||!intent.actorId().equals(host.actor(actor))||!host.can(actor,IslandAccess.Permission.VIEW))throw new IOException("Нет действительного инициатора");
            for(IslandAccess.Permission permission:intent.permissions())if(!host.can(actor,permission))throw new IOException("Право операции отозвано до сохранения: "+permission.name());
            IslandPolicy policy=backend.policy();if(policy==null)throw new IOException("Нет политики сеанса");
            Set<String> peers=new HashSet<>();for(ServerPlayer player:server.getPlayerList().getPlayers()){
                String id=host.actor(player);if(id!=null&&!id.equals(host.localIdentityId())&&host.can(player,IslandAccess.Permission.VIEW))peers.add(id);
            }
            Flow created=new Flow(intent,server,host,host.access().snapshot(),policy.snapshot(),peers);
            flow=created;host.transitionLock(true);server.tickRateManager().setFrozen(true);
            notice(created,"prepare");if(peers.isEmpty())created.ready.complete(null);
            return created;
        }).thenCompose(created->created.ready.copy());
    }
    void acknowledge(MinecraftServer server,OnlineGameHost host,String actor,JsonObject args)throws IOException{
        OnlineJson.keys(args,"id","nonce");Flow current=flow;
        if(current==null||current.server!=server||current.host!=host||!current.intent.operationId().toString().equals(OnlineJson.string(args,"id",36))
                ||!current.nonce.equals(OnlineJson.string(args,"nonce",36))||!current.peers.contains(actor)||System.nanoTime()>current.ackDeadline)throw new IOException("Устаревшее подтверждение перехода");
        current.acknowledgements.add(actor);
        if(current.acknowledgements.containsAll(current.peers))current.ready.complete(null);
    }
    public CompletableFuture<Void> prepareStopped(OnlineLabCoordinator.Intent intent,Path target,String action,boolean internal){
        Flow current=flow;
        if(current==null||!current.intent.operationId().equals(intent.operationId())||!current.ready.isDone()
                ||current.ready.isCompletedExceptionally()||System.nanoTime()>current.deadline
                ||current.server.isRunning()||!current.server.isStopped())return fail("Исходный сетевой сервер ещё не остановлен");
        return backend.prepareStopped(target,internal,current.access,current.actualAliases);
    }
    public CompletableFuture<OnlineLabCoordinator.Rebound> rebind(OnlineLabCoordinator.Intent intent,MinecraftServer target,boolean internal){
        Flow current=flow;
        if(current==null||!current.intent.operationId().equals(intent.operationId())||!current.ready.isDone()||current.ready.isCompletedExceptionally()||System.nanoTime()>current.deadline)return fail("Сетевой переход не был подтверждён");
        if(internal){
            if(!TestingSessions.internalExtraction(target)||OnlineGameHost.get(target)!=null)return fail("Неверная внутренняя копия");
            return CompletableFuture.completedFuture(new OnlineLabCoordinator.Rebound(null,null));
        }
        if(pending!=null)return fail("Восстановление уже выполняется");
        Pending next=new Pending(current,target);pending=next;backend.status("Мир открыт; повторная проверка участников перед продолжением.");
        return next.result.copy();
    }
    void holdOpened(MinecraftServer target,OnlineGameHost opened)throws IOException{
        Pending request=pending;if(request==null||request.target!=target||flow!=request.flow||OnlineGameHost.get(target)!=opened)throw new IOException("Переход отменён");
        request.resumeFrozen=target.tickRateManager().isFrozen();opened.transitionLock(true);target.tickRateManager().setFrozen(true);
    }
    void tick(){
        Flow current=flow;if(current==null)return;long now=System.nanoTime();
        if(now>current.deadline){failed(current.intent,"Истекло время сетевого перехода; сохранения сохранены");return;}
        if(!current.ready.isDone()){
            if(now>current.ackDeadline){failed(current.intent,"Не все участники подтвердили переход; исходный мир остаётся открыт");return;}
            if(now>=current.nextNotice&&!current.sending){
                current.sending=true;current.nextNotice=now+TimeUnit.MILLISECONDS.toNanos(500);
                onServer(current.server,()->{if(flow==current)notice(current,"prepare");return null;}).whenComplete((ignored,error)->{
                    current.sending=false;if(error!=null)client.execute(()->failed(current.intent,"Подготовка участников прервана"));
                });
            }
        }
        Pending request=pending;if(request==null||request.flow!=current)return;
        if(!request.opening&&client.getSingleplayerServer()==request.target&&client.player!=null&&client.level!=null){
            request.opening=true;
            backend.reopen(request.target,current.access,current.policy).whenComplete((opened,error)->client.execute(()->{
                if(pending!=request){if(opened!=null)opened.close();return;}
                if(error!=null){failed(current.intent,"Новый сетевой сеанс не открыт; сохранения сохранены");return;}
                request.opened=opened;
            }));
        }
        if(request.opened!=null&&!request.checking){
            request.checking=true;
            onServer(request.target,()->{
                OnlineGameHost opened=request.opened;if(OnlineGameHost.get(request.target)!=opened)return (UUID)null;
                UUID initiator=null;Set<String> present=new HashSet<>();
                for(ServerPlayer player:request.target.getPlayerList().getPlayers())if(opened.can(player,IslandAccess.Permission.VIEW)){
                    String id=opened.actor(player);present.add(id);if(current.intent.actorId().equals(id))initiator=player.getUUID();
                }
                if(initiator==null||!present.containsAll(current.peers))return (UUID)null;
                opened.transitionLock(false);request.target.tickRateManager().setFrozen(request.resumeFrozen);return initiator;
            }).whenComplete((actor,error)->client.execute(()->{
                request.checking=false;if(pending!=request)return;
                if(error!=null){failed(current.intent,"Допуск инициатора не восстановлен");return;}
                if(actor!=null){
                    pending=null;flow=null;backend.status("Участник повторно подтверждён; продолжение общей операции.");
                    request.result.complete(new OnlineLabCoordinator.Rebound(request.opened,actor));
                }
            }));
        }
    }
    public void failed(OnlineLabCoordinator.Intent intent,String message){
        Flow current=flow;if(current==null||!current.intent.operationId().equals(intent.operationId()))return;
        cancel(message);backend.status(message);
    }
    void cancel(String message){
        Flow current=flow;Pending request=pending;flow=null;pending=null;
        IOException failure=new IOException(message);
        if(current!=null){
            current.ready.completeExceptionally(failure);
            if(OnlineGameHost.get(current.server)==current.host)onServer(current.server,()->{
                current.host.transitionLock(false);notice(current,"cancel");current.server.tickRateManager().setFrozen(current.frozen);return null;
            });
        }
        if(request!=null){
            if(request.opened!=null&&OnlineGameHost.get(request.target)==request.opened)onServer(request.target,()->{request.opened.transitionLock(true);request.target.tickRateManager().setFrozen(true);return null;});
            request.result.completeExceptionally(failure);
        }
    }
    private void notice(Flow current,String type)throws IOException{
        JsonObject event=new JsonObject();event.addProperty("type",type);event.addProperty("id",current.intent.operationId().toString());event.addProperty("nonce",current.nonce);
        event.addProperty("sessionId",current.host.sessionId().toString());event.addProperty("islandId",current.host.islandId());event.addProperty("hostId",current.host.localIdentityId());
        event.addProperty("epoch",current.host.epoch());event.addProperty("action",current.intent.action());current.host.lifecycle(event);
    }
    private static boolean sameSource(MinecraftServer server,OnlineLabCoordinator.Intent intent){return server!=null&&server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(intent.source());}
    private static <T> CompletableFuture<T> fail(String text){return CompletableFuture.failedFuture(new IOException(text));}
    private static <T> CompletableFuture<T> onServer(MinecraftServer server,Callable<T> task){
        CompletableFuture<T> future=new CompletableFuture<>();server.execute(()->{try{future.complete(task.call());}catch(Exception failure){future.completeExceptionally(failure);}});return future;
    }
}
