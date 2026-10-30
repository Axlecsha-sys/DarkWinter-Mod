// Exposes the native client connection to authenticated online-session integration.
package com.dark.winter.client.mixin.client;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ClientCommonPacketListenerImpl.class)
public interface OnlineClientCommonAccess {
    @Accessor("connection") Connection darkwinter$connection();
}
