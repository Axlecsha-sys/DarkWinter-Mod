// Exposes native login verification to authenticated online ingress.
package com.dark.winter.mixin.online;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(ServerLoginPacketListenerImpl.class)
public interface OnlineLoginAccess {
    @Invoker("startClientVerification") void darkwinter$verify(GameProfile profile);
}
