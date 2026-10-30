// Only the explicitly active local laboratory drone suppresses the body input/physics step.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.TestingDrone;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the explicitly active local laboratory drone suppresses the body input/physics step. */
@Mixin(LocalPlayer.class)
public abstract class TestingDronePlayerMixin {
    @Inject(method={"aiStep","applyInput"},at=@At("HEAD"),cancellable=true)
    private void darkwinter$droneBody(CallbackInfo ci){
        LocalPlayer self=(LocalPlayer)(Object)this;
        if(TestingDrone.locks(self)){TestingDrone.lockPlayer(self);ci.cancel();}
    }
}
