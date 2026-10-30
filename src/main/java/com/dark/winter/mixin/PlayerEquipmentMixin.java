// Persists and drops the player equipment container through native player lifecycle hooks.
package com.dark.winter.mixin;

import com.dark.winter.equipment.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Prediction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerEquipmentMixin implements EquipmentAccess {
    // Lazy: InventoryMenu is built inside Player's constructor, before injected field initializers.
    @Unique private SimpleContainer darkwinter$gear;

    @Override public SimpleContainer darkwinter$equipment() {
        if (darkwinter$gear == null) darkwinter$gear = new SimpleContainer(GearSlot.values().length);
        return darkwinter$gear;
    }

    @Inject(method = "addAdditionalSaveData(Lnet/minecraft/world/level/storage/ValueOutput;)V", at = @At("TAIL"))
    private void darkwinter$saveGear(ValueOutput output, CallbackInfo ci) {
        // Slot names are stable even if the enum order changes in a later version.
        var gear = output.child("darkwinter_equipment");
        for (GearSlot slot : GearSlot.values())
            gear.store(slot.id(), ItemStack.OPTIONAL_CODEC, darkwinter$equipment().getItem(slot.ordinal()));
    }

    @Inject(method = "readAdditionalSaveData(Lnet/minecraft/world/level/storage/ValueInput;)V", at = @At("TAIL"))
    private void darkwinter$loadGear(ValueInput input, CallbackInfo ci) {
        var gear = input.childOrEmpty("darkwinter_equipment");
        darkwinter$equipment().clearContent();
        for (GearSlot slot : GearSlot.values())
            darkwinter$equipment().setItem(slot.ordinal(), gear.read(slot.id(), ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
    }

    @Inject(method = "dropEquipment(Lnet/minecraft/server/level/ServerLevel;)V", at = @At("TAIL"))
    private void darkwinter$dropGear(ServerLevel level, CallbackInfo ci) {
        if (level.getGameRules().get(GameRules.KEEP_INVENTORY)) return;
        Player player = (Player) (Object) this;
        for (int i = 0; i < darkwinter$equipment().getContainerSize(); i++) {
            ItemStack stack = darkwinter$equipment().removeItemNoUpdate(i);
            if (!stack.isEmpty()) player.drop(stack, true, Prediction.SERVER_ONLY);
        }
    }
}
