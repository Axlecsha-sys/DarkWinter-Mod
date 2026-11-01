// Skips unused uniform-palette scratch work inside native packing without bypassing its lock or codec.
package com.dark.winter.mixin;

import com.dark.winter.fixes.performance.SingleValuePalettePacking;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PalettedContainer.class)
abstract class SingleValuePalettePackingMixin<T> {
    @WrapOperation(method = "pack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/PalettedContainer;reencodeContents(Lnet/minecraft/util/BitStorage;Lnet/minecraft/world/level/chunk/Palette;Lnet/minecraft/world/level/chunk/Palette;)[I"))
    private int[] darkwinter$packSingleValue(BitStorage storage, Palette<T> oldPalette,
            Palette<T> newPalette, Operation<int[]> original) {
        int[] contents = SingleValuePalettePacking.tryReencode(storage, oldPalette, newPalette);
        return contents != null ? contents : original.call(storage, oldPalette, newPalette);
    }
}
