package com.dark.winter.placement;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.doubles.DoubleLists;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Builds one coordinate grid for a set of boxes so each insertion avoids merging intermediate shapes. */
public final class BoxUnionShape extends VoxelShape {
    private static final double EPSILON = 1e-7;
    private static final int MAX_GRID_CELLS = 4_000_000;
    private final DoubleList xs;
    private final DoubleList ys;
    private final DoubleList zs;

    private BoxUnionShape(BitSetDiscreteVoxelShape shape, double[] xs, double[] ys, double[] zs) {
        super(shape);
        this.xs = DoubleLists.unmodifiable(DoubleArrayList.wrap(xs));
        this.ys = DoubleLists.unmodifiable(DoubleArrayList.wrap(ys));
        this.zs = DoubleLists.unmodifiable(DoubleArrayList.wrap(zs));
    }

    public static VoxelShape of(List<AABB> boxes) {
        if (boxes.isEmpty()) return Shapes.empty();
        if (boxes.size() == 1) return Shapes.create(boxes.getFirst());
        double[] xs = coordinates(boxes, Direction.Axis.X);
        double[] ys = coordinates(boxes, Direction.Axis.Y);
        double[] zs = coordinates(boxes, Direction.Axis.Z);
        // Very sparse shapes can have a large Cartesian grid even with few filled cells.
        if ((long) (xs.length - 1) * (ys.length - 1) * (zs.length - 1) > MAX_GRID_CELLS) {
            return union(boxes, 0, boxes.size());
        }
        var shape = new BitSetDiscreteVoxelShape(xs.length - 1, ys.length - 1, zs.length - 1);
        for (AABB box : boxes) {
            int minX = coordinateIndex(xs, box.minX), maxX = coordinateIndex(xs, box.maxX);
            int minY = coordinateIndex(ys, box.minY), maxY = coordinateIndex(ys, box.maxY);
            int minZ = coordinateIndex(zs, box.minZ), maxZ = coordinateIndex(zs, box.maxZ);
            for (int x = minX; x < maxX; x++) {
                for (int y = minY; y < maxY; y++) {
                    for (int z = minZ; z < maxZ; z++) shape.fill(x, y, z);
                }
            }
        }
        return new BoxUnionShape(shape, xs, ys, zs);
    }

    private static VoxelShape union(List<AABB> boxes, int first, int end) {
        if (end - first == 1) return Shapes.create(boxes.get(first));
        int middle = (first + end) >>> 1;
        return Shapes.joinUnoptimized(union(boxes, first, middle), union(boxes, middle, end), BooleanOp.OR);
    }

    private static double[] coordinates(List<AABB> boxes, Direction.Axis axis) {
        double[] values = new double[2 * boxes.size()];
        int index = 0;
        for (AABB box : boxes) {
            values[index++] = box.min(axis);
            values[index++] = box.max(axis);
        }
        Arrays.sort(values);
        int unique = 1;
        for (int i = 1; i < values.length; i++) {
            if (values[i] - values[unique - 1] > EPSILON) values[unique++] = values[i];
        }
        return Arrays.copyOf(values, unique);
    }

    private static int coordinateIndex(double[] coordinates, double value) {
        int index = Arrays.binarySearch(coordinates, value);
        // When a near-identical coordinate was coalesced, its representative is on the left.
        return index >= 0 ? index : -index - 2;
    }

    @Override public DoubleList getCoords(Direction.Axis axis) {
        return switch (axis) { case X -> xs; case Y -> ys; case Z -> zs; };
    }
}
