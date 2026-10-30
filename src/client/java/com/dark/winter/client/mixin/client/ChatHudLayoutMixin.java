// Drawing and clickable text share this height argument, keeping links aligned above the HUD.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.SurvivalNeedsHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Drawing and clickable text share this height argument, keeping links aligned above the HUD. */
@Mixin(ChatComponent.class)
public abstract class ChatHudLayoutMixin {
    @Inject(method="getHeight()I",at=@At("RETURN"),cancellable=true)
    private void darkwinter$fitBelowHud(CallbackInfoReturnable<Integer> cir) {
        var client=Minecraft.getInstance();
        if(client.player!=null && !client.gui.hud.isHidden()
                && (client.gui.screen()==null || client.gui.screen() instanceof ChatScreen)) {
            int bottom=SurvivalNeedsHud.chatBottom(client.getWindow().getGuiScaledWidth(),client.getWindow().getGuiScaledHeight());
            int available=(int)((bottom-98)/client.options.chatScale().get());
            cir.setReturnValue(Math.min(cir.getReturnValue(),Math.max(9,available)));
        }
    }

    @ModifyVariable(method="extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V",
            at=@At("HEAD"),argsOnly=true,ordinal=0)
    private int darkwinter$raiseChat(int screenHeight) {
        var client=Minecraft.getInstance();
        if(client.player!=null && !client.gui.hud.isHidden()
                && (client.gui.screen()==null || client.gui.screen() instanceof ChatScreen))
            return SurvivalNeedsHud.chatBottom(client.getWindow().getGuiScaledWidth(),screenHeight)+40;
        return screenHeight;
    }
}
