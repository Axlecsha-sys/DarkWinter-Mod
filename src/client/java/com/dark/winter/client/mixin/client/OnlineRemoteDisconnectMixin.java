// Captures the actual remote-disconnect reason before the common client lifecycle clears authentication.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.online.OnlineConnectionClient;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the actual remote-disconnect reason before the common client lifecycle clears authentication. */
@Mixin(value=ClientCommonPacketListenerImpl.class,priority=2000)
public abstract class OnlineRemoteDisconnectMixin {
    @Shadow @Final protected Connection connection;
    @Inject(method="onDisconnect",at=@At("HEAD"))
    private void darkwinter$onlineRemoteDisconnect(DisconnectionDetails details,CallbackInfo ci){
        OnlineConnectionClient.remoteDisconnected(connection,details);
    }
}
