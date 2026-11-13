package com.dark.winter.mixin.placement;

import com.dark.winter.block.FenceGateGeometry;
import com.dark.winter.placement.AnchorRuntime;
import com.dark.winter.placement.SurfaceGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class AnchorReachMixin {
    @Inject(method = "isWithinBlockInteractionRange", at = @At("HEAD"), cancellable = true)
    private void darkwinter$physicalReach(BlockPos pos, double buffer, CallbackInfoReturnable<Boolean> cir) {
        Player player = (Player) (Object) this;
        var level = player.level();
        if (!AnchorRuntime.anchored(level, pos)) return;

        Vec3 eye = player.getEyePosition();
        Vec3 logicalEye = AnchorRuntime.logicalPoint(level, pos, eye);
        double range = player.blockInteractionRange() + buffer;
        double reachSquared = range * range;
        if (AnchorRuntime.rotation(level, pos) == 0 && new AABB(pos).distanceToSqr(logicalEye) < reachSquared) {
            cir.setReturnValue(true);
            return;
        }

        Vec3 localEye = logicalEye.subtract(Vec3.atLowerCornerOf(pos));
        AABB bounds = SurfaceGeometry.bounds(level, pos, level.getBlockState(pos));
        if (bounds.distanceToSqr(localEye) < reachSquared) {
            cir.setReturnValue(true);
            return;
        }
        var moving = FenceGateGeometry.movingShape(level, pos);
        cir.setReturnValue(moving != null && moving.closestPointTo(eye)
                .filter(point -> point.distanceToSqr(eye) < reachSquared).isPresent());
    }
}
