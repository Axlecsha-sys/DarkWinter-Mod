// Scoped native placement and physical support checks for paintings and both item-frame types.
package com.dark.winter.placement.hanging;

import com.dark.winter.placement.SurfaceGeometry;
import com.dark.winter.placement.SurfacePlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class HangingPlacement {
    private static final ThreadLocal<UseOnContext> PLACING = new ThreadLocal<>();
    private static final double EPS = 1e-6;
    private static final String SUPPORT = "darkwinter_surface_support";
    private static final String NORMAL = "darkwinter_surface_normal";
    private static final String OFFSET = "darkwinter_surface_offset";
    private HangingPlacement() {}

    public static final class Scope implements AutoCloseable {
        private final UseOnContext previous;
        private boolean closed;
        private Scope(UseOnContext context) { previous = PLACING.get(); PLACING.set(context); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) PLACING.remove(); else PLACING.set(previous);
        }
    }

    public static Scope begin(UseOnContext context) { return new Scope(context); }

    public static boolean validOffset(Vec3 offset) {
        // Vanilla itself only reloads logical hanging positions within 16 blocks of the physical entity.
        return Double.isFinite(offset.x + offset.y + offset.z)
                && Math.abs(offset.x) <= 8 && Math.abs(offset.y) <= 8 && Math.abs(offset.z) <= 8;
    }

    /** Called after native geometry has been rebuilt from the unchanged logical tile position. */
    public static void afterBoundingBox(HangingEntity entity) {
        HangingAnchorAccess anchor = (HangingAnchorAccess) entity;
        AABB original = entity.getBoundingBox();
        UseOnContext context = PLACING.get();
        if (context != null && context.getLevel() == entity.level()
                && entity.getDirection() == context.getClickedFace()
                && entity.getPos().equals(context.getClickedPos().relative(context.getClickedFace()))) {
            Vec3 offset = SurfacePlacement.hangingOffset(context, original);
            if (offset != null && validOffset(offset))
                anchor.darkwinter$setSurfaceAnchor(context.getClickedPos(), context.getClickedFace(), offset);
        }
        if (anchor.darkwinter$surfaceSupport().isEmpty() || anchor.darkwinter$surfaceNormal() != entity.getDirection()) return;
        Vec3 offset = anchor.darkwinter$surfaceOffset();
        if (!validOffset(offset)) return;
        AABB moved = original.move(offset);
        Vec3 center = moved.getCenter();
        entity.setPosRaw(center.x, center.y, center.z);
        entity.setBoundingBox(moved);
    }

    /** Check the entire physical back face, including painting cells beyond the initially clicked owner. */
    public static Boolean supported(HangingEntity entity, HangingAnchorAccess anchor) {
        BlockPos support = anchor.darkwinter$surfaceSupport().orElse(null);
        Direction normal = anchor.darkwinter$surfaceNormal();
        if (support == null || normal != entity.getDirection() || !validOffset(anchor.darkwinter$surfaceOffset())) return false;
        AABB box = entity.getBoundingBox();
        Direction.Axis first = normal.getAxis() == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
        Direction.Axis second = normal.getAxis() == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;
        double minA = box.min(first), maxA = box.max(first), minB = box.min(second), maxB = box.max(second);
        if (!Double.isFinite(minA + maxA + minB + maxB) || maxA - minA > 64 || maxB - minB > 64) return false;
        double plane = normal.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.min(normal.getAxis()) : box.max(normal.getAxis());
        Level level = entity.level();
        for (int a = (int)Math.floor(minA + EPS); a <= (int)Math.floor(maxA - EPS); a++)
            for (int b = (int)Math.floor(minB + EPS); b <= (int)Math.floor(maxB - EPS); b++) {
                double loA = Math.max(minA, a), hiA = Math.min(maxA, a + 1.0);
                double loB = Math.max(minB, b), hiB = Math.min(maxB, b + 1.0);
                for (int ia = 0; ia < 3; ia++) for (int ib = 0; ib < 3; ib++) {
                    double pointA = ia == 0 ? loA + EPS : ia == 2 ? hiA - EPS : (loA + hiA) * .5;
                    double pointB = ib == 0 ? loB + EPS : ib == 2 ? hiB - EPS : (loB + hiB) * .5;
                    double x = normal.getAxis() == Direction.Axis.X ? plane : first == Direction.Axis.X ? pointA : pointB;
                    double y = normal.getAxis() == Direction.Axis.Y ? plane : first == Direction.Axis.Y ? pointA : pointB;
                    double z = normal.getAxis() == Direction.Axis.Z ? plane : first == Direction.Axis.Z ? pointA : pointB;
                    BlockPos physicalOwner = new BlockPos(
                            normal.getAxis() == Direction.Axis.X ? support.getX() : (int)Math.floor(x),
                            normal.getAxis() == Direction.Axis.Y ? support.getY() : (int)Math.floor(y),
                            normal.getAxis() == Direction.Axis.Z ? support.getZ() : (int)Math.floor(z));
                    // Unknown chunks defer an existing entity's check; placement must establish loaded support now.
                    if (!level.hasChunkAt(physicalOwner)) return PLACING.get() == null ? null : Boolean.FALSE;
                    if (!SurfaceGeometry.supports(level, physicalOwner, normal, new Vec3(x, y, z))) return false;
                }
            }
        return true;
    }

    public static void save(HangingAnchorAccess anchor, ValueOutput output) {
        anchor.darkwinter$surfaceSupport().ifPresent(pos -> {
            output.store(SUPPORT, BlockPos.CODEC, pos);
            output.store(NORMAL, Direction.CODEC, anchor.darkwinter$surfaceNormal());
            output.store(OFFSET, Vec3.CODEC, anchor.darkwinter$surfaceOffset());
        });
    }

    public static void load(HangingAnchorAccess anchor, ValueInput input) {
        BlockPos support = input.read(SUPPORT, BlockPos.CODEC).orElse(null);
        Direction normal = input.read(NORMAL, Direction.CODEC).orElse(Direction.SOUTH);
        Vec3 offset = input.read(OFFSET, Vec3.CODEC).orElse(Vec3.ZERO);
        anchor.darkwinter$setSurfaceAnchor(support != null && validOffset(offset) ? support : null, normal,
                validOffset(offset) ? offset : Vec3.ZERO);
    }
}
