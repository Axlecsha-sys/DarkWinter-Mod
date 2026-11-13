package com.dark.winter.client.mixin.client;

import com.dark.winter.client.DebugMenuStyle;
import com.dark.winter.client.HudStyle;
import com.dark.winter.client.MenuStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.debug.DebugOptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Short visual status captions; the full original messages remain available to narration. */
@Mixin(AbstractButton.class)
public abstract class DebugStatusLabelMixin {
    @Inject(method="extractDefaultLabel",at=@At("HEAD"),cancellable=true)
    private void darkwinter$caption(ActiveTextCollector text,CallbackInfo ci) {
        if(!(Minecraft.getInstance().gui.screen() instanceof DebugOptionsScreen))return;
        var button=(AbstractWidget)(Object)this;Component caption=null;
        if(button instanceof CycleButton<?> cycle&&cycle.getValue() instanceof Boolean)caption=DebugMenuStyle.statusCaption(button);
        else if(MenuStyle.key(button,"debug.options.profile.performance"))caption=Component.translatable("menu.darkwinter.debug.profile.performance");
        if(caption==null)return;
        text.acceptScrollingWithDefaultCenter(HudStyle.strong(caption).copy().withColor(HudStyle.MUTED),
                button.getX()+4,button.getRight()-4,button.getY(),button.getBottom());ci.cancel();
    }
}
