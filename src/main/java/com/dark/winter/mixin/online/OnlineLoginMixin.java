// Validates managed online ingress before native player verification.
package com.dark.winter.mixin.online;
import com.dark.winter.online.server.OnlineGameHost;
import com.dark.winter.testing.TestingServer;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class OnlineLoginMixin {
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private Connection connection;
    @Shadow private String requestedUsername;
    @Shadow public abstract void disconnect(Component reason);
    @Unique private boolean darkwinter$helloQueued;
    @Inject(method="handleHello",at=@At("HEAD"),cancellable=true)
    private void darkwinter$verifiedIngress(ServerboundHelloPacket packet,CallbackInfo ci) {
        if(connection.isMemoryConnection()||!TestingServer.isTesting(server))return;
        ci.cancel();
        if(darkwinter$helloQueued){disconnect(Component.literal("DarkWinter: повтор нативного входа"));return;}
        darkwinter$helloQueued=true;
        server.execute(()->{
            try {
                OnlineGameHost host=OnlineGameHost.get(server);
                if(host==null)throw new IllegalStateException("Сеанс Online закрыт");
                GameProfile verified=host.claimLogin(connection);
                requestedUsername=verified.name();
                ((OnlineLoginAccess)this).darkwinter$verify(verified);
            } catch(Exception failure) {disconnect(Component.literal("DarkWinter: нет подтверждённого сетевого допуска"));}
        });
    }
}
