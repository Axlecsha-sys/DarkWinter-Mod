// The detached camera has no eye offset; entering/leaving it must not blend a parked body's eye height.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.TestingDrone;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The detached camera has no eye offset; entering/leaving it must not blend a parked body's eye height. */
@Mixin(Camera.class)
public abstract class TestingDroneCameraMixin {
    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;
    @Unique private boolean darkwinter$wasDroneCamera;
    @Inject(method="setEntity",at=@At("TAIL"))
    private void darkwinter$droneEye(Entity entity,CallbackInfo ci){
        if(TestingDrone.active()&&entity==TestingDrone.entity()){
            eyeHeight=0;eyeHeightOld=0;darkwinter$wasDroneCamera=true;
        }else if(darkwinter$wasDroneCamera){
            eyeHeight=entity==null?0:entity.getEyeHeight();eyeHeightOld=eyeHeight;darkwinter$wasDroneCamera=false;
        }
    }
}
