// Exposes the fixed region center without querying or loading mutable world data.
package com.dark.winter.client.mixin.client;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderSectionRegion.class)
public interface SectionRegionSnapshotAccess {
    @Accessor("sections") SectionCopy[] darkwinter$sections();
    @Accessor("minSectionX") int darkwinter$minSectionX();
    @Accessor("minSectionY") int darkwinter$minSectionY();
    @Accessor("minSectionZ") int darkwinter$minSectionZ();
}
