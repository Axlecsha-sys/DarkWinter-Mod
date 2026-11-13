package com.dark.winter.client.mixin.client;

import com.dark.winter.client.TestingAccess;
import com.dark.winter.client.HudStyle;
import com.dark.winter.client.vector.InventoryGlyph;
import com.dark.winter.client.vector.SvgHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SVG geometry in physical pixels: native GUI scale cannot turn the point into a large square. */
@Mixin(Hud.class)
public abstract class HudCrosshairMixin {
    @Inject(method="extractCrosshair",at=@At("HEAD"),cancellable=true)
    private void darkwinter$reticle(GuiGraphicsExtractor g,DeltaTracker ticks,CallbackInfo ci) {
        ci.cancel();var c=Minecraft.getInstance();
        if(c.player==null||c.gui.screen()!=null||c.gui.hud.isHidden()||!TestingAccess.enabled("hud")||c.player.isSpectator()
                ||c.debugEntries.isCurrentlyEnabled(DebugScreenEntries.THREE_DIMENSIONAL_CROSSHAIR))return;
        boolean target=c.hitResult!=null&&c.hitResult.getType()!=HitResult.Type.MISS;
        float physical=1f/Math.max(1,c.getWindow().getGuiScale());
        // GUI dimensions round up; use the framebuffer center to avoid a one-pixel offset.
        g.nextStratum();g.pose().pushMatrix();g.pose().translate(c.getWindow().getWidth()*physical/2f,c.getWindow().getHeight()*physical/2f);g.pose().scale(physical,physical);
        SvgHud.inventoryIcon(g,target?InventoryGlyph.TARGET:InventoryGlyph.DOT,-12,-12,24,HudStyle.INK);
        if(c.options.attackIndicator().get()==AttackIndicatorStatus.CROSSHAIR) {
            float charged=c.player.getAttackStrengthScale(0);
            if(charged<1)SvgHud.arc(g,-6,7,12,charged,HudStyle.alpha(HudStyle.STABLE,.8f),false);
        }
        g.pose().popMatrix();
    }
}
