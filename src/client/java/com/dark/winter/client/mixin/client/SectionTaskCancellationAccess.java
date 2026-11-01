// Exposes the native cancellation flag used to reject stale empty section results.
package com.dark.winter.client.mixin.client;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionRenderDispatcher.RenderSection.SectionTask.class)
public interface SectionTaskCancellationAccess {
    @Accessor("isCancelled")
    AtomicBoolean darkwinter$cancellationFlag();
}
