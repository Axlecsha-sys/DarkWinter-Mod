// Retains per-layer streaming fog while finite world-height haze owns ordinary environmental extinction.
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

/** Preserve terrain-layer distance fade while ordinary environmental extinction follows world height. */
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
        // Finite world-height extinction replaces only the ordinary environmental component.
        // Keep the engine's streaming range for each opaque/translucent layer before OIT.
        // Its selected terrain vertex variant measures XZ only, so elevation cannot erase
        // nearby ground directly below the camera.
        fog.environmentalStart = fog.environmentalEnd = Float.MAX_VALUE;
    }
}
