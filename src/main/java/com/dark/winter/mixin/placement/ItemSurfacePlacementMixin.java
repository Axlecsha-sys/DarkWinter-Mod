// Restrict the item-side hook to the validated DarkWinter item-on-furniture path.
package com.dark.winter.mixin.placement;

import com.dark.winter.placement.item.PlacedItems;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Item.class)
public abstract class ItemSurfacePlacementMixin {
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void darkwinter$placeOnFurniture(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = PlacedItems.place(context);
        if (result != InteractionResult.PASS) cir.setReturnValue(result);
    }
}
