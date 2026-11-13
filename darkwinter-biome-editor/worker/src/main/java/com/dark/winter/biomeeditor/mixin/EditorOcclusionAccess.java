// Observes pending native visibility publication without waiting for or changing its background task.
package com.dark.winter.biomeeditor.mixin;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionOcclusionGraph.class)
public interface EditorOcclusionAccess {
    @Accessor("needsFullUpdate") boolean editor$needsFullUpdate();
    @Accessor("fullUpdateTask") Future<?> editor$fullUpdateTask();
    @Accessor("needsFrustumUpdate") AtomicBoolean editor$needsFrustumUpdate();
}
