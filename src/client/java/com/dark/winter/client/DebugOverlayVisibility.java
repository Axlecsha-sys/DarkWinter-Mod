package com.dark.winter.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

/** Shared ownership of screen space; diagnostic flags and gameplay settings are never changed. */
public final class DebugOverlayVisibility {
    private DebugOverlayVisibility() {}
    public static boolean allowsDebugOverlay() {
        var screen=Minecraft.getInstance().gui.screen();
        return screen==null||screen instanceof ChatScreen;
    }
    public static boolean expandedDebugOwnsHud() {
        var client=Minecraft.getInstance();
        return client.debugEntries.isOverlayVisible()&&!client.gui.hud.isHidden()&&allowsDebugOverlay();
    }
}
