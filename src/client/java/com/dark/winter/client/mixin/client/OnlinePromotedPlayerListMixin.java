// Restricts promotion-specific player-list loading to a reserved world copy.
package com.dark.winter.client.mixin.client;

import com.dark.winter.online.activation.PendingAttestedStartup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only a reserved promoted copy freezes here; ordinary servers are untouched. */
@Mixin(MinecraftServer.class)
public abstract class OnlinePromotedPlayerListMixin implements PendingAttestedStartup.PlayerListHook {
    @Inject(method="setPlayerList(Lnet/minecraft/server/players/PlayerList;)V",at=@At("TAIL"),require=1)
    private void darkwinter$freezeReservedStartup(PlayerList players,CallbackInfo callback){
        PendingAttestedStartup.playerListReady((MinecraftServer)(Object)this);
    }
}
