package com.dark.winter.placement;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
/** Immutable geometry-generation snapshot supplied by a client render region. */
public interface AnchorLevelView {
    Map<Long,Vec3> darkwinter$anchorOffsets();
    Map<Long,Double> darkwinter$anchorRotations();
}
