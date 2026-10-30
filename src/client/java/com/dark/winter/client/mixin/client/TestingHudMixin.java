// Hide game instruments only; Fabric's independent laboratory HUD still extracts normally.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.TestingAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hide game instruments only; Fabric's independent laboratory HUD still extracts normally. */
@Mixin(Hud.class)
public abstract class TestingHudMixin {
    @Shadow private void extractItemHotbar(net.minecraft.client.gui.GuiGraphicsExtractor graphics,net.minecraft.client.DeltaTracker ticks){
        throw new AssertionError("Mixin shadow");
    }
    /** Keep the native inventory slots while the optional health/experience decorations stay disabled. */
    @Inject(method="extractHotbarAndDecorations",at=@At("HEAD"),cancellable=true)
    private void darkwinter$testingInventory(net.minecraft.client.gui.GuiGraphicsExtractor graphics,net.minecraft.client.DeltaTracker ticks,CallbackInfo ci){
        if(com.dark.winter.client.TestingWorlds.isTesting()&&!TestingAccess.enabled("hud")){
            extractItemHotbar(graphics,ticks);
            ci.cancel();
        }
    }
    /** A detached drone still uses the author's inventory, not the camera proxy's nonexistent slots. */
    @Inject(method="getCameraPlayer",at=@At("HEAD"),cancellable=true)
    private void darkwinter$testingCameraInventory(CallbackInfoReturnable<net.minecraft.world.entity.player.Player> cir){
        if(com.dark.winter.client.TestingWorlds.isTesting()&&com.dark.winter.client.TestingDrone.active())
            cir.setReturnValue(Minecraft.getInstance().player);
    }
    @Inject(method={"extractCrosshair","extractEffects","extractPlayerHealth",
            "extractVehicleHealth","extractCameraOverlays","extractOverlayMessage","extractTitle",
            "extractScoreboardSidebar","extractTabList","extractSelectedItemName"},at=@At("HEAD"),cancellable=true)
    private void darkwinter$testingHud(CallbackInfo ci) {
        if(!TestingAccess.enabled("hud"))ci.cancel();
    }
    @Inject(method="extractChat",at=@At("HEAD"),cancellable=true)
    private void darkwinter$testingChat(CallbackInfo ci) {
        if(!TestingAccess.enabled("hud") && !(Minecraft.getInstance().gui.screen() instanceof ChatScreen))ci.cancel();
    }
}
