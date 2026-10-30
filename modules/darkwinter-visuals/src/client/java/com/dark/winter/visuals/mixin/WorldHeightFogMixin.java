// Applies world-height atmospheric fog to ordinary Overworld rendering.
package com.dark.winter.visuals.mixin;

import com.dark.winter.visuals.VisualRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replace only ordinary Overworld distance fog with the world-height atmosphere. */
@Mixin(FogRenderer.class)
public abstract class WorldHeightFogMixin {
    @Inject(method = "setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/client/renderer/fog/FogData;",
            at = @At("RETURN"), require = 1)
    private void darkwinter$worldHeightAtmosphere(Camera camera, int renderDistanceInChunks,
            DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level,
            CallbackInfoReturnable<FogData> cir) {
        if (!Level.OVERWORLD.equals(level.dimension()) || !VisualRenderer.controlsAtmosphere(level)
                || camera.isPanoramicMode() || camera.getFluidInCamera() != FogType.NONE) return;

        // Preserve the actual FogRenderer environment priority: lava/powder snow,
        // blindness/darkness and water all take precedence over ordinary atmosphere.
        if (camera.entity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) return;
        if (Minecraft.getInstance().gui.hud.getBossOverlay().shouldCreateWorldFog()) return;

        FogData fog = cir.getReturnValue();
        // Vanilla's 'cylindrical' distance is max(length(XZ), abs(Y)). It erases the
        // ground under a climbing camera before post-processing can recover it.
        // Use the same sentinel as vanilla's empty Fog UBO; retain sky/cloud colour
        // and distances so celestial rendering keeps its existing behavior.
        fog.environmentalStart = fog.environmentalEnd = Float.MAX_VALUE;
        fog.renderDistanceStart = fog.renderDistanceEnd = Float.MAX_VALUE;
    }
}
