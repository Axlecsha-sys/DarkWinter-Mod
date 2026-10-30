// Applies Testing-world precipitation overrides to server weather ticks.
package com.dark.winter.mixin;

import com.dark.winter.testing.TestingServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class TestingPrecipitationMixin {
    @Inject(method="tickPrecipitation",at=@At("HEAD"),cancellable=true)
    private void darkwinter$labPrecipitation(BlockPos pos,CallbackInfo ci) {
        var server=((ServerLevel)(Object)this).getServer();
        if(!TestingServer.enabled(server,"precipitation"))ci.cancel();
    }
}
