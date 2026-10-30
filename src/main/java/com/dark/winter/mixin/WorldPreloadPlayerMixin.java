// Connection ticks stay alive; hunger and player physics wait behind the loading screen.
package com.dark.winter.mixin;

import com.dark.winter.loading.WorldPreloadServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class WorldPreloadPlayerMixin {
    /** Connection ticks stay alive; hunger and player physics wait behind the loading screen. */
    @Inject(method = "doTick()V", at = @At("HEAD"), cancellable = true)
    private void darkwinter$waitForPreparedWorld(CallbackInfo ci) {
        if (WorldPreloadServer.isPreparing((ServerPlayer) (Object) this)) ci.cancel();
    }
}
