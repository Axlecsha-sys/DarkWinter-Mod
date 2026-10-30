// Tracks server-reported preload progress for the current connection.
package com.dark.winter.client.loading;

import com.dark.winter.loading.WorldPreloadPayload;
import com.dark.winter.loading.WorldPreloadServer;
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

    private WorldPreloadClient() { }

    public static void init() {
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
            if (active() && client.level != null && client.gui.screen() == null) {
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
        if (current != null) return current;
        // Cover the small JOIN-to-custom-packet gap without sharing mutable server state.
        return WorldPreloadServer.currentProgress(client.getSingleplayerServer());
    }

    public static boolean active() {
        var progress = progress();
        return progress != null && progress.active();
    }

    public static boolean unacknowledgedFailure() {
        var progress = progress();
        return progress != null && progress.stage() == WorldPreloadPayload.Stage.FAILED && !failureAcknowledged;
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
    }
}
