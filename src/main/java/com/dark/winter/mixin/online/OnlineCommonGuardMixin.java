// Prevents native custom-click actions from bypassing managed-session permissions.
package com.dark.winter.mixin.online;
import com.dark.winter.online.server.OnlineGameHost;
import com.dark.winter.testing.TestingServer;
import net.minecraft.server.network.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class OnlineCommonGuardMixin {
    @Inject(method="handleCustomClickAction",at=@At("HEAD"),cancellable=true)
    private void darkwinter$noDialogCommandBypass(CallbackInfo ci) {
        var common=(OnlineCommonListenerAccess)this;
        OnlineGameHost host=OnlineGameHost.get(common.darkwinter$server());
        if(host!=null) {
            if(!((Object)this instanceof ServerGamePacketListenerImpl play)||!host.isWorldOwner(play.player))ci.cancel();
        }else if(!common.darkwinter$connection().isMemoryConnection()&&TestingServer.isTesting(common.darkwinter$server()))ci.cancel();
    }
}
