// Redirect vanilla mouse look after its normal sensitivity calculation; never rotate the authoring body.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.TestingDrone;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Redirect vanilla mouse look after its normal sensitivity calculation; never rotate the authoring body. */
@Mixin(Entity.class)
public abstract class TestingDroneLookMixin {
    @Inject(method="turn",at=@At("HEAD"),cancellable=true)
    private void darkwinter$droneLook(double yaw,double pitch,CallbackInfo ci){
        if(TestingDrone.active()&&(Object)this==Minecraft.getInstance().player){TestingDrone.turn(yaw,pitch);ci.cancel();}
    }
}
