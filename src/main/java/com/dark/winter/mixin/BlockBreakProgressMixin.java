// Uses elapsed mining ticks when the server completes a delayed block break.
package com.dark.winter.mixin;

import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ServerPlayerGameMode.class)
public abstract class BlockBreakProgressMixin {
    @Shadow private int gameTicks;

    // MC-311994: the first call in tick handles delayed destruction; its argument is a start tick in 26.3.
    @ModifyArg(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;incrementDestroyProgress(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;I)F",
            ordinal = 0
        ),
        index = 2,
        require = 1
    )
    private int darkwinter$delayedDestroyElapsedTicks(int startTick) {
        return this.gameTicks - startTick;
    }
}
