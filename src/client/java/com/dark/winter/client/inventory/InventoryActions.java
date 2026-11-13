package com.dark.winter.client.inventory;

import com.dark.winter.equipment.GearItem;
import com.dark.winter.equipment.GearSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;

/** All changes go through the existing native client prediction and server container protocol. */
public final class InventoryActions {
    private InventoryActions() {}
    private static boolean ready(InventoryMenu menu) {
        var c=Minecraft.getInstance();return c.player!=null&&c.gameMode!=null&&c.player.containerMenu==menu&&menu.getCarried().isEmpty();
    }
    public static void click(InventoryMenu menu,int slot,int button,ContainerInput input) {
        var c=Minecraft.getInstance();
        if(c.player==null||c.gameMode==null||c.player.containerMenu!=menu||slot<0||slot>=menu.slots.size())return;
        c.gameMode.handleContainerInput(menu.containerId,slot,button,input,c.player);
    }
    public static boolean equip(InventoryMenu menu,int source) {
        if(!ready(menu)||source<9||source>45||source>=menu.slots.size())return false;
        var stack=menu.slots.get(source).getItem();if(!(stack.getItem() instanceof GearItem item))return false;
        int target=GearSlot.MENU_START+item.slot().ordinal();if(target>=menu.slots.size()||!menu.slots.get(target).mayPlace(stack))return false;
        click(menu,source,0,ContainerInput.PICKUP);click(menu,target,0,ContainerInput.PICKUP);
        // Returns the previous garment (or any stack remainder) to the original source slot.
        if(!menu.getCarried().isEmpty())click(menu,source,0,ContainerInput.PICKUP);
        return menu.getCarried().isEmpty();
    }
    public static boolean remove(InventoryMenu menu,GearSlot gear) {
        int source=GearSlot.MENU_START+gear.ordinal(),empty=InventoryCatalog.firstEmpty(menu);
        if(!ready(menu)||empty<0||source>=menu.slots.size()||!menu.slots.get(source).hasItem())return false;
        click(menu,source,0,ContainerInput.PICKUP);click(menu,empty,0,ContainerInput.PICKUP);return menu.getCarried().isEmpty();
    }
    public static void drop(InventoryMenu menu,int source,boolean entireStack) {
        if(ready(menu)&&source>=9&&source<menu.slots.size())click(menu,source,entireStack?1:0,ContainerInput.THROW);
    }
    public static boolean hand(InventoryMenu menu,int source) {
        if(!ready(menu)||source<9||source>45||!menu.slots.get(source).hasItem())return false;
        int selected=Minecraft.getInstance().player.getInventory().getSelectedSlot();
        if(source!=36+selected)click(menu,source,selected,ContainerInput.SWAP);return true;
    }
}
