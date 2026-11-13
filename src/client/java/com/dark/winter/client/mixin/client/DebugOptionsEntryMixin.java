package com.dark.winter.client.mixin.client;

import com.dark.winter.client.DebugMenuStyle;
import com.dark.winter.client.HudStyle;
import com.dark.winter.client.vector.MenuSurface;
import com.dark.winter.client.vector.SvgHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="net.minecraft.client.gui.screens.debug.DebugOptionsScreen$OptionEntry")
public abstract class DebugOptionsEntryMixin {
    @Shadow @Final private Identifier location;
    @Shadow @Final private CycleButton<Boolean> always;
    @Shadow @Final private CycleButton<Boolean> overlay;
    @Shadow @Final private CycleButton<Boolean> never;
    @Shadow @Final private boolean isAllowed;

    @Redirect(method="<init>",at=@At(value="INVOKE",target="Lnet/minecraft/resources/Identifier;getPath()Ljava/lang/String;"))
    private String darkwinter$name(Identifier id) {return DebugMenuStyle.entryName(id.getPath()).getString();}

    @Inject(method="extractContent",at=@At("HEAD"),cancellable=true)
    private void darkwinter$row(GuiGraphicsExtractor g,int mx,int my,boolean hovered,float dt,CallbackInfo ci) {
        var row=(LayoutElement)this;int x=row.getX()+8,y=row.getY(),right=row.getX()+row.getWidth()-6;
        int bw=54,gap=3,start=right-3*bw-2*gap;
        HudStyle.fit(g,DebugMenuStyle.entryName(location.getPath()),x,y+8,Math.max(1,start-x-8),.88f,isAllowed?HudStyle.INK:HudStyle.MUTED);
        int i=0;
        for(var button:java.util.List.of(never,overlay,always)) {
            button.setPosition(start+i++*(bw+gap),y+4);button.setSize(bw,18);
            // Paint the selected state explicitly; the native button stays disabled
            // and retains its native narration/value/click behavior.
            if(button.getValue()) {
                SvgHud.surface(g,button.getX(),button.getY(),bw,18,MenuSurface.SELECTED,.65f,HudStyle.STABLE);
                HudStyle.centered(g,HudStyle.strong(DebugMenuStyle.statusCaption(button)).copy().withColor(HudStyle.INK),button.getX()+bw/2,y+9,1f,HudStyle.INK);
            } else button.extractRenderState(g,mx,my,dt);
        }
        if(hovered&&mx<start) {
            Component tip=HudStyle.font(DebugMenuStyle.entryName(location.getPath())).copy().append("\n").append(location.toString());
            if(!isAllowed)tip=tip.copy().append("\n").append(Component.translatable("debug.options.notAllowed.tooltip"));
            g.setTooltipForNextFrame(tip,mx,my);
        }
        ci.cancel();
    }
}
