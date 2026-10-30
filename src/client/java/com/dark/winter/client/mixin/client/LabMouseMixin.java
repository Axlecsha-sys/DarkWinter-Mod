// Supplies laboratory-controlled mouse coordinates to native GUI input.
package com.dark.winter.client.mixin.client;
import com.dark.winter.client.lab.LabClient;
import com.dark.winter.client.lab.LabUi;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(MouseHandler.class)
public abstract class LabMouseMixin {
    @Inject(method="getScaledXPos(Lcom/mojang/blaze3d/platform/Window;)D",at=@At("HEAD"),cancellable=true,require=1)
    private void darkwinter$labX(Window window, CallbackInfoReturnable<Double> result) {
        if (LabClient.ownsInput() && LabUi.hasPointer(Minecraft.getInstance())) result.setReturnValue(LabUi.pointerX());
    }
    @Inject(method="getScaledYPos(Lcom/mojang/blaze3d/platform/Window;)D",at=@At("HEAD"),cancellable=true,require=1)
    private void darkwinter$labY(Window window, CallbackInfoReturnable<Double> result) {
        if (LabClient.ownsInput() && LabUi.hasPointer(Minecraft.getInstance())) result.setReturnValue(LabUi.pointerY());
    }
}
