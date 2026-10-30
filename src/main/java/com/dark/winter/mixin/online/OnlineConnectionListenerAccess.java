// Exposes native server listener channels for controlled online ingress.
package com.dark.winter.mixin.online;
import io.netty.channel.ChannelFuture;
import java.util.List;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ServerConnectionListener.class)
public interface OnlineConnectionListenerAccess {
    @Accessor("channels") List<ChannelFuture> darkwinter$channels();
}
