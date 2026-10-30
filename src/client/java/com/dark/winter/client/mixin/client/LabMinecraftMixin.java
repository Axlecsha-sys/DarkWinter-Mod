// Integrates bounded laboratory input, frame capture, and inactive-window behavior.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.lab.LabClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class LabMinecraftMixin {
    @Inject(method = "pauseIfInactive()V", at = @At("HEAD"), cancellable = true, require = 1)
    private void darkwinter$labKeepRunning(CallbackInfo ci) {
        if (LabClient.ownsInput()) ci.cancel();
    }
    @Inject(method = "runTick(Z)V", at = @At("HEAD"), require = 1)
    private void darkwinter$labFrame(boolean render, CallbackInfo ci) {
        LabClient.beforeFrame((Minecraft)(Object)this);
    }
    @Inject(method = "renderFrame(Z)V", at = @At("TAIL"), require = 1)
    private void darkwinter$labCapture(boolean tick, CallbackInfo ci) {
        LabClient.afterRender((Minecraft)(Object)this);
    }
    @Redirect(method = "handleKeybinds()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"), require = 1)
    private boolean darkwinter$labAttack(MouseHandler mouse) {
        return LabClient.ownsInput() || mouse.isMouseGrabbed();
    }
    @Inject(method = "close()V", at = @At("HEAD"), require = 1)
    private void darkwinter$labShutdown(CallbackInfo ci) { LabClient.shutdown((Minecraft)(Object)this); }
}
