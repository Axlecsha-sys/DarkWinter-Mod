// Aligns shoulder-camera ray casts with the native interaction target.

package com.dark.winter.client.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;

import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LocalPlayer.class)
public abstract class ShoulderAimMixin {

    @ModifyReturnValue(
            method = "raycastHitResult(FLnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/HitResult;",
            at = @At("RETURN"),
            require = 1
    )
    private HitResult darkwinter$shoulderAim(
            HitResult original,
            float partialTicks,
            Entity cameraEntity
    ) {
        Minecraft client = Minecraft.getInstance();

        if (client.level == null
                || client.player == null
                || client.options.getCameraType()
                != CameraType.THIRD_PERSON_BACK
                || client.getCameraEntity() != client.player) {
            return original;
        }

        Camera camera = client.gameRenderer.mainCamera();

        if (!camera.isInitialized()) {
            return original;
        }

        Vec3 cameraPos = camera.position();
        Vector3fc forward = camera.forwardVector();

        Vec3 direction = new Vec3(
                forward.x(),
                forward.y(),
                forward.z()
        ).normalize();

        Vec3 playerEyes =
                client.player.getEyePosition(partialTicks);

        double range = client.player.blockInteractionRange();

        // Луч из центра камеры.
        Vec3 cameraEnd = cameraPos.add(
                direction.scale(range + 4.0D)
        );

        BlockHitResult cameraHit = client.level.clip(
                new ClipContext(
                        cameraPos,
                        cameraEnd,
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        client.player
                )
        );


        // Максимальная дальность взаимодействия с сущностями
        double entityRange = client.player.entityInteractionRange();

        // Не ищем мобов за стеной
        double searchDistance = range + 4.0D;

        if (cameraHit.getType() == HitResult.Type.BLOCK) {
            searchDistance = Math.min(
                    searchDistance,
                    cameraPos.distanceTo(cameraHit.getLocation())
            );
        }

        Vec3 entityEnd = cameraPos.add(
                direction.scale(searchDistance)
        );

        // Область поиска сущностей вдоль луча камеры
        AABB searchBox = new AABB(
                cameraPos,
                entityEnd
        ).inflate(1.0D);

        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                client.level,
                client.player,
                cameraPos,
                entityEnd,
                searchBox,
                entity -> entity.isPickable()
                        && !entity.isSpectator(),
                0.0F
        );

        if (entityHit != null) {
            Vec3 entityPosition = entityHit.getLocation();

            // Проверяем расстояние от самого игрока
            boolean inRange =
                    playerEyes.distanceTo(entityPosition) <= entityRange;

            // Проверяем, нет ли стены между игроком и мобом
            BlockHitResult obstacle = client.level.clip(
                    new ClipContext(
                            playerEyes,
                            entityPosition,
                            ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE,
                            client.player
                    )
            );

            boolean visible =
                    obstacle.getType() == HitResult.Type.MISS;

            if (inRange && visible) {
                return entityHit;
            }
        }


        if (cameraHit.getType() == HitResult.Type.MISS) {
            // The eye ray can hit beside the shoulder crosshair; keep the camera's miss.
            return cameraHit;
        }

        Vec3 target = cameraHit.getLocation();

        // Проверяем реальную дальность от игрока.
        if (playerEyes.distanceTo(target) > range) {
            return darkwinter$miss(cameraPos, direction, range);
        }

        // Второй луч — от глаз игрока к цели.
        Vec3 end = target.add(direction.scale(0.02D));

        BlockHitResult playerHit = client.level.clip(
                new ClipContext(
                        playerEyes,
                        end,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        client.player
                )
        );

        // Если между игроком и целью другая стена,
        // взаимодействовать нельзя.
        if (playerHit.getType() == HitResult.Type.BLOCK
                && !playerHit.getBlockPos().equals(
                cameraHit.getBlockPos()
        )) {
            return darkwinter$miss(cameraPos, direction, range);
        }

        return cameraHit;
    }

    private HitResult darkwinter$miss(
            Vec3 origin,
            Vec3 direction,
            double range
    ) {
        Vec3 end = origin.add(direction.scale(range));

        return BlockHitResult.miss(
                end,
                Direction.getApproximateNearest(
                        direction.x,
                        direction.y,
                        direction.z
                ),
                BlockPos.containing(end)
        );
    }
}
