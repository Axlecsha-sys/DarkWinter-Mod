// Guest independently observes its already pinned directory.
package com.dark.winter.client.online;

import com.dark.winter.online.authority.*;
import com.dark.winter.online.identity.*;
import com.dark.winter.online.signaling.PeerSignaling;
import com.google.gson.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Guest independently observes its already pinned directory. Host-supplied receipts never grant admission. */
final class OnlineGuestAuthority implements AutoCloseable {
    private final OnlineFriendsRuntime runtime;
    private final IdentityStore identity;
    private final long generation;
    private final String node,holder;
    private final UUID session;
    private final String island;
    private final Object observedLock=new Object();
    private final WorldAuthority.Certificate certificate;
    private final WorldAuthority.Policy policy;
    private final Consumer<String> failure;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(task->{
        Thread t=new Thread(task,"DarkWinter-guest-authority");t.setDaemon(true);return t;});
    private final AtomicBoolean closed=new AtomicBoolean(),notified=new AtomicBoolean();
    private final CompletableFuture<OnlineGuestAuthority> ready=new CompletableFuture<>();
    private volatile LeaseFence.Fence observed;
    private volatile CompletableFuture<?> pending;
    private volatile AuthorityServiceClient service;
    private AuthorityPins pins;

    OnlineGuestAuthority(OnlineFriendsRuntime runtime,PeerSignaling.Descriptor descriptor,String authenticatedHost,
                         Consumer<String> failure)throws IOException {
        this.runtime=Objects.requireNonNull(runtime);identity=runtime.identity();generation=runtime.generation();
        holder=authenticatedHost;session=descriptor.sessionId();this.failure=Objects.requireNonNull(failure);
        JsonObject proof=descriptor.authority();
        ProfileFiles.fields(proof,Set.of("certificate","policy"),Set.of());
        certificate=WorldAuthority.certificateFromJson(proof.getAsJsonObject("certificate"));
        policy=WorldAuthority.verifyPolicy(certificate,WorldAuthority.signedFromJson(proof.getAsJsonObject("policy")));
        var root=WorldAuthority.verify(certificate);island=root.islandId().toString();node=runtime.verifiedAuthorityNode().id();
        if(!root.coordinatorId().equals(node)||!root.islandId().toString().equals(descriptor.islandId()))
            throw new IOException("Остров не подтверждён выбранным служебным узлом");
        WorldAuthority.authorizeHost(policy,holder,System.currentTimeMillis());
        ready.whenComplete((v,f)->{if(ready.isCancelled())close();});
        worker.execute(this::initialize);
    }
    CompletableFuture<OnlineGuestAuthority> ready(){return ready;}
    boolean usable(){
        LeaseFence.Fence current=observed;
        try{return !closed.get()&&current!=null&&current.usable()&&runtime.identity()==identity
            &&runtime.generation()==generation&&node.equals(runtime.verifiedAuthorityNode().id())
            &&(service==null||service.observedEpoch()<=current.epoch()&&service.observedPolicyRevision()<=current.policyRevision());}
        catch(Exception unavailable){return false;}
    }
    void requireUsable()throws IOException {if(!usable())throw new IOException("Узел ещё не подтвердил действующего ведущего");}
    String islandId(){return island;}
    String ownerId(){return certificate.genesis().signer().id();}
    WorldAuthority.Certificate certificate(){return certificate;}
    WorldAuthority.Policy policy(){return policy;}
    LeaseFence.Fence fence()throws IOException{requireUsable();LeaseFence.Fence current=observed;current.requireUsable();return current;}
    private void initialize(){
        try{
            guard();pins=AuthorityPins.open(identity.base());
            var pin=pins.get(WorldAuthority.verify(certificate).islandId());
            if(pin.isPresent()){
                WorldAuthority.requirePinned(certificate,pin.get().authorityId(),pin.get().ownerId());
                if(!node.equals(pin.get().nodeId()))throw new IOException("Закреплён другой узел острова");
            }
            service=new AuthorityServiceClient(certificate,()->identity,runtime::authorityRequest,Clock.systemUTC(),System::nanoTime);
            if(pin.isPresent())service.requireMinimum(pin.get().minEpoch(),pin.get().minPolicy());
            observe();
            guard();ready.complete(this);
            worker.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS);
        }catch(Exception denied){ready.completeExceptionally(denied);close();disposeResources();}
    }
    private void observe()throws IOException {
        guard();CompletableFuture<LeaseFence.Fence> request=service.observe(holder,session,service.observedEpoch(),policy.revision());pending=request;
        try{
            LeaseFence.Fence fresh=request.get(18,TimeUnit.SECONDS);guard();
            if(fresh.policyRevision()!=policy.revision()||!fresh.policySha256().equals(policy.sha256()))
                throw new IOException("Политика ведущего изменилась");
            WorldAuthority.authorizeHost(policy,holder,System.currentTimeMillis());
            // Initial pin follows this actual independently verified Node receipt, never just a copied world certificate.
            pins.pinVerified(certificate,node,fresh.epoch(),fresh.policyRevision());pins.advanceVerified(certificate,fresh);
            synchronized(observedLock){guard();observed=fresh;}
        }catch(InterruptedException canceled){Thread.currentThread().interrupt();request.cancel(true);throw new IOException("Проверка ведущего отменена",canceled);}
        catch(ExecutionException|TimeoutException|CancellationException denied){request.cancel(true);throw new IOException("Узел не подтвердил ведущего",denied);}
        finally{if(pending==request)pending=null;}
    }
    private void tick(){
        if(closed.get())return;
        try{guard();LeaseFence.Fence current=observed;if(current==null||current.remainingMillis()<=15000)observe();}
        catch(Exception unavailable){
            LeaseFence.Fence current=observed;
            if(!usable()||service!=null&&current!=null&&(service.observedEpoch()>current.epoch()||service.observedPolicyRevision()>current.policyRevision())){
                close();if(notified.compareAndSet(false,true))failure.accept("Полномочия ведущего больше не подтверждены. Подключение закрыто.");
            }
        }
    }
    private void guard()throws IOException {
        if(closed.get()||runtime.identity()!=identity||runtime.generation()!=generation||!node.equals(runtime.verifiedAuthorityNode().id()))
            throw new IOException("Служебный узел или профиль изменился");
    }
    private void disposeResources(){AuthorityServiceClient value=service;if(value!=null)value.close();AuthorityPins store=pins;if(store!=null)store.close();}
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        synchronized(observedLock){observed=null;}CompletableFuture<?> current=pending;if(current!=null)current.cancel(true);
        worker.shutdownNow();AuthorityServiceClient requests=service;if(requests!=null)requests.close();
        if(!ready.isDone())ready.completeExceptionally(new IOException("Проверка ведущего отменена"));
        Thread cleanup=new Thread(this::disposeResources,"DarkWinter-guest-authority-close");cleanup.setDaemon(true);cleanup.start();
    }
}
