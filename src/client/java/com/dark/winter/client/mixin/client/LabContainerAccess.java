// Exposes native container origins to laboratory UI inspection.
package com.dark.winter.client.mixin.client;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(AbstractContainerScreen.class)
public interface LabContainerAccess {
    @Accessor("leftPos") int darkwinter$left();
    @Accessor("topPos") int darkwinter$top();
}
