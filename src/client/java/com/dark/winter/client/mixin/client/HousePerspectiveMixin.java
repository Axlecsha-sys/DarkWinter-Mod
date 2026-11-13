package com.dark.winter.client.mixin.client;

import com.dark.winter.client.interaction.HousePassageClient;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
abstract class HousePerspectiveMixin {
    @Redirect(method = "handleKeybinds", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Options;setCameraType(Lnet/minecraft/client/CameraType;)V"))
    private void darkwinter$lockHousePerspective(Options options, CameraType requested) {
        if (!HousePassageClient.lockPerspective()) options.setCameraType(requested);
    }
}
