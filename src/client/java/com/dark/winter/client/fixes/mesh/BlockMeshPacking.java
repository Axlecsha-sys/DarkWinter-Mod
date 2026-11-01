// Encodes BLOCK vertex packets and releases idle per-worker Java scratch on lifecycle changes.
package com.dark.winter.client.fixes.mesh;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/** Pure packet codec, shared by the game and standalone Java/C++ verification.
 * Templates: four (x,y,z,u,v) float bit patterns, 20 words per quad.
 * Instances: template index, x/y/z offset bits, four ARGB colors, four final light words.
 * Output: Minecraft 26.3 DefaultVertexFormat.BLOCK: xyz / RGBA / uv / light, 28 bytes.
 * Lighting, tint, model selection and visibility have ALREADY been evaluated by Minecraft.
 */
public final class BlockMeshPacking {
    /** Startup-only diagnostic timing; detailed clocks are excluded from the normal packing path. */
    public static final boolean DETAILED_TIMING = Boolean.getBoolean("darkwinter.mesh.detailedTiming");
    public static final long UNAVAILABLE_NANOS = -1L;
    public static final int TEMPLATE_WORDS = 20;
    public static final int RECORD_WORDS = 12;
    public static final int VERTEX_BYTES = 28;
    public static final int QUAD_BYTES = 112;
    public static final int MAX_QUADS = 1024;
    public static final int MAX_TEMPLATES = 1024;
    public static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    // Weak registration never keeps a terminated worker or its arrays alive.
    private static final java.util.Set<Words> WORD_CACHES = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final ThreadLocal<Words> JAVA_WORDS = ThreadLocal.withInitial(Words::new);
    private static final class Words { volatile int[] value; }

    private static int[] words() {
        Words holder = JAVA_WORDS.get();
        int[] value = holder.value;
        if (value == null) {
            synchronized (WORD_CACHES) {
                value = holder.value;
                if (value == null) {
                    value = new int[MAX_QUADS * 28];
                    holder.value = value;
                    WORD_CACHES.add(holder);
                }
            }
        }
        return value;
    }

    /** In-flight writes keep their local array; clearing a cached reference never mutates it. */
    public static void releaseScratchCaches() {
        synchronized (WORD_CACHES) {
            for (Words holder : WORD_CACHES) holder.value = null;
            WORD_CACHES.clear();
        }
    }

    static void releaseThreadScratch() {
        Words holder = JAVA_WORDS.get();
        synchronized (WORD_CACHES) {
            holder.value = null;
            WORD_CACHES.remove(holder);
        }
        JAVA_WORDS.remove();
    }

    public static java.util.Map<String, Long> memorySnapshot() {
        synchronized (WORD_CACHES) {
            long arrays = 0;
            for (Words holder : WORD_CACHES) if (holder.value != null) arrays++;
            return java.util.Map.of("cachedArrays", arrays, "cachedBytes", arrays * MAX_QUADS * 28L * Integer.BYTES);
        }
    }
    private BlockMeshPacking() { }

    private static void publish(int[] words, int count, MemorySegment output) {
        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            MemorySegment.copy(MemorySegment.ofArray(words), 0, output, 0, (long) count * 4);
        } else {
            for (int i = 0; i < count; i++) output.set(LE_INT, (long) i * 4, words[i]);
        }
    }

    /** Indigo's terminal encoded quad: xy z / ARGB / uv / light / unused normal, 8 words per vertex. */
    public static void validateEncoded(int[] vertices, int quads, MemorySegment output) {
        if (vertices == null || output == null || output.isReadOnly() || quads < 0 || quads > MAX_QUADS
                || vertices.length < quads * 32 || output.byteSize() < (long) quads * QUAD_BYTES) {
            throw new IllegalArgumentException("Invalid encoded quad packet");
        }
    }

    public static int packEncodedJava(int[] vertices, int quads, MemorySegment output) {
        validateEncoded(vertices, quads, output);
        int[] packed = words();
        for (int v = 0; v < quads * 4; v++) {
            int s = v * 8, d = v * 7;
            packed[d] = vertices[s];
            packed[d + 1] = vertices[s + 1];
            packed[d + 2] = vertices[s + 2];
            int c = vertices[s + 3];
            packed[d + 3] = (c & 0xff00ff00) | ((c >>> 16) & 255) | ((c & 255) << 16);
            packed[d + 4] = vertices[s + 4];
            packed[d + 5] = vertices[s + 5];
            packed[d + 6] = vertices[s + 6];
        }
        publish(packed, quads * 28, output);
        return quads * QUAD_BYTES;
    }

    public static void validate(int[] templates, int templateCount, int[] records, int quadCount,
                                MemorySegment output) {
        if (templates == null || records == null || output == null || output.isReadOnly()
                || templateCount < 0 || templateCount > MAX_TEMPLATES || quadCount < 0 || quadCount > MAX_QUADS
                || templates.length < templateCount * TEMPLATE_WORDS
                || records.length < quadCount * RECORD_WORDS
                || output.byteSize() < (long) quadCount * QUAD_BYTES) {
            throw new IllegalArgumentException("Invalid block mesh packet dimensions or output");
        }
        for (int q = 0; q < quadCount; q++) {
            int id = records[q * RECORD_WORDS];
            if (id < 0 || id >= templateCount) throw new IllegalArgumentException("Invalid mesh template index");
        }
    }

    public static int packJava(int[] templates, int templateCount, int[] records, int quadCount,
                               MemorySegment output) {
        validate(templates, templateCount, records, quadCount, output);
        long p = 0;
        for (int q = 0; q < quadCount; q++) {
            int r = q * RECORD_WORDS;
            int t = records[r] * TEMPLATE_WORDS;
            float ox = Float.intBitsToFloat(records[r + 1]);
            float oy = Float.intBitsToFloat(records[r + 2]);
            float oz = Float.intBitsToFloat(records[r + 3]);
            for (int v = 0; v < 4; v++, p += VERTEX_BYTES) {
                int b = t + v * 5;
                output.set(LE_INT, p, Float.floatToRawIntBits(Float.intBitsToFloat(templates[b]) + ox));
                output.set(LE_INT, p + 4, Float.floatToRawIntBits(Float.intBitsToFloat(templates[b + 1]) + oy));
                output.set(LE_INT, p + 8, Float.floatToRawIntBits(Float.intBitsToFloat(templates[b + 2]) + oz));
                int c = records[r + 4 + v];
                int abgr = (c & 0xff00ff00) | ((c >>> 16) & 255) | ((c & 255) << 16);
                output.set(LE_INT, p + 12, abgr);
                output.set(LE_INT, p + 16, templates[b + 3]);
                output.set(LE_INT, p + 20, templates[b + 4]);
                output.set(LE_INT, p + 24, records[r + 8 + v]);
            }
        }
        return quadCount * QUAD_BYTES;
    }
}
