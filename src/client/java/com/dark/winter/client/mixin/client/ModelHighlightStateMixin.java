// Keeps each extracted outline frame independent of later world and resource changes.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.highlight.ModelHighlight;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockOutlineRenderState.class)
public abstract class ModelHighlightStateMixin implements ModelHighlight.OutlineState {
    @Unique private ModelHighlight.@Nullable Frame darkwinter$modelOutline;
    @Override public ModelHighlight.@Nullable Frame darkwinter$modelOutline(){return darkwinter$modelOutline;}
    @Override public void darkwinter$modelOutline(ModelHighlight.@Nullable Frame frame){darkwinter$modelOutline=frame;}
}
