package com.dark.winter.mixin.placement;

import com.dark.winter.block.FenceGateGeometry;
import com.dark.winter.placement.AnchorRuntime;
import com.dark.winter.placement.SurfaceGeometry;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class AnchorPacketMixin {
    @Shadow public ServerPlayer player;

    @ModifyExpressionValue(method = "handleUseItemOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;subtract(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"), require = 1)
    private Vec3 darkwinter$physicalHitBounds(Vec3 nativeDistance, ServerboundUseItemOnPacket packet) {
        ServerLevel level = player.level();
        var hit = packet.hitResult();
        BlockPos pos = hit.getBlockPos();
        if (!AnchorRuntime.anchored(level, pos)) return nativeDistance;

        Vec3 logicalPoint = AnchorRuntime.logicalPoint(level, pos, hit.getLocation());
        Vec3 distance = logicalPoint.subtract(Vec3.atCenterOf(pos));
        if (Math.abs(distance.x) <= 1 && Math.abs(distance.y) <= 1 && Math.abs(distance.z) <= 1) return distance;

        // An overhanging part can legitimately extend beyond the vanilla hit envelope.
        Vec3 localPoint = logicalPoint.subtract(Vec3.atLowerCornerOf(pos));
        if (SurfaceGeometry.bounds(level, pos, level.getBlockState(pos)).inflate(1e-4).contains(localPoint)) return Vec3.ZERO;
        var moving = FenceGateGeometry.movingShape(level, pos);
        if (moving != null) {
            for (var box : moving.toAabbs()) {
                if (box.inflate(1e-4).contains(hit.getLocation())) return Vec3.ZERO;
            }
        }
        return distance;
    }
}
