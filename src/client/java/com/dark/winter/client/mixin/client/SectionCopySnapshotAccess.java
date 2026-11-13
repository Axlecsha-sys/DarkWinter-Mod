// Exposes immutable section-copy facts for the conservative empty-section compiler shortcut.
package com.dark.winter.client.mixin.client;

import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionCopy.class)
public interface SectionCopySnapshotAccess {
    @Accessor("section") @Nullable PalettedContainer<BlockState> darkwinter$section();
    @Accessor("debug") boolean darkwinter$debug();
}
