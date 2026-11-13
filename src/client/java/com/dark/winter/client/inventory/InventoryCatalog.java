package com.dark.winter.client.inventory;

import com.dark.winter.client.vector.InventoryGlyph;
import com.dark.winter.equipment.GearItem;
import com.dark.winter.equipment.GearSlot;
import com.dark.winter.equipment.ItemMass;
import com.dark.winter.registry.ModDataComponents;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

/** Filtering only reorders native slot references. It never merges, moves or copies stored items. */
public final class InventoryCatalog {
    public enum Category {
        ALL("all",InventoryGlyph.PACK),FOOD("food",InventoryGlyph.FOOD),MEDICAL("medical",InventoryGlyph.MEDICAL),
        CLOTHES("clothes",InventoryGlyph.SHIRT),TOOLS("tools",InventoryGlyph.TOOL),MATERIALS("materials",InventoryGlyph.MATERIAL);
        public final String id;public final InventoryGlyph glyph;
        Category(String id,InventoryGlyph glyph){this.id=id;this.glyph=glyph;}
    }
    public enum Order {STORAGE,NAME,CONDITION,MASS}
    private InventoryCatalog() {}
    public static Category category(ItemStack stack) {
        if(stack.getItem() instanceof GearItem)return Category.CLOTHES;
        String id=BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if(contains(id,"bandage","medicine","antiseptic","painkiller","first_aid","healing"))return Category.MEDICAL;
        if(stack.has(DataComponents.FOOD)||stack.has(DataComponents.CONSUMABLE))return Category.FOOD;
        if(stack.has(DataComponents.TOOL)||stack.has(DataComponents.WEAPON)||stack.isDamageableItem()
                ||contains(id,"torch","lantern","compass","clock","spyglass","flint","matches","shears","bucket"))return Category.TOOLS;
        return Category.MATERIALS;
    }
    private static boolean contains(String value,String... fragments){for(String part:fragments)if(value.contains(part))return true;return false;}
    public static List<Integer> slots(InventoryMenu menu,Category category,Order order,GearSlot replacement) {
        var result=new ArrayList<Integer>();
        // 45 is the native off-hand. Armor and the 2x2 crafting grid never enter this view.
        for(int id=9;id<=45&&id<menu.slots.size();id++) {
            var stack=menu.slots.get(id).getItem();if(stack.isEmpty())continue;
            if(category!=Category.ALL&&category(stack)!=category)continue;
            if(replacement!=null&&(!(stack.getItem() instanceof GearItem gear)||gear.slot()!=replacement))continue;
            result.add(id);
        }
        Comparator<Integer> compare=switch(order) {
            case NAME->Comparator.comparing(id->menu.slots.get(id).getItem().getHoverName().getString(),java.text.Collator.getInstance());
            case CONDITION->Comparator.comparingDouble(id->{float c=condition(menu.slots.get(id).getItem());return c<0?101:c;});
            case MASS->Comparator.<Integer>comparingLong(id->ItemMass.grams(menu.slots.get(id).getItem())).reversed();
            default->Comparator.naturalOrder();
        };
        result.sort(compare.thenComparingInt(Integer::intValue));return List.copyOf(result);
    }
    public static float condition(ItemStack stack) {
        Integer value=stack.get(ModDataComponents.CONDITION);if(value!=null)return value;
        return stack.isDamageableItem()?100f*(stack.getMaxDamage()-stack.getDamageValue())/Math.max(1,stack.getMaxDamage()):-1;
    }
    public static int firstEmpty(InventoryMenu menu) {
        for(int id=9;id<=44&&id<menu.slots.size();id++)if(!menu.slots.get(id).hasItem())return id;
        return -1;
    }
    public static long fingerprint(InventoryMenu menu) {
        long result=menu.getStateId();
        for(var slot:menu.slots) {var stack=slot.getItem();result=result*31+System.identityHashCode(stack.getItem());result=result*31+stack.getCount();result=result*31+stack.getComponents().hashCode();}
        return result*31+menu.getCarried().getCount();
    }
}
