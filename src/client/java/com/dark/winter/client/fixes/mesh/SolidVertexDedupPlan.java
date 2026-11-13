// Plans exact whole-record vertex reuse and optional removal of collapsed solid triangles.
package com.dark.winter.client.fixes.mesh;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Pure bounded planning; the source buffer is untouched until compactPrefix is explicitly committed. */
final class SolidVertexDedupPlan {
    static final int STRIDE = 28;
    static final int MAX_VERTICES = 262_144;
    private static final ThreadLocal<int[]> HASH_TABLE = new ThreadLocal<>();
    final int originalVertices, uniqueVertices, paddedVertices, indexCount, indexTypeBytes;
    private final int[] remap;
    private final boolean filterDegenerates;

    private SolidVertexDedupPlan(int originalVertices, int uniqueVertices, int[] remap, boolean filterDegenerates) {
        this.originalVertices = originalVertices;
        this.uniqueVertices = uniqueVertices;
        this.paddedVertices = (uniqueVertices + 3) & ~3;
        this.filterDegenerates = filterDegenerates;
        this.indexCount = filterDegenerates ? survivingIndexCount(remap) : originalVertices / 4 * 6;
        this.indexTypeBytes = paddedVertices < 65536 ? Short.BYTES : Integer.BYTES;
        this.remap = remap;
    }

    static SolidVertexDedupPlan build(ByteBuffer source, int vertices) {
        return build(source, vertices, false);
    }

    static SolidVertexDedupPlan build(ByteBuffer source, int vertices, boolean filterDegenerates) {
        if (vertices < 4 || vertices > MAX_VERTICES || (vertices & 3) != 0
                || source.remaining() < vertices * STRIDE) throw new IllegalArgumentException("Unsupported vertex range");
        ByteBuffer view = source.duplicate().order(source.order());
        int base = view.position(), slots = 8;
        while (slots < vertices * 2) slots <<= 1;
        // Plans retain only their own remap; this bounded per-thread hash scratch is never published.
        int[] table = HASH_TABLE.get();
        if (table == null || table.length < slots) {
            table = new int[slots];
            HASH_TABLE.set(table);
        }
        Arrays.fill(table, 0, slots, 0);
        int[] remap = new int[vertices];
        int unique = 0, probes = 0, probeBudget = Math.max(16_384, vertices * 16);
        for (int vertex = 0; vertex < vertices; vertex++) {
            int at = base + vertex * STRIDE;
            int hash = 0x6d2b79f5;
            for (int word = 0; word < STRIDE; word += 4)
                hash = Integer.rotateLeft(hash ^ view.getInt(at + word), 11) * 0x9e3779b1;
            int slot = (hash ^ (hash >>> 16)) & (slots - 1);
            while (true) {
                if (++probes > probeBudget) throw new ProbeBudgetExceeded();
                int entry = table[slot];
                if (entry == 0) {
                    table[slot] = vertex + 1;
                    remap[vertex] = unique++;
                    break;
                }
                int first = entry - 1;
                if (sameRecord(view, at, base + first * STRIDE)) {
                    remap[vertex] = remap[first];
                    break;
                }
                slot = (slot + 1) & (slots - 1);
            }
        }
        return new SolidVertexDedupPlan(vertices, unique, remap, filterDegenerates);
    }

    // A 25% padded-vertex saving also pays for the new uint32 IBO in initial upload bytes.
    boolean worthwhile() { return (long) paddedVertices * 4 <= (long) originalVertices * 3; }
    int vertexBytes() { return paddedVertices * STRIDE; }
    int indexBytes() { return (indexCount * indexTypeBytes + 3) & ~3; }

    void writeIndices(ByteBuffer target) {
        if (target.isReadOnly() || target.remaining() != indexBytes())
            throw new IllegalArgumentException("Exact writable index range required");
        int offset = target.position();
        for (int q = 0; q < originalVertices; q += 4) {
            offset = writeTriangle(target, offset, remap[q], remap[q + 1], remap[q + 2]);
            offset = writeTriangle(target, offset, remap[q + 2], remap[q + 3], remap[q]);
        }
        // Native staging appends byte ranges without alignment; keep the original 4-byte invariant.
        for (int end = target.position() + indexBytes(); offset < end; offset++) target.put(offset, (byte) 0);
    }

    private int writeTriangle(ByteBuffer target, int offset, int a, int b, int c) {
        if (filterDegenerates && collapsed(a, b, c)) return offset;
        if (indexTypeBytes == Short.BYTES) {
            target.putShort(offset, (short) a);
            target.putShort(offset + 2, (short) b);
            target.putShort(offset + 4, (short) c);
            return offset + 6;
        }
        target.putInt(offset, a);
        target.putInt(offset + 4, b);
        target.putInt(offset + 8, c);
        return offset + 12;
    }

    private static int survivingIndexCount(int[] remap) {
        int count = 0;
        for (int q = 0; q < remap.length; q += 4) {
            if (!collapsed(remap[q], remap[q + 1], remap[q + 2])) count += 3;
            if (!collapsed(remap[q + 2], remap[q + 3], remap[q])) count += 3;
        }
        return count;
    }

    private static boolean collapsed(int a, int b, int c) { return a == b || b == c || c == a; }

    /** Input and all output allocations must be validated before calling this non-fallback commit. */
    void compactPrefix(ByteBuffer target) {
        if (target.isReadOnly() || target.remaining() < originalVertices * STRIDE)
            throw new IllegalArgumentException("Writable original vertex range required");
        int base = target.position(), next = 0;
        for (int original = 0; original < originalVertices; original++) {
            if (remap[original] != next) continue;
            copyRecord(target, base + original * STRIDE, base + next * STRIDE);
            next++;
        }
        // Padding is initialized but never referenced by the explicit original-order indices.
        for (int padding = uniqueVertices; padding < paddedVertices; padding++)
            copyRecord(target, base + (uniqueVertices - 1) * STRIDE, base + padding * STRIDE);
    }

    private static boolean sameRecord(ByteBuffer source, int a, int b) {
        for (int word = 0; word < STRIDE; word += 4)
            if (source.getInt(a + word) != source.getInt(b + word)) return false;
        return true;
    }

    private static void copyRecord(ByteBuffer target, int from, int to) {
        if (from == to) return;
        // First occurrences are visited in source order, so every destination precedes its source.
        for (int word = 0; word < STRIDE; word += 4) target.putInt(to + word, target.getInt(from + word));
    }

    /** A bounded read-only decline, not an error discovered after modifying the original mesh. */
    static final class ProbeBudgetExceeded extends RuntimeException {
        ProbeBudgetExceeded() { super("Solid vertex dedup probe budget exceeded", null, false, false); }
    }
}
