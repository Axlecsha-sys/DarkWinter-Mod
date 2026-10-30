// Small render-state key shared by body animation and the equipment layer.
package com.dark.winter.client.equipment;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;

/** Small render-state key shared by body animation and the equipment layer. */
public final class AppearanceRenderState {
    public static final RenderStateDataKey<Integer> OUTFIT =
            RenderStateDataKey.create(() -> "darkwinter:outfit");

    private AppearanceRenderState() { }
}
