// Applies WKE's host-independent graphics policy through Minecraft's real atlas API.
package com.winterkore.engine.graphics;

import net.minecraft.client.Minecraft;

/** Minecraft owns the persisted MIP option and coalesced resource reload lifecycle. */
public final class WkeMinecraftGraphics {
    private WkeMinecraftGraphics() { }

    /** Requested setting, not a claim about a GPU atlas that is still reloading. */
    public static int requestedMipLevels(Minecraft client) {
        return WkeGraphicsPolicy.clampMipLevels(client.options.mipmapLevels().get());
    }

    /** Called at the native video screen's existing texture-settings commit point. */
    public static void applyMipLevels(Minecraft client, int requestedLevels) {
        client.updateMaxMipLevel(WkeGraphicsPolicy.clampMipLevels(requestedLevels));
    }
}
