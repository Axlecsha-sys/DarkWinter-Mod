// Defines named equipment slots and the item types each slot accepts.
package com.dark.winter.equipment;

public enum GearSlot {
    SOCKS, PANTS, GLOVES, HAT, JACKET, BACKPACK, FOOTWEAR, TSHIRT, SWEATER;

    public static final int MENU_START = 46;

    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }

    public boolean accepts(net.minecraft.world.item.ItemStack stack) {
        return stack.getItem() instanceof GearItem item && item.slot() == this;
    }
}
