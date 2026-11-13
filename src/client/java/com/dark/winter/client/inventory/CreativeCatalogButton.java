package com.dark.winter.client.inventory;

import com.dark.winter.client.GuiSurfaceArt;
import com.dark.winter.client.HudStyle;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A native focusable button occupying the spare bottom creative-tab position. */
public final class CreativeCatalogButton extends Button {
    private static final Identifier LOGO = Identifier.fromNamespaceAndPath(
            "darkwinter", "textures/gui/creative/darkwinter_logo.png");
    private final BooleanSupplier darkWinter;
    private final ItemStack minecraftIcon = new ItemStack(Items.GRASS_BLOCK);

    public CreativeCatalogButton(int x, int y, BooleanSupplier darkWinter, Runnable switchCatalog) {
        super(x, y, 26, 28, destination(darkWinter.getAsBoolean()),
                button -> switchCatalog.run(), Button.DEFAULT_NARRATION);
        this.darkWinter = darkWinter;
        refreshLabel();
    }

    private static Component destination(boolean darkWinter) {
        return Component.translatable(darkWinter
                ? "creative.darkwinter.open_minecraft" : "creative.darkwinter.open_darkwinter");
    }

    public void refreshLabel() {
        setMessage(destination(darkWinter.getAsBoolean()));
        setTooltip(Tooltip.create(HudStyle.font(getMessage())));
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        boolean highlighted = isHoveredOrFocused();
        GuiSurfaceArt.frame(graphics, getX(), getY(), width, height,
                highlighted ? HudStyle.STABLE : 0xFF506B7A, true);
        if (highlighted) {
            graphics.fill(getX() + 1, getY() + 1, getX() + width - 1,
                    getY() + height - 1, 0x184F8197);
        }
        if (darkWinter.getAsBoolean()) {
            graphics.item(minecraftIcon, getX() + 5, getY() + 3);
        } else {
            // Use the occupied square of the original transparent PNG; no per-frame image processing.
            graphics.blit(RenderPipelines.GUI_TEXTURED, LOGO, getX() + 3, getY() + 1,
                    128.0F, 156.0F, 20, 20, 950, 950, 1254, 1254, -1);
        }
    }
}
