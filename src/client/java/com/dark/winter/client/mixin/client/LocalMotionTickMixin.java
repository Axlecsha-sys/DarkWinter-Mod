// Measures supported local-player travel and replaces auto-jump with terrain climbing.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.motion.GaitRuntime;
import com.dark.winter.movement.TerrainStepAssist;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public abstract class LocalMotionTickMixin {
    // Vanilla predicts a jump before touching the ledge, which interrupts our gradual climb.
    // Leave the saved vanilla option alone; manual Space still follows its usual path.
    @Inject(method="canAutoJump()Z",at=@At("HEAD"),cancellable=true,require=1)
    private void darkwinter$useTerrainClimb(CallbackInfoReturnable<Boolean> cir) {
        if (TerrainStepAssist.eligible((LocalPlayer)(Object)this)) cir.setReturnValue(false);
    }

    @Inject(method="tick()V",at=@At("TAIL"),require=1)
    private void darkwinter$measureSupportedTravel(CallbackInfo ci) {
        GaitRuntime.tick((LocalPlayer)(Object)this);
    }
}
