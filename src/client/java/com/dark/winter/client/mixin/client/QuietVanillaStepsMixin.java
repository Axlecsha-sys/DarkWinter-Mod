// Suppresses overlapping native step audio without changing movement or game events.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.motion.GaitRuntime;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Audio only. Does not cancel movement, GameEvents, landing damage or server sounds. */
@Mixin(Entity.class)
public abstract class QuietVanillaStepsMixin {
    @Inject(method={
        "playStepSound(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
        "playCombinationStepSounds(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V",
        "playMuffledStepSound(Lnet/minecraft/world/level/block/state/BlockState;)V"
    },at=@At("HEAD"),cancellable=true,require=3)
    private void darkwinter$useSingleFootstepTrack(CallbackInfo ci) {
        if(GaitRuntime.replaceStep((Entity)(Object)this)) ci.cancel();
    }
}
