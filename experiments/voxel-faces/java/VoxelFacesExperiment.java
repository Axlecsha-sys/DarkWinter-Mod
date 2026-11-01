// Standalone Java 25 / C++17 experiment. Intentionally unrelated to Minecraft runtime.
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

public final class VoxelFacesExperiment {
    private static final int EDGE = 16;
    private static final int HALO_EDGE = 18;
    private static final int HALO_PLANE = HALO_EDGE * HALO_EDGE;
    private static final int HALO_SIZE = HALO_EDGE * HALO_EDGE * HALO_EDGE;
    private static final int SIZE = EDGE * EDGE * EDGE;
    private static volatile long blackhole;

    private VoxelFacesExperiment() {}

    private static int haloIndex(int x, int y, int z) {
        return (y * HALO_EDGE + z) * HALO_EDGE + x;
    }

    private static int voxelIndex(int x, int y, int z) {
        return (y * EDGE + z) * EDGE + x;
    }

    // Fast Java implementation. x varies fastest; one byte per solid/air cell.
    private static int javaFaces(byte[] halo, byte[] masks) {
        requireBuffers(halo, masks);
        int faces = 0;
        for (int y = 0; y < EDGE; y++) {
            for (int z = 0; z < EDGE; z++) {
                int row = haloIndex(1, y + 1, z + 1);
                int outRow = voxelIndex(0, y, z);
                for (int x = 0; x < EDGE; x++) {
                    int p = row + x;
                    int mask = 0;
                    if (halo[p] != 0) {
                        if (halo[p - 1] == 0) mask |= 1;
                        if (halo[p + 1] == 0) mask |= 2;
                        if (halo[p - HALO_PLANE] == 0) mask |= 4;
                        if (halo[p + HALO_PLANE] == 0) mask |= 8;
                        if (halo[p - HALO_EDGE] == 0) mask |= 16;
                        if (halo[p + HALO_EDGE] == 0) mask |= 32;
                    }
                    masks[outRow + x] = (byte) mask;
                    faces += Integer.bitCount(mask);
                }
            }
        }
        return faces;
    }

    // Deliberately independent coordinate-based oracle, not the optimized loop.
    private static int oracleFaces(byte[] halo, byte[] masks) {
        requireBuffers(halo, masks);
        int total = 0;
        int[] dx = {-1, 1, 0, 0, 0, 0};
        int[] dy = {0, 0, -1, 1, 0, 0};
        int[] dz = {0, 0, 0, 0, -1, 1};
        for (int y = 0; y < EDGE; y++) {
            for (int z = 0; z < EDGE; z++) {
                for (int x = 0; x < EDGE; x++) {
                    int mask = 0;
                    int hx = x + 1, hy = y + 1, hz = z + 1;
                    if (halo[haloIndex(hx, hy, hz)] != 0) {
                        for (int face = 0; face < 6; face++) {
                            if (halo[haloIndex(hx + dx[face], hy + dy[face], hz + dz[face])] == 0) {
                                mask |= 1 << face;
                                total++;
                            }
                        }
                    }
                    masks[voxelIndex(x, y, z)] = (byte) mask;
                }
            }
        }
        return total;
    }

    private static void requireBuffers(byte[] halo, byte[] masks) {
        if (halo == null || masks == null || halo.length != HALO_SIZE || masks.length != SIZE) {
            throw new IllegalArgumentException("Expected 5832-byte halo and 4096-byte output.");
        }
    }

    private static byte[] scene(int kind, long seed) {
        byte[] halo = new byte[HALO_SIZE];
        Random rng = new Random(seed);
        if (kind == 2) {
            Arrays.fill(halo, (byte) 1); // Completely enclosed section: zero visible faces.
            return halo;
        }
        if (kind == 4) {
            // One corner block with two opaque neighbors beyond section boundaries.
            halo[haloIndex(1, 1, 1)] = 1;
            halo[haloIndex(0, 1, 1)] = 1;
            halo[haloIndex(1, 0, 1)] = 1;
            return halo;
        }
        if (kind == 3 || kind == 6) {
            // Populate the border too, so cross-section culling gets exercised.
            for (int y = 0; y < HALO_EDGE; y++) {
                for (int z = 0; z < HALO_EDGE; z++) {
                    for (int x = 0; x < HALO_EDGE; x++) {
                        halo[haloIndex(x, y, z)] = (byte) (
                            kind == 3 ? ((x + y + z) & 1) : (rng.nextInt(100) < 38 ? 1 : 0));
                    }
                }
            }
            return halo;
        }
        for (int y = 1; y <= EDGE; y++) {
            for (int z = 1; z <= EDGE; z++) {
                int terrainHeight = 3 + ((z * 5 + 3) % 8);
                for (int x = 1; x <= EDGE; x++) {
                    boolean solid = switch (kind) {
                        case 0 -> false;
                        case 1 -> true; // Filled section with all six boundary faces exposed.
                        case 5 -> y <= terrainHeight + ((x * 7 + z) % 4);
                        default -> rng.nextInt(100) < 45;
                    };
                    halo[haloIndex(x, y, z)] = (byte) (solid ? 1 : 0);
                }
            }
        }
        return halo;
    }

    private static void compare(String label, byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            for (int i = 0; i < expected.length; i++) {
                if (expected[i] != actual[i]) {
                    throw new AssertionError(label + ": mismatch at voxel " + i
                        + ": expected " + (expected[i] & 255) + ", found " + (actual[i] & 255));
                }
            }
        }
    }

    private static void verify(NativeBridge nativeBridge) throws Throwable {
        int checked = 0;
        for (int kind = 0; kind <= 7; kind++) {
            checked += checkCase("fixed-" + kind, scene(kind, 0xD4A21L), nativeBridge);
        }
        for (int i = 0; i < 256; i++) {
            checked += checkCase("random-" + i, scene(6, 129847L + i * 131L), nativeBridge);
        }
        byte[] filled = scene(1, 0);
        byte[] result = new byte[SIZE];
        if (javaFaces(filled, result) != 6 * EDGE * EDGE) {
            throw new AssertionError("Full section must expose exactly six outer faces.");
        }
        if (javaFaces(scene(2, 0), result) != 0) {
            throw new AssertionError("Completely enclosed section must expose zero faces.");
        }
        if (javaFaces(scene(4, 0), result) != 4) {
            throw new AssertionError("Corner block must have exactly four exposed faces.");
        }
        if (javaFaces(scene(0, 0), result) != 0) {
            throw new AssertionError("Empty section must expose zero faces.");
        }
        boolean invalidRejected = false;
        try {
            javaFaces(new byte[HALO_SIZE - 1], result);
        } catch (IllegalArgumentException expected) {
            invalidRejected = true;
        }
        if (!invalidRejected) throw new AssertionError("Java buffer bounds were not enforced.");
        if (nativeBridge != null && !nativeBridge.invalidLengthsRejected()) {
            throw new AssertionError("C++ length validation failed.");
        }
        System.out.println("PASS: " + checked + " scenes, byte-for-byte masks, count, boundary cases, bad lengths."
            + " Native=" + (nativeBridge == null ? "FALLBACK_JAVA" : "C++/FFM"));
    }

    private static int checkCase(String label, byte[] input, NativeBridge nativeBridge) throws Throwable {
        byte[] oracle = new byte[SIZE];
        byte[] javaOut = new byte[SIZE];
        int expected = oracleFaces(input, oracle);
        int actualJava = javaFaces(input, javaOut);
        if (expected != actualJava) throw new AssertionError(label + " Java faces: " + actualJava + " != " + expected);
        compare(label + "-java", oracle, javaOut);
        if (nativeBridge != null) {
            byte[] nativeOut = new byte[SIZE];
            int actualNative = nativeBridge.computeWithCopies(input, nativeOut);
            if (expected != actualNative) {
                throw new AssertionError(label + " native faces: " + actualNative + " != " + expected);
            }
            compare(label + "-native", oracle, nativeOut);
        } else {
            // This is the actual conservative fallback if the DLL is not available.
            byte[] fallback = new byte[SIZE];
            int faceCount = javaFaces(input, fallback);
            if (faceCount != expected) throw new AssertionError(label + " fallback count");
            compare(label + "-fallback", oracle, fallback);
        }
        return 1;
    }

    private static final class NativeBridge implements AutoCloseable {
        private final Arena arena;
        private final MethodHandle faces;
        private final MemorySegment input;
        private final MemorySegment output;

        private NativeBridge(Path libraryPath) throws Throwable {
            arena = Arena.ofShared();
            try {
                SymbolLookup symbols = SymbolLookup.libraryLookup(libraryPath.toAbsolutePath(), arena);
                MethodHandle version = Linker.nativeLinker().downcallHandle(
                    symbols.findOrThrow("dw_voxel_api_version"), FunctionDescriptor.of(JAVA_INT));
                int api = (int) version.invokeExact();
                if (api != 1) throw new IllegalStateException("Native ABI mismatch: " + api);
                faces = Linker.nativeLinker().downcallHandle(
                    symbols.findOrThrow("dw_visible_faces"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
                input = arena.allocate(HALO_SIZE);
                output = arena.allocate(SIZE);
            } catch (Throwable error) {
                arena.close();
                throw error;
            }
        }

        private void upload(byte[] halo) {
            MemorySegment.copy(MemorySegment.ofArray(halo), 0, input, 0, HALO_SIZE);
        }

        private int call(MemorySegment in, MemorySegment out, int inputSize, int outputSize) throws Throwable {
            return (int) faces.invokeExact(in, inputSize, out, outputSize);
        }

        private int computeWithCopies(byte[] halo, byte[] masks) throws Throwable {
            requireBuffers(halo, masks);
            upload(halo);
            int result = call(input, output, HALO_SIZE, SIZE);
            if (result < 0) throw new IllegalStateException("Native error " + result);
            MemorySegment.copy(output, 0, MemorySegment.ofArray(masks), 0, SIZE);
            return result;
        }

        private boolean invalidLengthsRejected() throws Throwable {
            return call(input, output, HALO_SIZE - 1, SIZE) == -1
                && call(input, output, HALO_SIZE, SIZE - 1) == -1
                && call(MemorySegment.NULL, output, HALO_SIZE, SIZE) == -1;
        }

        private MemorySegment[] preload(byte[][] halos) {
            MemorySegment[] inputSegments = new MemorySegment[halos.length];
            for (int i = 0; i < halos.length; i++) {
                MemorySegment segment = arena.allocate(HALO_SIZE);
                MemorySegment.copy(MemorySegment.ofArray(halos[i]), 0, segment, 0, HALO_SIZE);
                inputSegments[i] = segment;
            }
            return inputSegments;
        }

        private int preloaded(MemorySegment in) throws Throwable {
            return call(in, output, HALO_SIZE, SIZE);
        }

        @Override
        public void close() {
            arena.close();
        }
    }

    private static long timeJava(byte[][] cases, int rounds) {
        byte[] output = new byte[SIZE];
        long digest = 0;
        long start = System.nanoTime();
        for (int round = 0; round < rounds; round++) {
            for (int i = 0; i < cases.length; i++) {
                int faces = javaFaces(cases[i], output);
                digest += (long) faces * 17 + (output[(round + i) & (SIZE - 1)] & 255);
            }
        }
        long elapsed = System.nanoTime() - start;
        blackhole ^= digest;
        return elapsed;
    }

    private static long timeNativeWithCopies(NativeBridge bridge, byte[][] cases, int rounds) throws Throwable {
        byte[] output = new byte[SIZE];
        long digest = 0;
        long start = System.nanoTime();
        for (int round = 0; round < rounds; round++) {
            for (int i = 0; i < cases.length; i++) {
                int faces = bridge.computeWithCopies(cases[i], output);
                digest += (long) faces * 17 + (output[(round + i) & (SIZE - 1)] & 255);
            }
        }
        long elapsed = System.nanoTime() - start;
        blackhole ^= digest;
        return elapsed;
    }

    private static long timeNativePreloaded(NativeBridge bridge, MemorySegment[] cases, int rounds)
            throws Throwable {
        long digest = 0;
        long start = System.nanoTime();
        for (int round = 0; round < rounds; round++) {
            for (MemorySegment input : cases) {
                digest += (long) bridge.preloaded(input) * 17;
            }
        }
        long elapsed = System.nanoTime() - start;
        blackhole ^= digest;
        return elapsed;
    }

    private static double median(long[] values) {
        long[] ordered = values.clone();
        Arrays.sort(ordered);
        return ordered[ordered.length / 2];
    }

    private static void benchmark(NativeBridge bridge) throws Throwable {
        final int sceneCount = 48;
        final int rounds = 150;
        final int repeats = 7;
        byte[][] inputs = new byte[sceneCount][];
        for (int i = 0; i < sceneCount; i++) {
            inputs[i] = scene(i % 8, 0xDA2026L + 7919L * i);
        }
        if (bridge == null) {
            for (int i = 0; i < 5; i++) timeJava(inputs, 55);
            long[] javaOnly = new long[repeats];
            for (int i = 0; i < repeats; i++) javaOnly[i] = timeJava(inputs, rounds);
            System.out.printf(Locale.ROOT,
                "JAVA ONLY: sections=%d rounds=%d repeats=%d, median=%.2f us/section, sink=%d%n",
                sceneCount, rounds, repeats, median(javaOnly) / (sceneCount * rounds * 1000.0), blackhole);
            System.out.println("C++ comparison unavailable: native library not loaded.");
            return;
        }
        MemorySegment[] preloaded = bridge.preload(inputs);
        // JIT warm-up is outside measured samples.
        for (int i = 0; i < 5; i++) {
            timeJava(inputs, 55);
            timeNativeWithCopies(bridge, inputs, 55);
            timeNativePreloaded(bridge, preloaded, 55);
        }
        long[] javaTimes = new long[repeats];
        long[] nativeTimes = new long[repeats];
        long[] preloadedTimes = new long[repeats];
        for (int sample = 0; sample < repeats; sample++) {
            // Alternate order to reduce systematic temperature/frequency bias.
            if ((sample & 1) == 0) {
                javaTimes[sample] = timeJava(inputs, rounds);
                nativeTimes[sample] = timeNativeWithCopies(bridge, inputs, rounds);
            } else {
                nativeTimes[sample] = timeNativeWithCopies(bridge, inputs, rounds);
                javaTimes[sample] = timeJava(inputs, rounds);
            }
            preloadedTimes[sample] = timeNativePreloaded(bridge, preloaded, rounds);
        }
        double n = (double) sceneCount * rounds;
        double javaNs = median(javaTimes) / n;
        double nativeNs = median(nativeTimes) / n;
        double preloadedNs = median(preloadedTimes) / n;
        System.out.printf(Locale.ROOT,
            "BENCH: sections=%d rounds=%d repeats=%d, median Java=%.2f us/section,"
            + " native+FFM+copies=%.2f us/section, native preloaded+FFM=%.2f us/section%n",
            sceneCount, rounds, repeats, javaNs / 1000.0, nativeNs / 1000.0, preloadedNs / 1000.0);
        System.out.printf(Locale.ROOT, "BENCH: Java/native-end-to-end ratio=%.3f (values >1 favor C++), sink=%d%n",
            javaNs / nativeNs, blackhole);
        System.out.println("NOTE: This is a local synthetic benchmark, not Minecraft FPS or a full mesh renderer.");
    }

    public static void main(String[] args) throws Throwable {
        String mode = args.length == 0 ? "verify" : args[0];
        String path = System.getProperty("dw.voxel.native.library", "");
        boolean required = Boolean.getBoolean("dw.voxel.native.required");
        NativeBridge bridge = null;
        if (!path.isBlank()) {
            try {
                Path library = Path.of(path).toAbsolutePath();
                if (!Files.isRegularFile(library)) throw new IllegalArgumentException("Library missing: " + library);
                bridge = new NativeBridge(library);
                System.out.println("Native C++/FFM available: " + library.getFileName());
            } catch (Throwable failure) {
                if (required) throw failure;
                System.out.println("Native unavailable; Java fallback: " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
            }
        } else if (required) {
            throw new IllegalStateException("Native backend required but no library path supplied.");
        }
        try {
            switch (mode) {
                case "verify" -> verify(bridge);
                case "benchmark" -> {
                    verify(bridge);
                    benchmark(bridge);
                }
                case "fallback" -> verify(null);
                default -> throw new IllegalArgumentException("Mode must be verify, benchmark or fallback.");
            }
        } finally {
            if (bridge != null) bridge.close();
        }
    }
}
