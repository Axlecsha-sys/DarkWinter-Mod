// Maps the existing snow shoulder exactly onto a half-height native grass slab.
package com.dark.winter.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Pure local geometry; only the sixteen global-corner masks own cached shapes. */
public final class TestSnowSlabGeometry {
    public static final float BASE = .5f, CAP = .125f;
    private static final int[][] CORNERS = {{0,1,3,4},{1,2,4,5},{4,5,7,8},{3,4,6,7}};
    private static final AtomicReferenceArray<List<TestSnowTerrainGeometry.Piece>> SHAPES = new AtomicReferenceArray<>(16);

    private TestSnowSlabGeometry() {}

    /** Column values: 0 is matching cap, -1 is lower snow, every other value protects a contact. */
    public static int cornerMask(int[] columns) {
        if (columns.length != 9) throw new IllegalArgumentException("Nine slab columns required");
        if (columns[4] != 0) return 0;
        int mask = 0;
        for (int corner = 0; corner < 4; corner++) {
            boolean known = true, lower = false;
            for (int index : CORNERS[corner]) {
                known &= columns[index] == 0 || columns[index] == -1;
                lower |= columns[index] == -1;
            }
            if (known && lower) mask |= 1 << (corner + 4);
        }
        return mask;
    }

    public static List<TestSnowTerrainGeometry.Piece> shape(int mask) {
        if ((mask & ~255) != 0) throw new IllegalArgumentException("Slab shoulder mask");
        int key = (mask & 0xF0) >>> 4;
        var known = SHAPES.get(key);
        if (known != null) return known;
        var terrain = TestSnowTerrainGeometry.shape(1, mask & 0xF0);
        var pieces = new ArrayList<TestSnowTerrainGeometry.Piece>();
        for (var piece : terrain.snowOwner()) pieces.add(move(piece, true));
        for (var piece : terrain.soilOwner()) pieces.add(move(piece, false));
        var made = List.copyOf(pieces);
        return SHAPES.compareAndSet(key, null, made) ? made : SHAPES.get(key);
    }

    private static TestSnowTerrainGeometry.Piece move(TestSnowTerrainGeometry.Piece source, boolean upperOwner) {
        var vertices = new ArrayList<TestSnowTerrainGeometry.Vertex>(source.vertices().size());
        for (var vertex : source.vertices()) {
            // Snow above/below the original y=0 ownership split meets exactly at slab y=.5.
            float y = source.snow() ? vertex.y() + (upperOwner ? BASE : -BASE)
                    : vertex.y() == 0 ? 0 : vertex.y() - BASE;
            if (!Float.isFinite(y) || y < 0 || y > BASE + CAP)
                throw new IllegalStateException("Slab shoulder escaped its owner cell: " + y);
            vertices.add(new TestSnowTerrainGeometry.Vertex(vertex.x(), y, vertex.z()));
        }
        return new TestSnowTerrainGeometry.Piece(source.face(), List.copyOf(vertices), source.snow());
    }
}
