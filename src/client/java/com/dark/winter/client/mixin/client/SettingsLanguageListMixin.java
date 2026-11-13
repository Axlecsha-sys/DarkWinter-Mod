package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Full-width rows; the scrollbar's painted and interactive positions are identical. */
@SuppressWarnings("rawtypes")
@Mixin(targets="net.minecraft.client.gui.screens.options.LanguageSelectScreen$LanguageSelectionList")
public abstract class SettingsLanguageListMixin extends ObjectSelectionList {
    protected SettingsLanguageListMixin(Minecraft minecraft,int w,int h,int y,int rowHeight) {super(minecraft,w,h,y,rowHeight);}

    @ModifyArg(method="<init>",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/components/ObjectSelectionList;<init>(Lnet/minecraft/client/Minecraft;IIII)V"),index=4)
    private static int darkwinter$rowHeight(int original) {return 26;}

    @Inject(method="getRowWidth",at=@At("HEAD"),cancellable=true)
    private void darkwinter$rowWidth(CallbackInfoReturnable<Integer> cir) {cir.setReturnValue(Math.max(1,getWidth()-14));}

    @Override public int getRowLeft() {return getX()+2;}
    @Override protected int scrollBarX() {return getRight()-7;}
    @Override protected void extractListBackground(GuiGraphicsExtractor g) {
        g.fill(getX(),getY(),getRight(),getBottom(),0x26040C14);
    }
    @Override protected void extractListSeparators(GuiGraphicsExtractor g) {
        g.fill(getX(),getY()-1,getRight(),getY(),HudStyle.alpha(HudStyle.MUTED,.23f));
        g.fill(getX(),getBottom(),getRight(),getBottom()+1,HudStyle.alpha(HudStyle.MUTED,.23f));
    }
}
