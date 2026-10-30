// Adds mass information to item tooltips.
package com.dark.winter.equipment;

import com.dark.winter.registry.ModDataComponents;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

public class WeightedItem extends Item {
    public WeightedItem(Properties properties) { super(properties); }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        lines.accept(Component.translatable("tooltip.darkwinter.mass", ItemMass.kilograms(stack.getOrDefault(ModDataComponents.MASS_GRAMS, 0))));
        if (stack.getCount() > 1)
            lines.accept(Component.translatable("tooltip.darkwinter.stack_mass", ItemMass.kilograms(ItemMass.grams(stack))));
    }
}
