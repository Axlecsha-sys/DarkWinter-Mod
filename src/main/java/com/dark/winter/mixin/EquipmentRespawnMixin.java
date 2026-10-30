// Copies retained equipment through the native player respawn transfer.
package com.dark.winter.mixin;

import com.dark.winter.equipment.EquipmentAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class EquipmentRespawnMixin {
    @Inject(method = "transferInventoryXpAndScore(Lnet/minecraft/world/entity/player/Player;)V", at = @At("TAIL"))
    private void darkwinter$copyKeptEquipment(Player previous, CallbackInfo ci) {
        var source = EquipmentAccess.of(previous);
        var target = EquipmentAccess.of((Player) (Object) this);
        for (int i = 0; i < source.getContainerSize(); i++) target.setItem(i, source.getItem(i).copy());
    }
}
