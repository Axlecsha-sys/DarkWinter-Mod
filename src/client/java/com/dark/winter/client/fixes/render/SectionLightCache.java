// Stores raw light and raw float shade bits in bounded generation-tagged primitive arrays.
package com.dark.winter.client.fixes.render;

import java.util.Arrays;

public final class SectionLightCache {
    public static final int SLOTS = configuredSlots();
    public static final int RAW_MISS = Integer.MAX_VALUE;
    private static final int MASK = SLOTS - 1;
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("darkwinter.render.sectionLightCache", "false"));
    private final long[] rawTags = new long[SLOTS];
    private final long[] shadeTags = new long[SLOTS];
    private final int[] rawValues = new int[SLOTS];
    private final int[] shadeBits = new int[SLOTS];
    private final int[] rawGenerations = new int[SLOTS];
    private final int[] shadeGenerations = new int[SLOTS];
    private int generation = 1;

    private static int configuredSlots() {
        return switch (System.getProperty("darkwinter.render.sectionLightCacheSlots", "1024")) {
            case "1024" -> 1024;
            case "2048" -> 2048;
            case "8192" -> 8192;
            default -> 1024;
        };
    }
    public static boolean enabled() { return ENABLED; }
    public static int slots() { return SLOTS; }
    public static long primitivePayloadBytes() { return 2L * Long.BYTES * SLOTS + 4L * Integer.BYTES * SLOTS; }

    public static int index(long key) {
        long mixed = key * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 32;
        return (int) (mixed ^ (mixed >>> 16)) & MASK;
    }
    public int rawGet(long key) {
        int slot = index(key);
        return rawGenerations[slot] == generation && rawTags[slot] == key ? rawValues[slot] : RAW_MISS;
    }
    public int rawPut(long key, int value) {
        int slot = index(key);
        int previous = rawGenerations[slot] == generation && rawTags[slot] == key ? rawValues[slot] : RAW_MISS;
        rawTags[slot] = key; rawValues[slot] = value; rawGenerations[slot] = generation;
        return previous;
    }
    public float shadeGet(long key) {
        int slot = index(key);
        return shadeGenerations[slot] == generation && shadeTags[slot] == key
            ? Float.intBitsToFloat(shadeBits[slot]) : Float.NaN;
    }
    public float shadePut(long key, float value) {
        int slot = index(key);
        float previous = shadeGenerations[slot] == generation && shadeTags[slot] == key
            ? Float.intBitsToFloat(shadeBits[slot]) : Float.NaN;
        shadeTags[slot] = key; shadeBits[slot] = Float.floatToRawIntBits(value); shadeGenerations[slot] = generation;
        return previous;
    }
    public void clear() {
        if (++generation == 0) {
            Arrays.fill(rawGenerations, 0); Arrays.fill(shadeGenerations, 0); generation = 1;
        }
    }
}
