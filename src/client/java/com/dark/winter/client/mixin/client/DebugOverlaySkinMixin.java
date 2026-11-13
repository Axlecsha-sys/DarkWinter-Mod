package com.dark.winter.client.mixin.client;

import com.dark.winter.client.DebugOverlayStyle;
import com.dark.winter.client.DebugOverlayVisibility;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native data collection, permissions, shortcuts, profiler and chart renderers still execute. */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugOverlaySkinMixin {
    @Shadow public abstract boolean showFpsCharts();
    @Shadow public abstract boolean showNetworkCharts();
    @Shadow public abstract boolean showProfilerChart();
    @Shadow public abstract boolean showLightmapTexture();

    @Inject(method="extractRenderState",at=@At("HEAD"),cancellable=true)
    private void darkwinter$menus(GuiGraphicsExtractor g,CallbackInfo ci) {
        if(!DebugOverlayVisibility.allowsDebugOverlay())ci.cancel();
    }

    @Inject(method="extractLines",at=@At("HEAD"),cancellable=true)
    private void darkwinter$panels(GuiGraphicsExtractor g,List<String> lines,boolean left,int width,CallbackInfo ci) {
        int reserve=showProfilerChart()?Math.min(160,g.guiHeight()/2):showFpsCharts()||showNetworkCharts()?78:showLightmapTexture()?76:12;
        DebugOverlayStyle.extract(g,lines,left,width,reserve);ci.cancel();
    }
}
