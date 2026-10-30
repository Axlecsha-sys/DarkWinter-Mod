// Uses vanilla's login protection for movement, interactions and damage; no saved abilities are changed.
package com.dark.winter.mixin;

import com.dark.winter.loading.WorldPreloadServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class WorldPreloadConnectionMixin {
    @Shadow public ServerPlayer player;

    /** Uses vanilla's login protection for movement, interactions and damage; no saved abilities are changed. */
    @Inject(method = "hasClientLoaded()Z", at = @At("HEAD"), cancellable = true)
    private void darkwinter$waitForPreparedWorld(CallbackInfoReturnable<Boolean> cir) {
        if (WorldPreloadServer.isPreparing(player)) cir.setReturnValue(false);
    }
}
