// Exposes native container-panel coordinates for custom surface rendering.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.ContainerSurfaceAccess;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(AbstractContainerScreen.class)
public abstract class ContainerSurfaceMixin implements ContainerSurfaceAccess {
    @Shadow protected int leftPos;
    @Shadow protected int topPos;
    public int darkwinter$panelLeft(){return leftPos;}
    public int darkwinter$panelTop(){return topPos;}
}
