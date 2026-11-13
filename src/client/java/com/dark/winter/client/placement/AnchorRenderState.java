package com.dark.winter.client.placement;

import net.minecraft.world.phys.Vec3;

/** Per-extraction transform; submission never consults a live world. */
public interface AnchorRenderState {
    Vec3 darkwinter$anchorOffset();
    void darkwinter$anchorOffset(Vec3 offset);
    double darkwinter$anchorRotation();
    void darkwinter$anchorRotation(double rotation);
}
