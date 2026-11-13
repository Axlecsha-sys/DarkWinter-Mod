// Native hanging entities keep their logical tile position while their physical box follows this anchor.
package com.dark.winter.placement.hanging;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public interface HangingAnchorAccess {
    Optional<BlockPos> darkwinter$surfaceSupport();
    Direction darkwinter$surfaceNormal();
    Vec3 darkwinter$surfaceOffset();
    /** Writes synchronized anchor state; the enclosing native bounding-box/load callback completes the physical update. */
    void darkwinter$setSurfaceAnchor(@Nullable BlockPos support, Direction normal, Vec3 offset);
    boolean darkwinter$surfaceSurvives(boolean allowIntersectingSameType);
}
