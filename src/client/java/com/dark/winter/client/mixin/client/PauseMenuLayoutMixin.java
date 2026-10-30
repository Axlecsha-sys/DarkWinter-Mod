// Arranges native pause-menu actions and Testing connection controls.
package com.dark.winter.client.mixin.client;

import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.FriendsButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseMenuLayoutMixin extends Screen {
    protected PauseMenuLayoutMixin(Component title) { super(title); }
    @Inject(method="init",at=@At("TAIL"))
    private void darkwinter$soloActions(CallbackInfo ci) {
        if(!((PauseScreen)(Object)this).showsPauseMenu())return;
        boolean local=minecraft.isLocalServer();
        boolean remoteTesting=!local&&com.dark.winter.client.online.OnlineConnectionClient.authenticated()
                &&com.dark.winter.client.online.OnlineTestingView.remote()&&com.dark.winter.client.online.OnlineTestingView.revision()>=0;
        boolean testing=local?com.dark.winter.client.TestingWorlds.isTesting():remoteTesting;
        if(!local&&!remoteTesting)return;
        var remove=java.util.Set.of(Component.translatable("menu.sendFeedback").getString(),Component.translatable("menu.reportBugs").getString(),
                Component.translatable("menu.playerReporting").getString(),Component.translatable("gui.advancements").getString());
        for(var child:new java.util.ArrayList<>(children())) if(child instanceof AbstractWidget w
                &&(w instanceof FriendsButton||local&&remove.contains(w.getMessage().getString())))removeWidget(w);
        if(local)addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.translatable("menu.darkwinter.interface"),b->minecraft.gui.setScreen(new com.dark.winter.client.UiPreferencesScreen((Screen)(Object)this))).build());
        var friends=testing?addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Соединения"),
                b->com.dark.winter.client.online.FriendsScreen.open((Screen)(Object)this)).build()):null;
        int top=com.dark.winter.client.UiLayout.pauseTop(height),row=0;
        var buttons=new java.util.ArrayList<AbstractButton>();
        for(var child:new java.util.ArrayList<>(children())) {
            if(child instanceof AbstractButton b) buttons.add(b);
            else if(child instanceof StringWidget title) removeWidget(title);
        }
        buttons.sort(java.util.Comparator.comparingInt(b->com.dark.winter.client.MenuStyle.key(b,"menu.returnToGame")?0:b==friends?1:com.dark.winter.client.MenuStyle.key(b,"menu.options")?2:com.dark.winter.client.MenuStyle.key(b,"menu.darkwinter.interface")?3:com.dark.winter.client.MenuStyle.key(b,"gui.stats")?4:com.dark.winter.client.MenuStyle.key(b,"menu.returnToMenu")?10:5));
        int step=27,buttonHeight=24;
        if(friends!=null){
            step=Math.min(27,Math.max(1,(height-top-8)/Math.max(1,buttons.size())));
            buttonHeight=Math.min(24,Math.max(1,step-3));
        }
        for(var b:buttons) {
            b.setWidth(com.dark.winter.client.MenuStyle.rowWidth(width));b.setHeight(buttonHeight);
            b.setPosition(com.dark.winter.client.MenuStyle.left(width),top+row++*step);
        }
    }
}
