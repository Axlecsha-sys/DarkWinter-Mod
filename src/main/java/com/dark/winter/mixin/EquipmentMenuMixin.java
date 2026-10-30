// Adds equipment slots and shift-click routing to the native player inventory menu.
package com.dark.winter.mixin;

import com.dark.winter.equipment.*;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InventoryMenu.class)
public abstract class EquipmentMenuMixin extends AbstractContainerMenu {
    protected EquipmentMenuMixin(MenuType<?> type, int id) { super(type, id); }

    @Inject(method = "<init>(Lnet/minecraft/world/entity/player/Inventory;ZLnet/minecraft/world/entity/player/Player;)V", at = @At("TAIL"))
    private void darkwinter$addGearSlots(Inventory inventory, boolean active, Player player, CallbackInfo ci) {
        for (GearSlot gear : GearSlot.values()) {
            addSlot(new Slot(EquipmentAccess.of(player), gear.ordinal(), -10000, -10000) {
                @Override public boolean mayPlace(ItemStack stack) { return gear.accepts(stack); }
                @Override public int getMaxStackSize() { return 1; }
            });
        }
    }

    @Inject(method = "quickMoveStack(Lnet/minecraft/world/entity/player/Player;I)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true)
    private void darkwinter$quickMoveGear(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        if (index < 0 || index >= slots.size()) return;
        Slot source = slots.get(index);
        ItemStack stack = source.getItem();
        int start, end;
        if (index >= GearSlot.MENU_START) {
            start = 9; end = 45;
        } else if (index >= 9 && index < 45 && stack.getItem() instanceof GearItem item
                && !slots.get(GearSlot.MENU_START + item.slot().ordinal()).hasItem()) {
            start = GearSlot.MENU_START + item.slot().ordinal(); end = start + 1;
        } else return;
        ItemStack original = stack.copy();
        if (!moveItemStackTo(stack, start, end, false)) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }
        if (stack.isEmpty()) source.setByPlayer(ItemStack.EMPTY, original);
        else source.setChanged();
        source.onTake(player, stack);
        cir.setReturnValue(original);
    }
}
