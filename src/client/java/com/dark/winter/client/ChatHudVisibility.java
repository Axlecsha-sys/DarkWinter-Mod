package com.dark.winter.client;

/** Read-only visibility used to give native chat priority over optional diagnostics. */
public interface ChatHudVisibility {
    boolean darkwinter$hasVisibleMessages(int guiTick);
}
