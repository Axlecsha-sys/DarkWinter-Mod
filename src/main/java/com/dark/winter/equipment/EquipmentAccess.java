// Named clothing slots appended to the existing player inventory menu.
package com.dark.winter.equipment;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;

/** Named clothing slots appended to the existing player inventory menu. */
public interface EquipmentAccess {
    SimpleContainer darkwinter$equipment();

    static SimpleContainer of(Player player) { return ((EquipmentAccess) player).darkwinter$equipment(); }
}
