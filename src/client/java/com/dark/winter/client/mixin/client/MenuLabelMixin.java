// Styles native menu labels while preserving their full messages and input geometry.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import com.dark.winter.client.MenuStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.dark.winter.client.MenuText;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Presentation only: full messages, narration and widget hit regions remain intact. */
@Mixin(AbstractWidget.class)
public abstract class MenuLabelMixin {
    @Shadow @Final private WidgetTooltipHolder tooltip;
    @Unique private Tooltip darkwinter$base, darkwinter$expanded;
    @Unique private Component darkwinter$full;

    @Inject(method="extractScrollingStringOverContents",at=@At("HEAD"),cancellable=true)
    private void darkwinter$label(ActiveTextCollector output,Component message,int margin,CallbackInfo ci) {
        if(!MenuStyle.themed(Minecraft.getInstance().gui.screen())) return;
        var widget=(AbstractWidget)(Object)this;
        if(message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().equals("selectWorld.create")) message=Component.literal("+ ").append(message);
        Component full=HudStyle.font(message);
        int padding=Math.max(margin,6), available=Math.max(0,widget.getWidth()-padding*2);
        Component fitted=MenuText.fit(full,available);
        Tooltip current=tooltip.get();
        boolean baseChanged=darkwinter$expanded==null || current!=darkwinter$expanded;
        if(baseChanged) darkwinter$base=current;
        if(fitted!=full) {
            if(baseChanged || darkwinter$expanded==null || !full.equals(darkwinter$full)) {
                var detail=full.copy();
                if(darkwinter$base!=null) detail.append("\n").append(((MenuTooltipAccessor)(Object)darkwinter$base).darkwinter$message());
                darkwinter$expanded=Tooltip.create(detail,
                        darkwinter$base==null?java.util.Optional.empty():darkwinter$base.component(),
                        darkwinter$base==null?null:darkwinter$base.style());
                darkwinter$full=full;
            }
            tooltip.set(darkwinter$expanded);
        } else {
            tooltip.set(darkwinter$base);
            darkwinter$expanded=null;
        }
        output.acceptScrollingWithDefaultCenter(fitted,widget.getX()+padding,
                widget.getX()+widget.getWidth()-padding,widget.getY(),widget.getY()+widget.getHeight());
        ci.cancel();
    }
}
