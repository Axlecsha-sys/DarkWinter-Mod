package com.dark.winter.interaction;

import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.PanelDoorBlock;
import com.dark.winter.placement.AnchorRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Geometry shared by the server's passage decision and the client's walking animation. */
public record HousePassage(BlockPos door, Direction direction, boolean leaving) implements CustomPacketPayload {
    public static final Type<HousePassage> TYPE = new Type<>(Identifier.fromNamespaceAndPath("darkwinter", "house_passage"));
    public static final StreamCodec<FriendlyByteBuf, HousePassage> CODEC = StreamCodec.of((buffer, passage) -> {
        buffer.writeBlockPos(passage.door);
        buffer.writeEnum(passage.direction);
        buffer.writeBoolean(passage.leaving);
    }, buffer -> new HousePassage(buffer.readBlockPos(), buffer.readEnum(Direction.class), buffer.readBoolean()));

    private static final double START_CLEARANCE = .60;
    private static final double END_CLEARANCE = 1.05;
    private static final double LEAF_EXIT_DISTANCE = 1.20;

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static @Nullable HousePassage plan(Player player, PanelDoorInteraction.Target target) {
        if (player.isPassenger() || player.isInWater() || player.getAbilities().flying || !player.onGround()) return null;
        Level level = player.level();
        Vec3 center = center(level, target);
        Direction facing = target.lower().getValue(FurnitureBlock.FACING);
        Vec3 delta = player.position().subtract(center);
        if (delta.horizontalDistanceSqr() > 3.4 * 3.4 || Math.abs(delta.y) > .7) return null;
        Direction direction = delta.dot(facing.getUnitVec3()) >= 0 ? facing.getOpposite() : facing;
        Vec3 forward = direction.getUnitVec3();
        boolean destinationInside = HouseInterior.contains(level, center.add(forward.scale(END_CLEARANCE)));
        boolean sourceInside = HouseInterior.contains(level, center.subtract(forward.scale(END_CLEARANCE)));
        return destinationInside == sourceInside ? null : new HousePassage(target.origin(), direction, sourceInside);
    }

    public Vec3 approach(Vec3 center) { return center.subtract(direction.getUnitVec3().scale(START_CLEARANCE)); }

    public Vec3 destination(Vec3 center, Direction facing) {
        if (direction == facing) return center.add(direction.getUnitVec3().scale(END_CLEARANCE));
        // Finish on the free side of the leaf when walking in the direction it opens.
        return center.add(direction.getUnitVec3().scale(LEAF_EXIT_DISTANCE))
                .add(facing.getCounterClockWise().getUnitVec3().scale(.14));
    }

    /** The player has left the threshold even if furniture prevents taking the final step. */
    public boolean clearedThreshold(Vec3 center, Vec3 position) {
        Vec3 offset = position.subtract(center);
        Vec3 forward = direction.getUnitVec3();
        double advance = offset.dot(forward);
        return Math.abs(offset.y) <= .85 && advance >= START_CLEARANCE
                && offset.subtract(forward.scale(advance)).horizontalDistanceSqr() < .55 * .55;
    }

    public static Vec3 center(Level level, PanelDoorInteraction.Target target) {
        Direction facing = target.lower().getValue(FurnitureBlock.FACING);
        double faceOffset = .5 - PanelDoorBlock.hinge(Direction.NORTH).z;
        return Vec3.atBottomCenterOf(target.origin()).add(AnchorRuntime.offset(level, target.origin()))
                .add(facing.getUnitVec3().scale(faceOffset));
    }

    public record Aim(PanelDoorInteraction.Target target, Vec3 point) {}

    /** An open door can be targeted through the empty aperture, as well as on its leaf. */
    public static @Nullable Aim openAim(Player player, Vec3 from, Vec3 to) {
        Level level = player.level();
        return BlockGetter.traverseBlocks(from, to, level, (world, cell) -> {
            if (!world.hasChunkAt(cell)) return null;
            Aim aim = openAim(player, cell, from, to);
            if (aim != null) return aim;
            for (BlockPos owner : AnchorRuntime.logicalOwners(world, cell)) {
                if (!owner.equals(cell) && (aim = openAim(player, owner, from, to)) != null) return aim;
            }
            return null;
        }, world -> null);
    }

    private static @Nullable Aim openAim(Player player, BlockPos pos, Vec3 from, Vec3 to) {
        var target = PanelDoorInteraction.target(player.level(), pos);
        if (target == null || !target.open()) return null;
        Vec3 center = center(player.level(), target);
        Vec3 normal = target.lower().getValue(FurnitureBlock.FACING).getUnitVec3();
        Vec3 delta = to.subtract(from);
        double projection = delta.dot(normal);
        if (Math.abs(projection) < 1e-6) return null;
        double time = center.subtract(from).dot(normal) / projection;
        if (time <= 0 || time > 1) return null;
        Vec3 point = from.add(delta.scale(time));
        return visibleAperture(player, target, point) ? new Aim(target, point) : null;
    }

    static boolean visibleAperture(Player player, PanelDoorInteraction.Target target, Vec3 point) {
        if (!target.open() || !Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) return false;
        Vec3 eye = player.getEyePosition(1);
        double range = player.blockInteractionRange();
        if (eye.distanceToSqr(point) > range * range) return false;
        Vec3 relative = point.subtract(center(player.level(), target));
        Vec3 normal = target.lower().getValue(FurnitureBlock.FACING).getUnitVec3();
        if (Math.abs(relative.dot(normal)) > .035 || relative.horizontalDistanceSqr() > .42 * .42
                || relative.y < .12 || relative.y > 1.87) return false;
        var hit = player.level().clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS;
    }
}
