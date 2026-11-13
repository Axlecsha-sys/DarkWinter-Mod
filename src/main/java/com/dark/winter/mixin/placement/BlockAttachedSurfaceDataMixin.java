// Piggyback on native hanging-entity NBT; ordinary painting/frame item, rotation and variant data stay native.
package com.dark.winter.mixin.placement;

import com.dark.winter.placement.hanging.HangingAnchorAccess;
import com.dark.winter.placement.hanging.HangingPlacement;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockAttachedEntity.class)
public abstract class BlockAttachedSurfaceDataMixin {
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void darkwinter$saveSurface(ValueOutput output, CallbackInfo ci) {
        if ((Object)this instanceof HangingAnchorAccess anchor) HangingPlacement.save(anchor, output);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void darkwinter$loadSurface(ValueInput input, CallbackInfo ci) {
        if ((Object)this instanceof HangingAnchorAccess anchor) HangingPlacement.load(anchor, input);
    }
}
