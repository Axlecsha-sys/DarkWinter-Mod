// A detached editor camera has no first-person hands or held-item pass.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.TestingDrone;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A detached editor camera has no first-person hands or held-item pass. */
@Mixin(GameRenderer.class)
public abstract class TestingDroneHandsMixin {
    @Inject(method="renderItemInHand",at=@At("HEAD"),cancellable=true)
    private void darkwinter$hideDroneHands(CallbackInfo ci){
        if(TestingDrone.active()){TestingDrone.noteHandsSuppressed();ci.cancel();}
    }
}
