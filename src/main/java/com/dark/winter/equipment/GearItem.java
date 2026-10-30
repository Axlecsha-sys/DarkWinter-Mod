// Defines wearable items with equipment-slot placement and descriptive tooltips.
package com.dark.winter.equipment;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

public final class GearItem extends WeightedItem {
    private final GearSlot slot;

    public GearItem(Properties properties, GearSlot slot) {
        super(properties);
        this.slot = slot;
    }

    public GearSlot slot() { return slot; }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        var equipment = EquipmentAccess.of(player);
        if (!equipment.getItem(slot.ordinal()).isEmpty()) return InteractionResult.PASS;
        if (!level.isClientSide()) {
            equipment.setItem(slot.ordinal(), player.getItemInHand(hand).split(1));
            player.inventoryMenu.broadcastChanges();
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        lines.accept(Component.translatable("equipment.darkwinter." + slot.id()));
        lines.accept(Component.translatable("tooltip.darkwinter.equip"));
    }
}
