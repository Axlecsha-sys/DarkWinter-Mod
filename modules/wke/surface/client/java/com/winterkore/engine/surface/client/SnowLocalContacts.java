// Local presentation cuts the real snow surface before the next frame; the server remains authoritative.
package com.winterkore.engine.surface.client;

import com.dark.winter.client.equipment.ThirdPersonAnimations;
import com.winterkore.engine.surface.geometry.HumanGaitTimeline;
import com.winterkore.engine.surface.host.PlayerSnowFootprints;
import com.winterkore.engine.surface.host.SnowSurfaceRuntime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/** Pressure samples the extracted avatar endpoint, without advancing a second animation clock. */
public final class SnowLocalContacts {
    public record FrameAlignment(int serial,boolean extractedBody,double phase,double x,double y,double z,float bodyYaw){}
    private static ClientLevel world;
    private static Player owner;
    private static PlayerSnowFootprints.Walker walker;
    private static final IdentityHashMap<AvatarRenderState,HumanGaitTimeline.Snapshot> captured=new IdentityHashMap<>();
    private static int serial;
    /** No client entity/world crosses threads; the captured entity ID identifies this player incarnation. */
    private record TraceSession(MinecraftServer server,UUID player,int entityId,ResourceKey<Level> dimension){}
    private static volatile TraceSession traceSession;
    // The server keeps its UUID sequence across respawn and client-world replacement.
    private static long traceSequence,clientTicks;
    private static long retryGeneration=Long.MIN_VALUE,retryToken,retrySentTick=Long.MIN_VALUE;
    private record BootstrapAck(TraceSession session,long revision){}
    private static volatile BootstrapAck bootstrapAck;
    private static long ackRevision=-1,ackSentTick=Long.MIN_VALUE;
    private static void resetRetry(){retryGeneration=Long.MIN_VALUE;retryToken=0;retrySentTick=Long.MIN_VALUE;}
    private static void resetAck(){bootstrapAck=null;ackRevision=-1;ackSentTick=Long.MIN_VALUE;}
    private static FrameAlignment alignment=new FrameAlignment(0,false,0,0,0,0,0);
    private SnowLocalContacts(){}
    static void reset(){
        traceSession=null;resetRetry();resetAck();clientTicks=0;
        world=null;owner=null;walker=null;captured.clear();serial=0;
        alignment=new FrameAlignment(0,false,0,0,0,0,0);
    }
    private static void bind(Minecraft client){
        if(world!=client.level||owner!=client.player){
            reset();world=client.level;owner=client.player;
            walker=owner==null?null:new PlayerSnowFootprints.Walker(owner);
        }
        var server=client.getSingleplayerServer();
        if(world==null||owner==null||server==null||!client.isLocalServer()){traceSession=null;return;}
        var current=traceSession;
        if(current==null||current.server()!=server||!current.player().equals(owner.getUUID())||current.entityId()!=owner.getId()
                ||!current.dimension().equals(world.dimension())){
            traceSession=new TraceSession(server,owner.getUUID(),owner.getId(),world.dimension());resetRetry();resetAck();
        }
    }
    /** Enqueue only immutable captured context; queued pressure still drains during ordinary quit/save. */
    private static void enqueueOwned(TraceSession session,Consumer<ServerPlayer> action){
        if(session==null||traceSession!=session)return;
        try{session.server().execute(()->{
            // Do not test current client binding here: an ordinary quit must not erase queued pressure.
            var player=session.server().getPlayerList().getPlayer(session.player());
            if(player==null||player.getId()!=session.entityId()||player.level().getServer()!=session.server()
                    ||!player.level().dimension().equals(session.dimension()))return;
            action.accept(player);
        });}catch(RejectedExecutionException stopped){
            // Closing an integrated server can reject the last queued render callback.
        }
    }
    /** Persist actual extracted pressure in the owned integrated server, after immediate presentation. */
    private static void persistRendered(List<SnowSurfaceRuntime.RenderedFootTrace> traces){
        var session=traceSession;if(session==null||traces.isEmpty())return;
        for(int first=0;first<traces.size();first+=8){
            var batch=List.copyOf(traces.subList(first,Math.min(first+8,traces.size())));
            long sequence=++traceSequence;
            enqueueOwned(session,player->SnowSurfaceRuntime.acceptRenderedFootTraces(player,batch,sequence));
        }
    }
    /** Release the SSP stream only after the actual retained initial nine fields have committed. */
    private static void acknowledgeBootstrap(TraceSession session){
        if(!SnowSurfaceClient.bootstrapReady())return;
        long revision=SnowSurfaceClient.bootstrapCommittedSourceRevision();if(revision<0)return;
        var acknowledged=bootstrapAck;
        if(acknowledged!=null&&acknowledged.session()==session&&acknowledged.revision()==revision)return;
        if(ackRevision!=revision){ackRevision=revision;ackSentTick=Long.MIN_VALUE;}
        if(ackSentTick!=Long.MIN_VALUE&&clientTicks-ackSentTick<20)return;
        ackSentTick=clientTicks;
        enqueueOwned(session,player->{
            if(SnowSurfaceRuntime.ackRenderedBootstrap(player,revision))bootstrapAck=new BootstrapAck(session,revision);
        });
    }
    /** Tick maintenance retries rejected initial cohorts, without stamping a future rendered endpoint. */
    static void tick(Minecraft client){
        bind(client);clientTicks++;
        var session=traceSession;if(session==null)return;
        acknowledgeBootstrap(session);
        if(!SnowSurfaceClient.bootstrapNeedsFreshCohort())return;
        long generation=SnowSurfaceClient.bootstrapRetryGeneration();
        if(retryGeneration!=generation){
            retryGeneration=generation;retryToken=++traceSequence;retrySentTick=Long.MIN_VALUE;
        }
        if(retrySentTick!=Long.MIN_VALUE&&clientTicks-retrySentTick<20)return;
        retrySentTick=clientTicks;long token=retryToken;
        // Resends keep the same token. They cannot repeatedly reset an already streaming paged cohort.
        enqueueOwned(session,player->SnowSurfaceRuntime.requestRenderedBootstrapRetry(player,token));
    }
    /** Native avatar extraction supplies both the actual partial-tick phase and its render-state identity. */
    public static void capture(Player player,AvatarRenderState state,HumanGaitTimeline.Snapshot pose){
        Minecraft client=Minecraft.getInstance();bind(client);
        if(player!=owner||pose==null)return;
        if(captured.size()>=16)captured.clear();
        captured.put(state,pose);
    }
    /** Called before the snow frame is frozen; GUI portraits cannot supply a world contact. */
    static boolean applyCaptured(LevelRenderState levelState){
        Minecraft client=Minecraft.getInstance();bind(client);
        // The first pressure sample must see an actual displayed owner, rather than consume
        // the first touch while the native cap is still awaiting its replacement ACK.
        if(world==null||owner==null||walker==null||!SnowSurfaceClient.bootstrapNativeReady()){captured.clear();return false;}
        AvatarRenderState avatar=null;HumanGaitTimeline.Snapshot pose=null;
        for(var entity:levelState.entityRenderStates)if(entity instanceof AvatarRenderState candidate){
            var found=captured.get(candidate);
            if(found!=null){avatar=candidate;pose=found;break;}
        }
        double x,y,z;float yaw;
        boolean extracted=avatar!=null;
        if(extracted){
            x=avatar.x;y=avatar.y;z=avatar.z;yaw=avatar.bodyRot;
        }else{
            // A culled/invisible avatar still presses the snow. Use this frame's native interpolation.
            float partial=client.getDeltaTracker().getGameTimeDeltaPartialTick(!world.tickRateManager().isEntityFrozen(owner));
            pose=ThirdPersonAnimations.gait(owner,partial);
            var position=owner.getPosition(partial);x=position.x;y=position.y;z=position.z;
            yaw=Mth.rotLerp(partial,owner.yBodyRotO,owner.yBodyRot);
        }
        captured.clear();
        if(++serial<=0){serial=1;walker=new PlayerSnowFootprints.Walker(owner);}
        alignment=new FrameAlignment(serial,extracted,pose.phase(),x,y,z,yaw);
        var plants=walker.update(owner,pose,serial,x,y,z,yaw);
        if(plants.isEmpty())return false;
        var contacts=new ArrayList<SnowSurfaceClient.LocalContactCandidate>(plants.size());
        var traces=traceSession==null?null:new ArrayList<SnowSurfaceRuntime.RenderedFootTrace>(plants.size());
        for(var plant:plants){
            var contact=SnowSurfaceClient.localContact(plant);
            if(contact==null)continue;
            contacts.add(contact);
            // The presentation mark stores snow skin Y. Persistence needs the original real sole Y.
            if(traces!=null)traces.add(new SnowSurfaceRuntime.RenderedFootTrace(plant.x(),plant.y(),plant.z(),
                    plant.yaw(),plant.width(),plant.length(),plant.channel(),contact.support()));
        }
        if(contacts.isEmpty())return false;
        SnowSurfaceClient.applyLocalContacts(contacts);
        if(traces!=null)persistRendered(traces);
        return true;
    }
    public static FrameAlignment frameAlignment(){return alignment;}
}
