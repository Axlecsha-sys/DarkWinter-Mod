// Filters mouse look once per frame, with vanilla's shared frame interval for both axes.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.motion.GaitRuntime;
import com.dark.winter.client.motion.LookWeight;
import com.dark.winter.client.motion.MotionConfig;
import com.dark.winter.client.motion.MotionSettings;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Filters mouse look once per frame, with vanilla's shared frame interval for both axes. */
@Mixin(MouseHandler.class)
public abstract class WeightedLookMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow public abstract boolean isMouseGrabbed();

    @Unique private final LookWeight darkwinter$yawWeight = new LookWeight();
    @Unique private final LookWeight darkwinter$pitchWeight = new LookWeight();
    @Unique private Object darkwinter$player;
    @Unique private Object darkwinter$world;
    // Entity.turn converts its mouse units into angles using 0.15.
    @Unique private static final double DARKWINTER_DEGREES_PER_UNIT = 0.15;

    @Inject(method = "handleAccumulatedMovement()V", at = @At("HEAD"))
    private void darkwinter$resetInactiveLook(CallbackInfo ci) {
        if (darkwinter$player != minecraft.player || darkwinter$world != minecraft.level
                || !minecraft.isWindowActive() || !isMouseGrabbed()
                || minecraft.gui.screen() != null || minecraft.isPaused()) {
            darkwinter$yawWeight.reset();
            darkwinter$pitchWeight.reset();
            darkwinter$player = minecraft.player;
            darkwinter$world = minecraft.level;
        }
    }

    @ModifyArgs(method = "turnPlayer(D)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"), require = 1)
    private void darkwinter$weightedLook(Args args, double frameSeconds) {
        MotionConfig.poll();
        MotionSettings settings = MotionConfig.get();
        boolean active = settings.enabled() && settings.camera() && settings.lookControl()
                && minecraft.level != null && minecraft.getCameraEntity() == minecraft.player
                && minecraft.isWindowActive() && isMouseGrabbed()
                && minecraft.gui.screen() == null && !minecraft.isPaused()
                && minecraft.options.getCameraType() == CameraType.THIRD_PERSON_BACK
                && GaitRuntime.posture(minecraft.player);

        double yaw = args.get(0);
        double pitch = args.get(1);
        args.set(0, darkwinter$yawWeight.update(yaw * DARKWINTER_DEGREES_PER_UNIT,
                frameSeconds, active, settings.maxYawSpeed(), settings.lookResponseSeconds())
                / DARKWINTER_DEGREES_PER_UNIT);
        args.set(1, darkwinter$pitchWeight.update(pitch * DARKWINTER_DEGREES_PER_UNIT,
                frameSeconds, active, settings.maxPitchSpeed(), settings.lookResponseSeconds())
                / DARKWINTER_DEGREES_PER_UNIT);
    }
}
