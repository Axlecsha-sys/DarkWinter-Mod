// Prevents native UUID-based operator privileges from bypassing managed island authority.
package com.dark.winter.mixin.online;
import com.dark.winter.online.server.OnlineGameHost;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(MinecraftServer.class)
public abstract class OnlineVanillaAuthorityMixin {
    @Inject(method="getProfilePermissions",at=@At("HEAD"),cancellable=true)
    private void darkwinter$noUuidPrivilege(NameAndId profile,CallbackInfoReturnable<LevelBasedPermissionSet> cir) {
        OnlineGameHost host=OnlineGameHost.get((MinecraftServer)(Object)this);
        if(host!=null&&!host.nativeOperator(profile.id()))cir.setReturnValue(LevelBasedPermissionSet.ALL); // Actual 26.3 level 0.
    }
}
