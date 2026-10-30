// Applies managed Testing-session publication and permission rules to the integrated server.
package com.dark.winter.client.mixin.client;
import com.dark.winter.online.server.OnlineGameHost;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(IntegratedServer.class)
public abstract class OnlineIntegratedServerMixin {
    @Inject(method="isPublished",at=@At("HEAD"),cancellable=true)
    private void darkwinter$managedPublished(CallbackInfoReturnable<Boolean> cir) {
        if(OnlineGameHost.publicationGuard((MinecraftServer)(Object)this))cir.setReturnValue(true);
    }
    @Inject(method="getMultiplayerScope",at=@At("HEAD"),cancellable=true)
    private void darkwinter$managedScope(CallbackInfoReturnable<MinecraftServer.MultiplayerScope> cir) {
        if(OnlineGameHost.publicationGuard((MinecraftServer)(Object)this))cir.setReturnValue(MinecraftServer.MultiplayerScope.LAN);
    }
    @Inject(method="getProfilePermissions",at=@At("HEAD"),cancellable=true)
    private void darkwinter$noLocalUuidOperator(NameAndId profile,CallbackInfoReturnable<LevelBasedPermissionSet> cir) {
        OnlineGameHost host=OnlineGameHost.get((MinecraftServer)(Object)this);
        if(host!=null&&!host.nativeOperator(profile.id())||host==null&&OnlineGameHost.publicationGuard((MinecraftServer)(Object)this))cir.setReturnValue(LevelBasedPermissionSet.ALL);
    }
    @Inject(method="unpublishServer",at=@At("HEAD"),cancellable=true)
    private void darkwinter$closeOnlyManagedListener(CallbackInfoReturnable<Boolean> cir) {
        OnlineGameHost host=OnlineGameHost.get((MinecraftServer)(Object)this);
        if(host!=null){host.close();cir.setReturnValue(true);}
    }
    @Inject(method="getMaxPlayers",at=@At("HEAD"),cancellable=true)
    private void darkwinter$managedLimit(CallbackInfoReturnable<Integer> cir) {
        OnlineGameHost host=OnlineGameHost.get((MinecraftServer)(Object)this);if(host!=null)cir.setReturnValue(host.limit());
    }
}
