// Calculates item and stack mass using integer grams.
package com.dark.winter.equipment;

import com.dark.winter.registry.ModDataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Integer grams avoid rounding errors when stacks are combined. Unspecified items have no assigned mass yet. */
public final class ItemMass {
    private ItemMass() { }

    public static long grams(ItemStack stack) {
        return stack.isEmpty() ? 0 : (long) stack.getOrDefault(ModDataComponents.MASS_GRAMS, 0) * stack.getCount();
    }

    public static long grams(Container inventory) {
        long result = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) result += grams(inventory.getItem(i));
        return result;
    }

    /** Includes carried items, vanilla equipment, custom equipment and the menu cursor exactly once. */
    public static long carriedGrams(Player player) {
        return grams(player.getInventory()) + grams(EquipmentAccess.of(player)) + grams(player.containerMenu.getCarried());
    }

    public static String kilograms(long grams) {
        return java.math.BigDecimal.valueOf(grams, 3).stripTrailingZeros().toPlainString();
    }
}
