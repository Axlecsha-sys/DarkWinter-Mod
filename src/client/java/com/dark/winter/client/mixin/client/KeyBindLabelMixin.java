// Positions key-binding labels and hints within the custom settings layout.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import com.dark.winter.client.MenuText;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyBindsList.KeyEntry.class)
public abstract class KeyBindLabelMixin {
    @Shadow @Final private Button changeButton;
    @Shadow @Final private Button resetButton;
    @Shadow @Final private Component name;
    @Unique private boolean darkwinter$shortened;
    @Unique private int darkwinter$labelX,darkwinter$labelY;

    @Inject(method="<init>",at=@At("TAIL"))
    private void darkwinter$rowGap(CallbackInfo ci) {
        changeButton.setHeight(18);
        resetButton.setHeight(18);
    }

    @Redirect(method="extractContent",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
    private void darkwinter$name(GuiGraphicsExtractor g,Font font,Component text,int x,int y,int color) {
        Component full=HudStyle.font(text), fitted=MenuText.fit(full,Math.max(0,changeButton.getX()-10-x));
        darkwinter$shortened=fitted!=full;
        darkwinter$labelX=x; darkwinter$labelY=y;
        g.text(font,fitted,x,y,color);
    }

    @Inject(method="extractContent",at=@At("TAIL"))
    private void darkwinter$hint(GuiGraphicsExtractor g,int mx,int my,boolean hovered,float dt,CallbackInfo ci) {
        if(darkwinter$shortened && hovered && mx>=darkwinter$labelX && mx<changeButton.getX()-6
                && my>=darkwinter$labelY-4 && my<=darkwinter$labelY+13)
            g.setTooltipForNextFrame(net.minecraft.client.gui.components.Tooltip.splitTooltip(net.minecraft.client.Minecraft.getInstance(),HudStyle.font(name)),mx,my);
    }
}
