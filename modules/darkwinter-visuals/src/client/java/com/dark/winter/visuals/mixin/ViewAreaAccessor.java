// Exposes compiled render sections from the native view area.
package com.dark.winter.visuals.mixin;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ViewArea.class)
public interface ViewAreaAccessor {
    @Accessor("sections")
    RotatingSectionStorage<SectionRenderDispatcher.RenderSection> darkwinter$sections();
}
