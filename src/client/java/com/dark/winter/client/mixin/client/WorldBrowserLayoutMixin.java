// Arranges the native world browser without changing save metadata or world actions.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import com.dark.winter.client.MenuStyle;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.*;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Layout only: no reads/writes to save metadata, no replacement of world actions. */
@Mixin(SelectWorldScreen.class)
public abstract class WorldBrowserLayoutMixin extends Screen {
    @Shadow private Button playWorldButton;
    protected WorldBrowserLayoutMixin(Component title){super(title);}
    @Inject(method="repositionElements",at=@At("TAIL"))
    private void darkwinter$layout(CallbackInfo ci) {
        int left=MenuStyle.left(width),available=width-left*2;
        boolean compact=width<480;
        int footer=compact?57:35,top=72;
        var other=new java.util.ArrayList<Button>();
        for(var child:children()) {
            if(child instanceof WorldSelectionList list) list.updateSizeAndPosition(width,Math.max(40,height-top-footer-8),0,top);
            else if(child instanceof EditBox field){field.setPosition(left,43);field.setWidth(Math.min(240,available));}
            else if(child instanceof StringWidget label){label.setMessage(HudStyle.font(label.getMessage()));label.setPosition(left,18);}
            else if(child instanceof Button b) {
                if(MenuStyle.key(b,"selectWorld.create")){b.setPosition(width-left-Math.min(178,available/2),14);b.setWidth(Math.min(178,available/2));b.setHeight(21);}
                else if(b!=playWorldButton) other.add(b);
            }
        }
        if(compact) {
            playWorldButton.setPosition(left,height-56);playWorldButton.setWidth(available);playWorldButton.setHeight(20);
            int w=(available-6*Math.max(0,other.size()-1))/Math.max(1,other.size());
            for(int i=0;i<other.size();i++){var b=other.get(i);b.setPosition(left+i*(w+6),height-29);b.setWidth(Math.max(32,w));b.setHeight(20);}
        } else {
            int play=Math.round(available*.31f),rest=(available-play-6*other.size())/Math.max(1,other.size());
            playWorldButton.setPosition(left,height-31);playWorldButton.setWidth(play);playWorldButton.setHeight(22);
            for(int i=0;i<other.size();i++){var b=other.get(i);b.setPosition(left+play+6+i*(rest+6),height-31);b.setWidth(Math.max(32,rest));b.setHeight(22);}
        }
    }
}
