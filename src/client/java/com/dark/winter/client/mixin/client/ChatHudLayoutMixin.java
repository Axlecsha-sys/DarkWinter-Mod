// Drawing and clickable text share this height argument, keeping links aligned above the HUD.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.SurvivalNeedsHud;
import com.dark.winter.client.DateTimeHud;
import com.dark.winter.client.TestingAccess;
import com.dark.winter.client.TestingClient;
import com.dark.winter.client.ChatHudVisibility;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Drawing and clickable text share this height argument, keeping links aligned above the HUD. */
@Mixin(ChatComponent.class)
public abstract class ChatHudLayoutMixin implements ChatHudVisibility {
    @Shadow @Final private List<GuiMessage.Line> trimmedMessages;

    @Override public boolean darkwinter$hasVisibleMessages(int guiTick) {
        if(Minecraft.getInstance().options.chatVisibility().get()==ChatVisiblity.HIDDEN) return false;
        return trimmedMessages.stream().anyMatch(line->guiTick-line.addedTime()<200);
    }

    @Inject(method="extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V",
            at=@At("HEAD"),cancellable=true)
    private void darkwinter$keepMenusClear(CallbackInfo ci) {
        var screen=Minecraft.getInstance().gui.screen();
        if(screen!=null && !(screen instanceof ChatScreen)) ci.cancel();
    }
    @Inject(method="getWidth()I",at=@At("RETURN"),cancellable=true)
    private void darkwinter$leaveLabPanelClear(CallbackInfoReturnable<Integer> cir) {
        var client=Minecraft.getInstance();
        if(TestingClient.active()) {
            int available=TestingClient.hudPanelLeft(client.getWindow().getGuiScaledWidth())-12;
            cir.setReturnValue(Math.min(cir.getReturnValue(),Math.max(40,available)));
        }
    }
    @Inject(method="getHeight()I",at=@At("RETURN"),cancellable=true)
    private void darkwinter$fitBelowHud(CallbackInfoReturnable<Integer> cir) {
        var client=Minecraft.getInstance();
        if(TestingAccess.enabled("hud") && client.player!=null && !client.gui.hud.isHidden()
                && (client.gui.screen()==null || client.gui.screen() instanceof ChatScreen)) {
            int bottom=SurvivalNeedsHud.chatBottom(client.getWindow().getGuiScaledWidth(),client.getWindow().getGuiScaledHeight());
            int available=(int)((bottom-DateTimeHud.panelBottom(client.getWindow().getGuiScaledWidth())-8)
                    /Math.max(.01,client.options.chatScale().get()));
            cir.setReturnValue(Math.min(cir.getReturnValue(),Math.max(9,available)));
        }
    }

    @ModifyVariable(method="extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V",
            at=@At("HEAD"),argsOnly=true,ordinal=0)
    private int darkwinter$raiseChat(int screenHeight) {
        var client=Minecraft.getInstance();
        if(TestingAccess.enabled("hud") && client.player!=null && !client.gui.hud.isHidden()
                && (client.gui.screen()==null || client.gui.screen() instanceof ChatScreen))
            return SurvivalNeedsHud.chatBottom(client.getWindow().getGuiScaledWidth(),screenHeight)+40;
        return screenHeight;
    }
}
