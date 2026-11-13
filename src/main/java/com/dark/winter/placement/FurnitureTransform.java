package com.dark.winter.placement;

import java.util.ArrayList;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Horizontal rotation about the physical origin of a furniture assembly. */
public final class FurnitureTransform {
    private record Footprint(Vec3 center, double halfX, double halfZ, double minY, double maxY,
                             double cos, double sin) {
        static Footprint of(AABB box, Vec3 pivot, double yaw) {
            return new Footprint(point(box.getCenter(), pivot, yaw), box.getXsize() / 2, box.getZsize() / 2,
                    box.minY, box.maxY, Math.cos(yaw), Math.sin(yaw));
        }

        double radius(double x, double z) {
            return halfX * Math.abs(x * cos + z * sin) + halfZ * Math.abs(-x * sin + z * cos);
        }
    }

    private FurnitureTransform() {}

    public static Vec3 point(Vec3 value, Vec3 pivot, double yaw) {
        if (yaw == 0) return value;
        double x = value.x - pivot.x;
        double z = value.z - pivot.z;
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        return new Vec3(pivot.x + cos * x - sin * z, value.y, pivot.z + sin * x + cos * z);
    }

    public static AABB bounds(AABB box, Vec3 pivot, double yaw) {
        if (yaw == 0) return box;
        Vec3 center = point(box.getCenter(), pivot, yaw);
        double sin = Math.abs(Math.sin(yaw)), cos = Math.abs(Math.cos(yaw));
        double dx = .5 * (cos * box.getXsize() + sin * box.getZsize());
        double dz = .5 * (sin * box.getXsize() + cos * box.getZsize());
        return new AABB(center.x - dx, box.minY, center.z - dz, center.x + dx, box.maxY, center.z + dz);
    }

    /** Exact separating-axis test, used for placement so aligned diagonal edges can touch cleanly. */
    public static boolean intersects(VoxelShape first, Vec3 firstPivot, double firstYaw,
                                     VoxelShape second, Vec3 secondPivot, double secondYaw) {
        if (first.isEmpty() || second.isEmpty()
                || !bounds(first.bounds(), firstPivot, firstYaw).intersects(bounds(second.bounds(), secondPivot, secondYaw))) return false;
        var otherBoxes = second.toAabbs().stream().map(box -> Footprint.of(box, secondPivot, secondYaw)).toList();
        for (AABB box : first.toAabbs()) {
            Footprint a = Footprint.of(box, firstPivot, firstYaw);
            for (Footprint b : otherBoxes) {
                if (intersects(a, b)) return true;
            }
        }
        return false;
    }

    public static boolean intersects(AABB first, Vec3 firstPivot, double firstYaw, AABB second) {
        return intersects(Footprint.of(first, firstPivot, firstYaw), Footprint.of(second, Vec3.ZERO, 0));
    }

    private static boolean intersects(Footprint a, Footprint b) {
        if (a.maxY <= b.minY + 1e-7 || b.maxY <= a.minY + 1e-7) return false;
        Vec3 delta = a.center.subtract(b.center);
        return overlaps(a, b, delta, a.cos, a.sin) && overlaps(a, b, delta, -a.sin, a.cos)
                && overlaps(a, b, delta, b.cos, b.sin) && overlaps(a, b, delta, -b.sin, b.cos);
    }

    private static boolean overlaps(Footprint a, Footprint b, Vec3 delta, double x, double z) {
        return Math.abs(delta.x * x + delta.z * z) < a.radius(x, z) + b.radius(x, z) - 1e-7;
    }

    public static VoxelShape shape(VoxelShape source, Vec3 pivot, double yaw) {
        if (yaw == 0 || source.isEmpty()) return source;
        var boxes = new ArrayList<AABB>();
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        boolean cardinal = Math.abs(sin * cos) < 1e-8;
        // Minecraft collision accepts axis-aligned boxes. Cover the rotated footprint
        // with narrow strips rather than filling its whole bounding rectangle.
        for (AABB box : source.toAabbs()) {
            AABB envelope = bounds(box, pivot, yaw);
            if (cardinal) {
                boxes.add(envelope);
                continue;
            }
            Vec3[] corners = {
                    point(new Vec3(box.minX, 0, box.minZ), pivot, yaw),
                    point(new Vec3(box.minX, 0, box.maxZ), pivot, yaw),
                    point(new Vec3(box.maxX, 0, box.maxZ), pivot, yaw),
                    point(new Vec3(box.maxX, 0, box.minZ), pivot, yaw)
            };
            int strips = Math.clamp((int) Math.ceil(envelope.getZsize() * 22), 1, 44);
            for (int i = 0; i < strips; i++) {
                double z0 = envelope.minZ + envelope.getZsize() * i / strips;
                double z1 = envelope.minZ + envelope.getZsize() * (i + 1) / strips;
                double x0 = Double.POSITIVE_INFINITY, x1 = Double.NEGATIVE_INFINITY;
                for (int edge = 0; edge < corners.length; edge++) {
                    Vec3 a = corners[edge], b = corners[(edge + 1) % corners.length];
                    if (a.z >= z0 && a.z <= z1) {
                        x0 = Math.min(x0, a.x);
                        x1 = Math.max(x1, a.x);
                    }
                    double low = Math.min(a.z, b.z), high = Math.max(a.z, b.z);
                    for (int edgeZ = 0; edgeZ < 2; edgeZ++) {
                        double z = edgeZ == 0 ? z0 : z1;
                        if (z < low || z > high || high - low < 1e-9) continue;
                        double x = a.x + (b.x - a.x) * (z - a.z) / (b.z - a.z);
                        x0 = Math.min(x0, x);
                        x1 = Math.max(x1, x);
                    }
                }
                if (x1 - x0 > 1e-7 && z1 - z0 > 1e-7) boxes.add(new AABB(x0, box.minY, z0, x1, box.maxY, z1));
            }
        }
        return BoxUnionShape.of(boxes);
    }
}
