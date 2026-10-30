// Positions the shoulder camera with collision clearance and transition smoothing.

package com.dark.winter.client.mixin.client;

import com.dark.winter.client.ShoulderCameraState;
import com.dark.winter.client.ShoulderCameraMotion;
import com.dark.winter.client.motion.GaitRuntime;

import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraShoulderMixin implements com.dark.winter.client.CameraViewDiagnostics {

    // Положение
    private static final double DISTANCE = 3.0D;
    private static final double HEIGHT = 0.3D;
    private static final double RADIUS = 0.18D;
    private static final double PADDING = 0.12D;

    // Автоматическое первое лицо
    private static final double ENTER_FIRST = 0.65D;
    private static final double EXIT_FIRST = 1.25D;
    private static final long EXIT_DELAY_NS = 250_000_000L;

    private static final double FIRST_PERSON_APPROACH_SPEED = 1.8D;
    private static final double FIRST_PERSON_SWITCH_DISTANCE = 0.85D;

    // Плавное возвращение
    private static final double RETURN_SPEED = 3.5D;

    private static final double APPROACH_DISTANCE = 1.6D;

    private static final double APPROACH_SPEED = 9.0D;

    private double currentFraction = 1.0D;
    private long lastFrameNanos = 0L;

    private boolean automaticFirstPerson = false;
    private long clearanceSinceNanos = 0L;

    private boolean darkwinter$wasFirstPerson = true;

    @Unique private ShoulderCameraMotion darkwinter$motion;

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Override
    public String darkwinter$describeShoulderView() {
        return "manual=" + ShoulderCameraState.isManualToFirst() + ",automatic=" + automaticFirstPerson
                + ",fraction=" + currentFraction + ",wasFirst=" + darkwinter$wasFirstPerson;
    }

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void darkwinter$updateCamera(
            float partialTicks,
            CallbackInfo ci
    ) {
        Minecraft client = Minecraft.getInstance();

        if (!com.dark.winter.client.TestingAccess.enabled("camera") || client.level == null || client.player == null
                || client.getCameraEntity() != client.player) {
            darkwinter$resetShoulderCamera();
            return;
        }

        CameraType type = client.options.getCameraType();

        if (type == CameraType.THIRD_PERSON_FRONT) {
            darkwinter$resetShoulderCamera();
            return;
        }

        if (type == CameraType.FIRST_PERSON
                && !automaticFirstPerson) {

            darkwinter$wasFirstPerson = true;
            ShoulderCameraState.pauseSmoothing();
            if (darkwinter$motion != null) darkwinter$motion.reset();
            return;
        }

        Camera camera = (Camera) (Object) this;

        Vector3fc forward = camera.forwardVector();
        Vector3fc up = camera.upVector();
        Vector3fc left = camera.leftVector();

        Vec3 forwardDir = new Vec3(
                forward.x(), forward.y(), forward.z()
        );

        Vec3 upDir = new Vec3(
                up.x(), up.y(), up.z()
        );

        Vec3 rightDir = new Vec3(
                -left.x(), -left.y(), -left.z()
        );

        float side = ShoulderCameraState.updateSideOffset();

        Vec3 eye = client.player.getEyePosition(partialTicks);

        if (darkwinter$motion == null) darkwinter$motion = new ShoulderCameraMotion();
        boolean motionActive = type == CameraType.THIRD_PERSON_BACK
                && !automaticFirstPerson
                && !ShoulderCameraState.isManualToFirst()
                && GaitRuntime.posture(client.player);

        Vec3 desired = darkwinter$motion.apply(
                client, eye, forwardDir, upDir, rightDir,
                side, HEIGHT, DISTANCE, partialTicks, motionActive);

        // Вычисляем доступное пространство
        double targetFraction = calculateClearance(
                client,
                eye,
                desired,
                upDir,
                rightDir
        );

        double availableDistance = DISTANCE * targetFraction;
        long now = System.nanoTime();


        if (ShoulderCameraState.isManualToFirst()) {

            // Время, прошедшее с предыдущего кадра
            double dt = lastFrameNanos == 0L
                    ? 0.0D
                    : Math.min(
                    (now - lastFrameNanos) / 1_000_000_000.0D,
                    0.1D
            );

            lastFrameNanos = now;

            // Постепенно сокращаем расстояние
            currentFraction = Math.max(
                    0.0D,
                    currentFraction - FIRST_PERSON_APPROACH_SPEED * dt
            );

            // Не позволяем анимации проходить через препятствия
            currentFraction = Math.min(
                    currentFraction,
                    targetFraction
            );

            double distance = DISTANCE * currentFraction;

            // Завершаем переход
            if (distance <= FIRST_PERSON_SWITCH_DISTANCE) {

                ShoulderCameraState.finishManualToFirst();

                client.options.setCameraType(
                        CameraType.FIRST_PERSON
                );

                lastFrameNanos = 0L;
                return;
            }

            // Обновляем положение камеры во время анимации
            Vec3 transitionPosition = eye.add(
                    desired.subtract(eye).scale(currentFraction)
            );

            this.setPosition(transitionPosition);
            return;
        }


        // Камера уже автоматически перешла в первое лицо
        if (automaticFirstPerson) {
            if (type != CameraType.FIRST_PERSON) {
                automaticFirstPerson = false;
                clearanceSinceNanos = 0L;
                return;
            }

            if (availableDistance >= EXIT_FIRST) {
                if (clearanceSinceNanos == 0L) {
                    clearanceSinceNanos = now;
                }

                if (now - clearanceSinceNanos >= EXIT_DELAY_NS) {
                    automaticFirstPerson = false;
                    clearanceSinceNanos = 0L;

                    currentFraction = Math.min(
                            targetFraction,
                            1.05D / DISTANCE
                    );

                    lastFrameNanos = now;

                    client.options.setCameraType(
                            CameraType.THIRD_PERSON_BACK
                    );
                }
            } else {
                clearanceSinceNanos = 0L;
            }

            return;
        }


        // Плавный переход из первого лица в третье
        if (darkwinter$wasFirstPerson) {

            currentFraction = Math.min(
                    targetFraction,
                    1.05D / DISTANCE
            );

            lastFrameNanos = now;
            darkwinter$wasFirstPerson = false;
        }

        // Вычисляем время между кадрами
        double dt = lastFrameNanos == 0L
                ? 0.0D
                : Math.min(
                (now - lastFrameNanos) / 1_000_000_000.0D,
                0.1D
        );

        lastFrameNanos = now;


        double targetDistance = DISTANCE * targetFraction;

        if (targetDistance < APPROACH_DISTANCE) {

            // Плавно приближаемся к препятствию
            double alpha = 1.0D -
                    Math.exp(-APPROACH_SPEED * dt);

            currentFraction +=
                    (targetFraction - currentFraction) * alpha;

            // Но не допускаем прохождения сквозь стену
            currentFraction = Math.min(
                    currentFraction,
                    targetFraction
            );

        } else {

            // Плавно возвращаем обычную дистанцию
            double alpha = 1.0D -
                    Math.exp(-RETURN_SPEED * dt);

            currentFraction +=
                    (targetFraction - currentFraction) * alpha;
        }


        currentFraction = Math.min(
                currentFraction,
                targetFraction
        );

        if (targetDistance < ENTER_FIRST) {
            automaticFirstPerson = true;
            clearanceSinceNanos = 0L;

            client.options.setCameraType(
                    CameraType.FIRST_PERSON
            );

            currentFraction = targetFraction;
            lastFrameNanos = 0L;
            return;
        }

        Vec3 finalPosition = eye.add(
                desired.subtract(eye).scale(currentFraction)
        );

        this.setPosition(finalPosition);
    }

    private double calculateClearance(
            Minecraft client,
            Vec3 eye,
            Vec3 desired,
            Vec3 up,
            Vec3 right
    ) {
        Vec3[] offsets = {
                Vec3.ZERO,
                up.scale(RADIUS),
                up.scale(-RADIUS),
                right.scale(RADIUS),
                right.scale(-RADIUS),
                up.add(right).normalize().scale(RADIUS),
                up.subtract(right).normalize().scale(RADIUS),
                right.subtract(up).normalize().scale(RADIUS),
                up.add(right).normalize().scale(-RADIUS)
        };

        double fraction = 1.0D;

        for (Vec3 offset : offsets) {
            Vec3 target = desired.add(offset);

            BlockHitResult hit = client.level.clip(
                    new ClipContext(
                            eye,
                            target,
                            ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE,
                            client.player
                    )
            );

            if (hit.getType() == HitResult.Type.MISS) {
                continue;
            }

            double length = target.distanceTo(eye);

            if (length < 1.0E-6D) {
                continue;
            }

            double hitDistance =
                    hit.getLocation().distanceTo(eye);

            double allowed = Math.max(
                    0.0D,
                    (hitDistance - PADDING) / length
            );

            fraction = Math.min(fraction, allowed);
        }

        return fraction;
    }

    private void darkwinter$resetShoulderCamera() {
        if (darkwinter$motion != null) darkwinter$motion.reset();
        currentFraction = 1.0D;
        lastFrameNanos = 0L;
        automaticFirstPerson = false;
        clearanceSinceNanos = 0L;

        ShoulderCameraState.pauseSmoothing();

        darkwinter$wasFirstPerson = true;
    }
}
