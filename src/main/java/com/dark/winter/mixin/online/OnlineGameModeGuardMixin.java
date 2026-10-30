// Checks authenticated island build permission before native block destruction.
package com.dark.winter.mixin.online;
import com.dark.winter.online.server.OnlineGameHost;
import com.dark.winter.online.world.IslandAccess.Permission;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerPlayerGameMode.class)
public abstract class OnlineGameModeGuardMixin {
    @Shadow @Final protected ServerPlayer player;
    @Inject(method="destroyBlock",at=@At("HEAD"),cancellable=true)
    private void darkwinter$breakPermission(BlockPos pos,CallbackInfoReturnable<Boolean> cir) {
        if(!OnlineGameHost.nativeAllowed(player,Permission.BREAK))cir.setReturnValue(false);
    }
}
