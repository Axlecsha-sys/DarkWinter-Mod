package com.dark.winter.client.inventory;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** One user-requested native consume action, with a bounded lease on the use key. No item effects are simulated here. */
public final class InventoryUseAction {
    private static boolean initialized,ownsUse,pending;
    private static int delay,selected;
    private static ItemStack intended=ItemStack.EMPTY;
    private static Object level;
    private static java.util.UUID player;
    private static long began;
    private InventoryUseAction() {}
    public static void init() {
        if(initialized)return;initialized=true;
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            boolean same=client.player!=null&&client.level==level&&client.player.getUUID().equals(player);
            if(pending) {
                if(!same||client.gameMode==null||client.gui.screen()!=null||client.player.getInventory().getSelectedSlot()!=selected
                        ||!ItemStack.isSameItemSameComponents(intended,client.player.getMainHandItem())) {
                    pending=false;intended=ItemStack.EMPTY;level=null;player=null;return;
                }
                if(--delay>0)return;
                // Wait for the screen transition and native hotbar swap to settle before starting use.
                pending=false;intended=ItemStack.EMPTY;client.gameMode.useItem(client.player,InteractionHand.MAIN_HAND);
                if(client.player.isUsingItem()&&!client.options.keyUse.isDown()) {
                    ownsUse=true;began=System.nanoTime();client.options.keyUse.setDown(true);
                }else {level=null;player=null;}
                return;
            }
            if(!ownsUse)return;
            if(!same||client.gui.screen()!=null||!client.player.isUsingItem()||System.nanoTime()-began>60_000_000_000L) {
                client.options.keyUse.setDown(false);ownsUse=false;
                if(same&&client.player.isUsingItem()&&client.gameMode!=null)client.gameMode.releaseUsingItem(client.player);
                level=null;player=null;
            }
        });
    }
    public static boolean consumable(ItemStack stack){return stack.has(DataComponents.CONSUMABLE);}
    public static boolean begin() {
        var c=Minecraft.getInstance();
        if(c.player==null||c.gameMode==null||c.gui.screen()!=null||ownsUse||pending||!consumable(c.player.getMainHandItem()))return false;
        pending=true;delay=2;level=c.level;player=c.player.getUUID();selected=c.player.getInventory().getSelectedSlot();
        intended=c.player.getMainHandItem().copy();
        return true;
    }
}
