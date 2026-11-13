package com.dark.winter.client.mixin.client;

import com.dark.winter.client.DebugMenuStyle;
import com.dark.winter.client.HudStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@SuppressWarnings("rawtypes")
@Mixin(targets="net.minecraft.client.gui.screens.debug.DebugOptionsScreen$OptionList")
public abstract class DebugOptionsListMixin extends ContainerObjectSelectionList {
    protected DebugOptionsListMixin(Minecraft minecraft,int w,int h,int y,int rowHeight) {super(minecraft,w,h,y,rowHeight);}
    @ModifyArg(method="<init>",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/components/ContainerObjectSelectionList;<init>(Lnet/minecraft/client/Minecraft;IIII)V"),index=4)
    private static int darkwinter$height(int original) {return 26;}
    @Inject(method="getRowWidth",at=@At("HEAD"),cancellable=true)
    private void darkwinter$width(CallbackInfoReturnable<Integer> cir) {cir.setReturnValue(Math.max(1,getWidth()-14));}
    @Override public int getRowLeft() {return getX()+2;}
    @Override protected int scrollBarX() {return getRight()-7;}
    @Override protected void extractListBackground(GuiGraphicsExtractor g) {g.fill(getX(),getY(),getRight(),getBottom(),0x26040C14);}
    @Override protected void extractListSeparators(GuiGraphicsExtractor g) {
        g.fill(getX(),getY()-1,getRight(),getY(),0x3B93AEBB);g.fill(getX(),getBottom(),getRight(),getBottom()+1,0x3B93AEBB);
    }
    @Redirect(method="updateSearch",at=@At(value="INVOKE",target="Ljava/lang/String;contains(Ljava/lang/CharSequence;)Z"))
    private boolean darkwinter$localizedSearch(String code,CharSequence input) {
        String query=input.toString().toLowerCase(java.util.Locale.ROOT);
        return code.toLowerCase(java.util.Locale.ROOT).contains(query)||DebugMenuStyle.entryName(code).getString().toLowerCase(java.util.Locale.ROOT).contains(query);
    }
}
