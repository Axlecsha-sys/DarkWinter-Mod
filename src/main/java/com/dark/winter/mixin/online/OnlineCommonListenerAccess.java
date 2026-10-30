// Exposes the common server packet listener connection and server references.
package com.dark.winter.mixin.online;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ServerCommonPacketListenerImpl.class)
public interface OnlineCommonListenerAccess {
    @Accessor("server") MinecraftServer darkwinter$server();
    @Accessor("connection") Connection darkwinter$connection();
}
