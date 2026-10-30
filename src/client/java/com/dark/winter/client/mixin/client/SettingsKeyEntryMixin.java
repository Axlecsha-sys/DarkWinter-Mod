// Keeps both native key-binding buttons reachable in a narrow settings pane.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import com.dark.winter.client.MenuText;
import com.dark.winter.client.SettingsNavigation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps both native key-binding buttons reachable in a narrow settings pane. */
@Mixin(targets="net.minecraft.client.gui.screens.options.controls.KeyBindsList$KeyEntry")
public abstract class SettingsKeyEntryMixin {
    @Shadow @Final private Component name;
    @Shadow @Final private Button changeButton;
    @Shadow @Final private Button resetButton;
    @Shadow private boolean hasCollision;
    @Shadow private void updateChangeButtonMessage() { throw new AssertionError(); }
    @Unique private int darkwinter$normalBindWidth = -1;
    @Unique private int darkwinter$normalResetWidth = -1;

    @Inject(method="extractContent", at=@At("HEAD"), cancellable=true)
    private void darkwinter$compactKeys(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                       boolean hovered, float partialTick, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = minecraft.gui.screen();
        if (screen == null || !SettingsNavigation.applies(screen)) return;
        if (darkwinter$normalBindWidth < 0) {
            darkwinter$normalBindWidth = changeButton.getWidth();
            darkwinter$normalResetWidth = resetButton.getWidth();
        }
        if (!SettingsNavigation.keyCompact(screen)) {
            // A GUI resize can return this same row to the native one-line layout.
            changeButton.setWidth(darkwinter$normalBindWidth);
            resetButton.setWidth(darkwinter$normalResetWidth);
            return;
        }
        updateChangeButtonMessage();
        var row = (ContainerObjectSelectionList.Entry<?>)(Object)this;
        int x = row.getContentX(), y = row.getContentY(), width = Math.max(1, row.getContentWidth());
        int resetWidth = Math.min(50, Math.max(1, width - 9));
        int bindWidth = Math.max(1, width - resetWidth - 8);
        int buttonY = y + 14;
        changeButton.setPosition(x, buttonY);
        changeButton.setWidth(bindWidth);
        changeButton.setHeight(18);
        resetButton.setPosition(x + bindWidth + 8, buttonY);
        resetButton.setWidth(resetWidth);
        resetButton.setHeight(18);
        Component full = HudStyle.font(name);
        Component fitted = MenuText.fit(full, width);
        graphics.text(minecraft.font, fitted, x, y, -1);
        changeButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
        resetButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (hasCollision) graphics.fill(x - 6, buttonY - 1, x - 3, buttonY + 19, -256);
        if (fitted != full && hovered && mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + 12) {
            graphics.setTooltipForNextFrame(Tooltip.splitTooltip(minecraft, full), mouseX, mouseY);
        }
        ci.cancel();
    }
}
