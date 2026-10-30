// Routes inventory opening through the custom equipment screen while preserving creative access.
package com.dark.winter.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

public final class DarkWinterInventoryHooks {
    private static boolean setupDone = false;
    private static boolean creativeScreenRequested = false;

    private DarkWinterInventoryHooks() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) {
                setupDone = false;
                creativeScreenRequested = false;
                return;
            }
            if (!setupDone) {
                // Inventory = TAB. TAB previously opens the player list, so unbind it.
                client.options.keyInventory.setKey(
                        InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_TAB));
                client.options.keyPlayerList.setKey(InputConstants.UNKNOWN);
                KeyMapping.resetMapping();
                client.options.save();
                setupDone = true;
            }
            Screen screen = client.gui.screen();
            if (screen instanceof InventoryScreen
                    || (screen instanceof CreativeModeInventoryScreen && !creativeScreenRequested)) {
                client.gui.setScreen(new DarkWinterInventoryScreen(client.player));
                return;
            }
            if (!(screen instanceof CreativeModeInventoryScreen)) {
                creativeScreenRequested = false;
            }
        });
    }

    public static void allowCreativeScreen() {
        creativeScreenRequested = true;
    }
}
