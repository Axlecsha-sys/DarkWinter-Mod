// Local shelter is directional: a wall upwind matters more than an open-sided roof.
package com.dark.winter.weather;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Local shelter is directional: a wall upwind matters more than an open-sided roof. */
public final class WindExposure {
    private WindExposure() { }

    public static float exposure(Entity entity, ClimateSample climate) {
        return exposure(entity.level(), entity.position().add(0, entity.getBbHeight() * .65, 0),
                new Vec3(climate.windX(), 0, climate.windZ()), entity);
    }

    public static float exposure(Level level, Vec3 point, Vec3 flow, Entity context) {
        if (!level.hasChunkAt(BlockPos.containing(point)) || !level.getFluidState(BlockPos.containing(point)).isEmpty()) return 0;
        double length = flow.horizontalDistance();
        if (length < 1.0e-5) return 0;
        Vec3 direction = flow.scale(1.0 / length);
        Vec3 side = new Vec3(-direction.z, 0, direction.x).scale(.35);
        float open = 0;
        for (int lane = -1; lane <= 1; lane++) {
            Vec3 start = point.add(side.scale(lane));
            Vec3 end = start.subtract(direction.scale(12));
            // Never request chunks for a weather query at the edge of loaded terrain.
            if (!level.hasChunkAt(BlockPos.containing(end))) continue;
            var hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context));
            if (hit.getType() == HitResult.Type.MISS) open += 1;
            else open += (float)Math.clamp((start.distanceTo(hit.getLocation()) - 1.5) / 14, 0, .7);
        }
        BlockPos pos = BlockPos.containing(point);
        boolean covered = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) > point.y + 1;
        return Math.clamp(open / 3 * (covered ? .55f : 1f), 0, 1);
    }
}
