// Drone picking belongs to the editor, never to a vanilla attack/use packet from the parked body.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.TestingDrone;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Drone picking belongs to the editor, never to a vanilla attack/use packet from the parked body. */
@Mixin(Minecraft.class)
public abstract class TestingDroneInteractionMixin {
    @Inject(method="startAttack",at=@At("HEAD"),cancellable=true)
    private void darkwinter$droneAttack(CallbackInfoReturnable<Boolean> cir){if(TestingDrone.active())cir.setReturnValue(false);}
    @Inject(method={"continueAttack","startUseItem"},at=@At("HEAD"),cancellable=true)
    private void darkwinter$droneUse(CallbackInfo ci){if(TestingDrone.active())ci.cancel();}
}
