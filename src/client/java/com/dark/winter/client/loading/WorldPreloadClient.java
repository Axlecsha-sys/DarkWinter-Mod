// Tracks server-reported preload progress for the current connection.
package com.dark.winter.client.loading;

import com.dark.winter.client.UiPreferences;
import com.dark.winter.loading.WorldPreloadPayload;
import com.dark.winter.loading.WorldPreloadServer;
import com.dark.winter.worldgen.TestBiomeCompatibility;
import com.winterkore.engine.surface.client.SnowSurfaceClient;
import com.winterkore.engine.surface.client.SnowSurfaceChunkInbox;
import com.winterkore.engine.surface.client.SnowInitialNativePreparation;
import java.util.Map;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.util.Util;

/** Progress belongs to one connection. UI never reads a live server chunk collection. */
public final class WorldPreloadClient {
    private static WorldPreloadPayload current;
    private static float shown;
    private static long lastFrame;
    private static boolean failureAcknowledged;
    private static boolean loadingUiFinished;
    private static boolean showingPreloadProgress;
    private static ClientPacketListener connectionOwner;
    private static PacketSender senderOwner;
    private static final Logger SNOW_LOG=LoggerFactory.getLogger("DarkWinter/SnowLoading");
    private static final long SNOW_CAPABILITY_WAIT_MILLIS=5_000,SNOW_READY_TIMEOUT_MILLIS=90_000;
    private static long snowCapabilityStarted,snowReadyStarted,snowLastDiagnostic;
    private static boolean snowReadyLatched,snowCapabilityBypassed;
    private static String snowFailure="";

    private WorldPreloadClient() { }

    public static void init() {
        WorldPreloadServer.setAutomaticPreference(UiPreferences::additionalChunkPreload);
        ClientPlayConnectionEvents.INIT.register((handler, client) -> {
            reset();
            connectionOwner = handler;
            senderOwner = null;
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (connectionOwner == handler) senderOwner = sender;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            // Fabric may dispatch channelInactive from Netty. A late old connection must not reset a new one.
            if (connectionOwner == handler) {
                reset();
                connectionOwner = null;
                senderOwner = null;
            }
        }));
        ClientPlayNetworking.registerGlobalReceiver(WorldPreloadPayload.TYPE, (payload, context) -> {
            Minecraft client = context.client();
            // Context.player() is backed by Minecraft.player in this Fabric version. The sender,
            // unlike that player reference, belongs to the actual packet's connection addon.
            if (!ownsLiveConnection(client) || client.getConnection() != connectionOwner
                    || context.responseSender() != senderOwner) return;
            if (current == null || payload.completed() < current.completed()) {
                shown = 0;
                lastFrame = 0;
                failureAcknowledged = false;
            }
            current = payload;
            if (payload.active()) loadingUiFinished = false;
            if (payload.stage() == WorldPreloadPayload.Stage.FAILED) {
                context.client().gui.setScreen(new WorldPreloadScreen(context.client().gui.screen()));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            SnowInitialNativePreparation.tick(client);
            if (active() && client.level != null && (client.gui.screen() == null
                    ||client.gui.screen() instanceof LevelLoadingScreen&&allowWorldPreparation())) {
                // The ordinary opaque screen permits native world preparation without exposing the world or accepting movement.
                client.gui.setScreen(new WorldPreloadScreen(null));
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof LevelLoadingScreen)) return;
            // doWorldLoad creates this screen before spinning its server and before the next PLAY INIT.
            if (client.level == null) reset();
            var button = WorldPreloadScreen.menuButton(width / 2 - 68, NorthernLoadingArt.buttonY(height), 136);
            // Before login Minecraft is in a blocking startup loop: cancelling there is unsafe.
            button.visible = canCancel(client);
            Screens.getWidgets(screen).add(button);
            ScreenEvents.beforeTick(screen).register(ignored -> button.visible = canCancel(client));
        });
    }

    public static WorldPreloadPayload progress() {
        Minecraft client = Minecraft.getInstance();
        // disconnect() clears the integrated server before its queued DISCONNECT event can run.
        // Never expose a retained LOADING packet as live progress during or after that teardown.
        if (!ownsLiveConnection(client)) return null;
        // Cover the small JOIN-to-custom-packet gap without sharing mutable server state.
        var ordinary=current!=null?current:WorldPreloadServer.currentProgress(client.getSingleplayerServer());
        if(ordinary!=null&&ordinary.active())return ordinary;
        var snow=snowProgress(client);
        return snow!=null?snow:ordinary;
    }

    /** Only the initial local Test-world entry waits; ordinary travel and dimension transfers never re-arm it. */
    private static WorldPreloadPayload snowProgress(Minecraft client){
        if(loadingUiFinished||snowReadyLatched||client.level==null||client.player==null
                ||!Level.OVERWORLD.equals(client.level.dimension())||!client.level.hasChunkAt(client.player.blockPosition())
                ||!client.level.getBiome(client.player.blockPosition()).is(TestBiomeCompatibility.BIOME))return null;
        if(SnowSurfaceClient.bootstrapNativeReady()){snowReadyLatched=true;return null;}
        long now=Util.getMillis();
        boolean announced=!SnowSurfaceChunkInbox.activeChunks(client.level.dimension().identifier().toString()).isEmpty();
        if(!announced){
            if(snowCapabilityBypassed)return null;
            if(snowCapabilityStarted==0)snowCapabilityStarted=now;
            if(now-snowCapabilityStarted>=SNOW_CAPABILITY_WAIT_MILLIS){
                snowCapabilityBypassed=true;
                SNOW_LOG.warn("No snow stream announcement after bounded capability wait; native loading continues for a connection without the protocol. dimension={} position={}",
                        client.level.dimension().identifier(),client.player.blockPosition());
                return null;
            }
        }else if(snowReadyStarted==0)snowReadyStarted=now;
        long elapsed=now-(snowReadyStarted==0?snowCapabilityStarted:snowReadyStarted);
        if(announced&&elapsed>=SNOW_READY_TIMEOUT_MILLIS&&snowFailure.isEmpty()){
            snowFailure="Initial nearby snow field did not become ready within "+SNOW_READY_TIMEOUT_MILLIS+" ms";
            SNOW_LOG.error("{}; diagnostics={}",snowFailure,snowBootstrapDiagnostics());
        }
        var diagnostic=SnowSurfaceClient.bootstrapDiagnostics();
        int prepared=((Number)diagnostic.getOrDefault("nearCommittedMeshes",0)).intValue();
        if(announced&&elapsed>=5_000&&now-snowLastDiagnostic>=5_000){
            snowLastDiagnostic=now;SNOW_LOG.warn("Waiting for initial nearby snow publication: {}",diagnostic);
        }
        return new WorldPreloadPayload(Math.clamp(prepared,0,9),9,SnowSurfaceChunkInbox.pendingChunks(),
                client.player.blockPosition().getX()>>4,client.player.blockPosition().getZ()>>4,
                client.level.dimension().identifier().toString(),snowFailure.isEmpty()?WorldPreloadPayload.Stage.LOADING:WorldPreloadPayload.Stage.FAILED,elapsed);
    }
    /** Stage two keeps the opaque existing UI/input gate, but permits ordinary native section preparation. */
    public static boolean allowWorldPreparation(){
        Minecraft c=Minecraft.getInstance();
        if(!ownsLiveConnection(c)||loadingUiFinished||!snowFailure.isEmpty()||c.level==null
                ||!Level.OVERWORLD.equals(c.level.dimension())||SnowSurfaceClient.bootstrapNativeReady()
                ||SnowSurfaceChunkInbox.activeChunks(c.level.dimension().identifier().toString()).isEmpty())return false;
        var ordinary=current!=null?current:WorldPreloadServer.currentProgress(c.getSingleplayerServer());
        return ordinary==null||!ordinary.active();
    }
    public static Map<String,Object> snowBootstrapDiagnostics(){
        Minecraft c=Minecraft.getInstance();
        return Map.of("ready",snowReadyLatched||SnowSurfaceClient.bootstrapNativeReady(),"cpuReady",SnowSurfaceClient.bootstrapReady(),"pending",SnowSurfaceClient.bootstrapPending(),
                "inbox",SnowSurfaceChunkInbox.pendingChunks(),"failure",snowFailure,"capabilityBypassed",snowCapabilityBypassed,
                "dimension",c.level==null?"":c.level.dimension().identifier().toString(),
                "activeChunks",c.level==null?0:SnowSurfaceChunkInbox.activeChunks(c.level.dimension().identifier().toString()).size(),
                "nativePreparation",SnowInitialNativePreparation.metrics(),"bootstrap",SnowSurfaceClient.bootstrapDiagnostics());
    }

    public static boolean active() {
        var progress = progress();
        return progress != null && (progress.active()||!snowFailure.isEmpty());
    }

    public static boolean unacknowledgedFailure() {
        var progress = progress();
        return progress != null && progress.stage() == WorldPreloadPayload.Stage.FAILED && !failureAcknowledged && snowFailure.isEmpty();
    }

    public static boolean northernLoading(LevelLoadingScreen.Reason reason) {
        return reason == LevelLoadingScreen.Reason.OTHER && Minecraft.getInstance().isLocalServer() && !loadingUiFinished;
    }

    public static void loadingScreenClosed() { loadingUiFinished = true; }

    public static float displayedProgress(float vanillaProgress) {
        long now = Util.getMillis();
        var progress = progress();
        boolean preload = progress != null;
        if (showingPreloadProgress != preload) {
            // The shared server snapshot can arrive before the first custom packet.
            // Its 0/2601 must never inherit the previous vanilla startup bar.
            showingPreloadProgress = preload;
            shown = 0;
            lastFrame = 0;
        }
        float target = progress == null ? vanillaProgress
                : (progress.total() <= 0 ? 0 : progress.completed() / (float) progress.total());
        if (target < shown) shown = Math.max(0, target);
        float step = lastFrame == 0 ? 1 : (float) (1 - Math.exp(-Math.min(100, now - lastFrame) / 110.0));
        shown += (Math.clamp(target, 0, 1) - shown) * step;
        lastFrame = now;
        return shown;
    }

    static void acknowledgeFailure() { failureAcknowledged = true; }

    static void cancel() {
        Minecraft client = Minecraft.getInstance();
        if (!canCancel(client)) return;
        reset();
        client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
    }

    private static boolean canCancel(Minecraft client) {
        return client.isLocalServer() && client.level != null && (active() || unacknowledgedFailure());
    }

    private static boolean ownsLiveConnection(Minecraft client) {
        return client.isLocalServer() && client.getSingleplayerServer() != null
                && connectionOwner != null && connectionOwner.isAcceptingMessages()
                && (client.getConnection() == null || client.getConnection() == connectionOwner);
    }

    private static void reset() {
        current = null;
        shown = 0;
        lastFrame = 0;
        failureAcknowledged = false;
        loadingUiFinished = false;
        showingPreloadProgress = false;
        snowCapabilityStarted=snowReadyStarted=snowLastDiagnostic=0;snowReadyLatched=snowCapabilityBypassed=false;snowFailure="";
        SnowInitialNativePreparation.reset();
    }
}
