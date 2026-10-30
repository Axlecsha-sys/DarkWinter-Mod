// Requires authenticated online identity before native configuration completes.
package com.dark.winter.mixin.online;
import com.dark.winter.online.server.OnlineGameHost;
import com.dark.winter.testing.TestingServer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=ServerConfigurationPacketListenerImpl.class,priority=2000)
public abstract class OnlineConfigurationMixin {
    @Unique private volatile boolean darkwinter$returnScheduled;
    @Unique private boolean darkwinter$authQueued;
    // Fabric completes its early registration/configuration tasks before vanilla reaches this method.
    // Rescheduling only this tail never restarts Fabric startConfiguration with an already populated task queue.
    @Inject(method="returnToWorld",at=@At("HEAD"),cancellable=true)
    private void darkwinter$identityFirst(CallbackInfo ci) {
        var access=(OnlineCommonListenerAccess)this;
        MinecraftServer server=access.darkwinter$server();Connection connection=access.darkwinter$connection();
        if(connection.isMemoryConnection()||!TestingServer.isTesting(server))return;
        if(!server.isSameThread()) {
            ci.cancel();if(!darkwinter$returnScheduled){darkwinter$returnScheduled=true;server.execute(()->{darkwinter$returnScheduled=false;((ServerConfigurationPacketListenerImpl)(Object)this).returnToWorld();});}return;
        }
        if(darkwinter$authQueued){ci.cancel();return;}
        try {
            OnlineGameHost host=OnlineGameHost.get(server);
            if(host==null)throw new IllegalStateException();
            host.beginConfiguration((ServerConfigurationPacketListenerImpl)(Object)this);darkwinter$authQueued=true;
        }catch(Exception failure){((ServerConfigurationPacketListenerImpl)(Object)this).disconnect(Component.literal("DarkWinter: обязательное подтверждение личности недоступно"));ci.cancel();}
    }
    @Inject(method="handleConfigurationFinished",at=@At("HEAD"),cancellable=true)
    private void darkwinter$neverSpawnBeforeProof(ServerboundFinishConfigurationPacket packet,CallbackInfo ci) {
        var access=(OnlineCommonListenerAccess)this;
        if(access.darkwinter$connection().isMemoryConnection()||!TestingServer.isTesting(access.darkwinter$server()))return;
        if(!access.darkwinter$server().isSameThread())return; // Vanilla PacketUtils reschedules before this handler mutates anything.
        OnlineGameHost host=OnlineGameHost.get(access.darkwinter$server());
        if(host==null||!host.configurationReady(access.darkwinter$connection())) {
            ((ServerConfigurationPacketListenerImpl)(Object)this).disconnect(Component.literal("DarkWinter: удостоверение до входа в мир обязательно"));ci.cancel();
        }
    }
}
