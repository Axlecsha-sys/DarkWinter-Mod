// Applies per-world Testing food overrides during native player food ticks.
package com.dark.winter.mixin;

import com.dark.winter.testing.TestingServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FoodData.class)
public abstract class TestingFoodMixin {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void darkwinter$labFood(ServerPlayer player,CallbackInfo ci) {
        if(!TestingServer.enabled(player.level().getServer(),"survival")) ci.cancel();
    }
}
