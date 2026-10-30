// Sizes native world-selection cards for the available screen area.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.MenuStyle;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldSelectionList.class)
public abstract class WorldListLayoutMixin {
    @ModifyConstant(method="<init>",constant=@Constant(intValue=36))
    private static int darkwinter$cardHeight(int original) {return net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight()<330?40:54;}
    @Inject(method="getRowWidth",at=@At("HEAD"),cancellable=true)
    private void darkwinter$cardWidth(CallbackInfoReturnable<Integer> cir) {
        var screen=((WorldSelectionList)(Object)this).getScreen();
        if(screen instanceof net.minecraft.client.gui.screens.worldselection.SelectWorldScreen)
            cir.setReturnValue(Math.min(640,screen.width-MenuStyle.left(screen.width)*2));
    }
}
