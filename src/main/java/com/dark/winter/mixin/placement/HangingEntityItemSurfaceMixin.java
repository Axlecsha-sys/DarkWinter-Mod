// Preserve the ordinary native item path, including painting variant selection, sounds, consumption and drops.
package com.dark.winter.mixin.placement;

import com.dark.winter.placement.hanging.HangingPlacement;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(HangingEntityItem.class)
public abstract class HangingEntityItemSurfaceMixin {
    @WrapMethod(method = "useOn")
    private InteractionResult darkwinter$surfaceScope(UseOnContext context, Operation<InteractionResult> original) {
        try (var scope = HangingPlacement.begin(context)) { return original.call(context); }
    }
}
