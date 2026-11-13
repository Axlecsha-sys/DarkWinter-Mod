// Captures native-parsed startup selection before Minecraft can initialize OIT classes.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.render.EarlyTransparency;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameConfig.class)
abstract class BalancedOitGameConfigMixin {
    @Inject(method="<init>",at=@At("TAIL"),require=1,allow=1)
    private void darkwinter$captureTransparencyStartup(CallbackInfo callback) {
        GameConfig config=(GameConfig)(Object)this;
        var forced=config.game==null?null:config.game.forcedGraphicsApi;
        // Short-circuit null so an unforced startup does not initialize the enum here.
        boolean forcedVulkan=forced!=null&&forced==PreferredGraphicsApi.VULKAN;
        var directory=config.location==null?null:config.location.gameDirectory;
        EarlyTransparency.capture(forcedVulkan,directory==null?null:directory.toPath());
    }
}
