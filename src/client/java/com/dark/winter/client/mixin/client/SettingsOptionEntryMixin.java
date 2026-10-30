// Positions the original option widgets without replacing their handlers or narration.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.SettingsNavigation;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Positions the original option widgets without replacing their handlers or narration. */
@Mixin(targets="net.minecraft.client.gui.components.OptionsList$Entry")
public abstract class SettingsOptionEntryMixin {
    @Shadow @Final private List<OptionsList.OptionInstanceWidget> children;
    @Shadow @Final private Screen screen;
    @Unique private boolean darkwinter$singleShapeKnown;
    @Unique private boolean darkwinter$singleBig;

    @Inject(method="extractContent", at=@At("HEAD"), cancellable=true)
    private void darkwinter$rightPane(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                     boolean hovered, float partialTick, CallbackInfo ci) {
        if (!SettingsNavigation.applies(screen)) return;
        if (!darkwinter$singleShapeKnown) {
            darkwinter$singleBig = children.size() == 1 && children.getFirst().widget().getWidth() >= 300;
            darkwinter$singleShapeKnown = true;
        }
        int x = SettingsNavigation.optionLeft(screen);
        int width = SettingsNavigation.optionWidth(screen);
        boolean compact = SettingsNavigation.compact(screen);
        int y = ((ContainerObjectSelectionList.Entry<?>)(Object)this).getContentY();
        int columnWidth = Math.max(1, (width - 8) / 2);
        for (int index = 0; index < children.size(); index++) {
            var widget = children.get(index).widget();
            int widgetWidth = compact || darkwinter$singleBig ? width : columnWidth;
            widget.setWidth(widgetWidth);
            widget.setPosition(compact ? x : x + index * (columnWidth + 8),
                    compact ? y + index * 22 : y);
            widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
        ci.cancel();
    }
}
