// Item frames have separate native pop-box and survival implementations; fixed frames retain their native bypass.
package com.dark.winter.mixin.placement;

import com.dark.winter.placement.hanging.HangingAnchorAccess;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemFrame.class)
public abstract class ItemFrameSurfaceMixin {
    @Shadow private boolean fixed;

    @Inject(method = "getPopBox", at = @At("RETURN"), cancellable = true)
    private void darkwinter$physicalPopBox(CallbackInfoReturnable<AABB> cir) {
        HangingAnchorAccess anchor = (HangingAnchorAccess)(Object)this;
        ItemFrame frame = (ItemFrame)(Object)this;
        if (anchor.darkwinter$surfaceSupport().isPresent() && anchor.darkwinter$surfaceNormal() == frame.getDirection())
            cir.setReturnValue(cir.getReturnValue().move(anchor.darkwinter$surfaceOffset()));
    }

    @Inject(method = "survives", at = @At("HEAD"), cancellable = true)
    private void darkwinter$checkActualFrameSupport(CallbackInfoReturnable<Boolean> cir) {
        HangingAnchorAccess anchor = (HangingAnchorAccess)(Object)this;
        if (!fixed && anchor.darkwinter$surfaceSupport().isPresent())
            cir.setReturnValue(anchor.darkwinter$surfaceSurvives(true));
    }
}
