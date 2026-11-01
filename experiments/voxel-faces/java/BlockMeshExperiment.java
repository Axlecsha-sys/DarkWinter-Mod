import com.dark.winter.client.fixes.mesh.BlockMeshNative;
import com.dark.winter.client.fixes.mesh.BlockMeshPacking;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;

/** Standalone packet tests. Real Minecraft byte parity is separately tested by /dwmesh compare. */
public final class BlockMeshExperiment {
    private static volatile long sink;
    private record Case(int[] templates, int templateCount, int[] records, int quads) { }

    private static Case data(int templateCount, int quads, long seed) {
        Random r = new Random(seed);
        int[] templates = new int[templateCount * 20], records = new int[quads * 12];
        for (int t = 0; t < templateCount; t++) {
            for (int v = 0; v < 4; v++) {
                int b = t * 20 + v * 5;
                for (int axis = 0; axis < 3; axis++) {
                    float value = r.nextFloat() * 4 - 2;
                    if ((t + v + axis) % 17 == 0) value = -0.0f;
                    if ((t + v + axis) % 29 == 0) value = Float.MIN_NORMAL;
                    templates[b + axis] = Float.floatToRawIntBits(value);
                }
                templates[b + 3] = Float.floatToRawIntBits(r.nextFloat());
                templates[b + 4] = Float.floatToRawIntBits(r.nextFloat());
            }
        }
        for (int q = 0; q < quads; q++) {
            int b = q * 12;
            records[b] = r.nextInt(templateCount);
            for (int axis = 1; axis <= 3; axis++) records[b + axis] = Float.floatToRawIntBits(r.nextInt(16) + r.nextFloat());
            for (int v = 0; v < 4; v++) {
                records[b + 4 + v] = r.nextInt();
                records[b + 8 + v] = r.nextInt();
            }
        }
        return new Case(templates, templateCount, records, quads);
    }

    private static byte[] oracle(Case c) {
        ByteBuffer out = ByteBuffer.allocate(c.quads * 112).order(ByteOrder.LITTLE_ENDIAN);
        for (int q = 0; q < c.quads; q++) {
            int r = q * 12, t = c.records[r] * 20;
            for (int v = 0; v < 4; v++) {
                for (int axis = 0; axis < 3; axis++) {
                    out.putFloat(Float.intBitsToFloat(c.templates[t + v * 5 + axis]) + Float.intBitsToFloat(c.records[r + axis + 1]));
                }
                int argb = c.records[r + v + 4];
                out.put((byte) (argb >>> 16)).put((byte) (argb >>> 8)).put((byte) argb).put((byte) (argb >>> 24));
                out.putInt(c.templates[t + v * 5 + 3]).putInt(c.templates[t + v * 5 + 4]);
                int light = c.records[r + v + 8];
                out.putShort((short) light).putShort((short) (light >>> 16));
            }
        }
        return out.array();
    }

    private static void check(BlockMeshNative nativeCode, Case c) throws Throwable {
        byte[] expected = oracle(c);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment javaOut = a.allocate(expected.length + 32, 16);
            MemorySegment cppOut = a.allocate(expected.length + 32, 16);
            javaOut.fill((byte) 0x5a); cppOut.fill((byte) 0x5a);
            // Deliberately unaligned output tests that no alignment promise is relied upon.
            MemorySegment j = javaOut.asSlice(17, expected.length), n = cppOut.asSlice(17, expected.length);
            int writtenJ = BlockMeshPacking.packJava(c.templates, c.templateCount, c.records, c.quads, j);
            int writtenN = nativeCode.pack(c.templates, c.templateCount, c.records, c.quads, n, new BlockMeshNative.Scratch());
            if (writtenJ != expected.length || writtenN != expected.length
                    || !Arrays.equals(j.toArray(ValueLayout.JAVA_BYTE), expected)
                    || !Arrays.equals(n.toArray(ValueLayout.JAVA_BYTE), expected)) {
                throw new AssertionError("Packet mismatch: quads=" + c.quads);
            }
            for (int i = 0; i < 17; i++) {
                if (javaOut.get(ValueLayout.JAVA_BYTE, i) != 0x5a || cppOut.get(ValueLayout.JAVA_BYTE, i) != 0x5a) throw new AssertionError("Prefix guard overwritten");
            }
            for (int i = expected.length + 17; i < expected.length + 32; i++) {
                if (javaOut.get(ValueLayout.JAVA_BYTE, i) != 0x5a || cppOut.get(ValueLayout.JAVA_BYTE, i) != 0x5a) throw new AssertionError("Suffix guard overwritten");
            }
        }
    }

    private static void invalid(BlockMeshNative nativeCode) throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment t = a.allocate(80, 16), r = a.allocate(48, 16), out = a.allocate(112, 16);
            t.fill((byte) 0); r.fill((byte) 0); out.fill((byte) 0x5a);
            int[][] bad = {{19,12,112},{20,11,112},{20,12,111},{-1,12,112},{20,-12,112},{20,12,-1},
                           {Integer.MAX_VALUE,12,112},{20,Integer.MAX_VALUE,112},{0,12,112}};
            for (int[] p : bad) {
                if (nativeCode.probe(t,p[0],r,p[1],out,p[2]) >= 0) throw new AssertionError("Invalid dimensions accepted");
            }
            if (nativeCode.probe(MemorySegment.NULL,20,r,12,out,112) >= 0
                    || nativeCode.probe(t,20,MemorySegment.NULL,12,out,112) >= 0
                    || nativeCode.probe(t,20,r,12,MemorySegment.NULL,112) >= 0) throw new AssertionError("NULL accepted");
            if (nativeCode.probe(MemorySegment.NULL,0,MemorySegment.NULL,0,MemorySegment.NULL,0) != 0) throw new AssertionError("Empty batch failed");
            r.set(ValueLayout.JAVA_INT, 0, -1);
            if (nativeCode.probe(t,20,r,12,out,112) != -2) throw new AssertionError("Bad template index accepted");
            for (byte b : out.toArray(ValueLayout.JAVA_BYTE)) if (b != 0x5a) throw new AssertionError("Failure partially wrote output");
            boolean rejected = false;
            try { BlockMeshPacking.packJava(new int[20],1,new int[12],1,out.asSlice(0,111)); }
            catch (IllegalArgumentException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Java output bounds not enforced");
        }
    }

    @FunctionalInterface
    private interface CheckedCall { void run() throws Throwable; }

    private static void expectProbeFailure(String label, Class<? extends Throwable> type, CheckedCall call) throws Throwable {
        try {
            call.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError(label + ": unexpected failure", failure);
        }
        throw new AssertionError(label + ": unsafe segment accepted");
    }

    /** These cases must be rejected in Java before a C pointer can escape its physical segment. */
    private static void physicalSegments(BlockMeshNative nativeCode) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment templates = arena.allocate(96, 16), records = arena.allocate(64, 16);
            MemorySegment encoded = arena.allocate(144, 16), output = arena.allocate(128, 16);
            templates.fill((byte) 0); records.fill((byte) 0); encoded.fill((byte) 0); output.fill((byte) 0x5a);
            expectProbeFailure("short templates", IllegalArgumentException.class,
                    () -> nativeCode.probe(templates.asSlice(0, 79), 20, records, 12, output, 112));
            expectProbeFailure("short records", IllegalArgumentException.class,
                    () -> nativeCode.probe(templates, 20, records.asSlice(0, 47), 12, output, 112));
            expectProbeFailure("short template output", IllegalArgumentException.class,
                    () -> nativeCode.probe(templates, 20, records, 12, output.asSlice(0, 111), 112));
            expectProbeFailure("short encoded input", IllegalArgumentException.class,
                    () -> nativeCode.probeEncoded(encoded.asSlice(0, 127), 32, output, 112));
            expectProbeFailure("short encoded output", IllegalArgumentException.class,
                    () -> nativeCode.probeEncoded(encoded, 32, output.asSlice(0, 111), 112));
            expectProbeFailure("readonly template output", IllegalArgumentException.class,
                    () -> nativeCode.probe(templates, 20, records, 12, output.asReadOnly(), 112));
            expectProbeFailure("readonly encoded output", IllegalArgumentException.class,
                    () -> nativeCode.probeEncoded(encoded, 32, output.asReadOnly(), 112));
            expectProbeFailure("heap templates", IllegalArgumentException.class,
                    () -> nativeCode.probe(MemorySegment.ofArray(new int[20]), 20, records, 12, output, 112));
            expectProbeFailure("heap encoded input", IllegalArgumentException.class,
                    () -> nativeCode.probeEncoded(MemorySegment.ofArray(new int[32]), 32, output, 112));
            expectProbeFailure("heap output", IllegalArgumentException.class,
                    () -> nativeCode.probeEncoded(encoded, 32, MemorySegment.ofArray(new byte[112]), 112));
            expectProbeFailure("unaligned templates", IllegalArgumentException.class,
                    () -> nativeCode.probe(templates.asSlice(1, 80), 20, records, 12, output, 112));
            expectProbeFailure("unaligned records", IllegalArgumentException.class,
                    () -> nativeCode.probe(templates, 20, records.asSlice(1, 48), 12, output, 112));
            expectProbeFailure("unaligned encoded input", IllegalArgumentException.class,
                    () -> nativeCode.probeEncoded(encoded.asSlice(1, 128), 32, output, 112));
            Arena closedArena = Arena.ofConfined();
            MemorySegment closed = closedArena.allocate(128, 16);
            closedArena.close();
            expectProbeFailure("closed templates", IllegalStateException.class,
                    () -> nativeCode.probe(closed, 20, records, 12, output, 112));
            expectProbeFailure("closed encoded input", IllegalStateException.class,
                    () -> nativeCode.probeEncoded(closed, 32, output, 112));
            expectProbeFailure("closed output", IllegalStateException.class,
                    () -> nativeCode.probeEncoded(encoded, 32, closed, 112));
            try (Arena shared = Arena.ofShared(); var pool = Executors.newSingleThreadExecutor()) {
                MemorySegment sharedTemplates = shared.allocate(80, 16), sharedRecords = shared.allocate(48, 16);
                MemorySegment sharedEncoded = shared.allocate(128, 16), sharedOutput = shared.allocate(112, 16);
                pool.submit(() -> {
                    try {
                        expectProbeFailure("foreign-thread templates", WrongThreadException.class,
                                () -> nativeCode.probe(templates, 20, sharedRecords, 12, sharedOutput, 112));
                        expectProbeFailure("foreign-thread template output", WrongThreadException.class,
                                () -> nativeCode.probe(sharedTemplates, 20, sharedRecords, 12, output, 112));
                        expectProbeFailure("foreign-thread encoded input", WrongThreadException.class,
                                () -> nativeCode.probeEncoded(encoded, 32, sharedOutput, 112));
                        expectProbeFailure("foreign-thread encoded output", WrongThreadException.class,
                                () -> nativeCode.probeEncoded(sharedEncoded, 32, output, 112));
                    } catch (Throwable failure) {
                        throw new AssertionError("Confined probe access", failure);
                    }
                }).get();
            }
            for (byte b : output.toArray(ValueLayout.JAVA_BYTE)) {
                if (b != 0x5a) throw new AssertionError("Rejected physical probe wrote output");
            }
            // Readonly inputs and byte-unaligned output are valid; C reads uint32 inputs and writes bytes.
            MemorySegment unalignedOutput = output.asSlice(1, 112);
            if (nativeCode.probe(templates.asReadOnly(), 20, records.asReadOnly(), 12, unalignedOutput, 112) != 112
                    || nativeCode.probeEncoded(encoded.asReadOnly(), 32, unalignedOutput, 112) != 112) {
                throw new AssertionError("Valid physical probe rejected");
            }
            if (output.get(ValueLayout.JAVA_BYTE, 0) != 0x5a || output.get(ValueLayout.JAVA_BYTE, 113) != 0x5a) {
                throw new AssertionError("Physical probe output bounds overwritten");
            }
        }
    }

    private static void checkEncoded(BlockMeshNative nativeCode, int quads, long seed) throws Throwable {
        Random random = new Random(seed);
        int[] input = new int[quads * 32];
        for (int i = 0; i < input.length; i++) input[i] = random.nextInt();
        ByteBuffer expected = ByteBuffer.allocate(quads * 112).order(ByteOrder.LITTLE_ENDIAN);
        for (int v = 0; v < quads * 4; v++) {
            int s = v * 8, c = input[s + 3];
            expected.putInt(input[s]).putInt(input[s + 1]).putInt(input[s + 2]);
            expected.put((byte)(c >>> 16)).put((byte)(c >>> 8)).put((byte)c).put((byte)(c >>> 24));
            expected.putInt(input[s + 4]).putInt(input[s + 5]).putInt(input[s + 6]);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ja = arena.allocate(quads * 112L + 32, 16), na = arena.allocate(quads * 112L + 32, 16);
            ja.fill((byte)0x5a); na.fill((byte)0x5a);
            MemorySegment j = ja.asSlice(17, quads * 112L), n = na.asSlice(17, quads * 112L);
            BlockMeshPacking.packEncodedJava(input, quads, j);
            nativeCode.packEncoded(input, quads, n, new BlockMeshNative.Scratch());
            if (!Arrays.equals(expected.array(), j.toArray(ValueLayout.JAVA_BYTE))
                    || !Arrays.equals(expected.array(), n.toArray(ValueLayout.JAVA_BYTE))) throw new AssertionError("Encoded packet mismatch");
            for (int i = 0; i < 17; i++) if (ja.get(ValueLayout.JAVA_BYTE,i)!=0x5a || na.get(ValueLayout.JAVA_BYTE,i)!=0x5a) throw new AssertionError("Encoded prefix guard");
            for (int i = 17 + quads * 112; i < quads * 112 + 32; i++) if (ja.get(ValueLayout.JAVA_BYTE,i)!=0x5a || na.get(ValueLayout.JAVA_BYTE,i)!=0x5a) throw new AssertionError("Encoded suffix guard");
            MemorySegment source = arena.allocate(128,16), output = arena.allocate(112,16);
            output.fill((byte)0x5a);
            if (nativeCode.probeEncoded(source,31,output,112)>=0 || nativeCode.probeEncoded(source,-32,output,112)>=0
                    || nativeCode.probeEncoded(source,32,output,111)>=0 || nativeCode.probeEncoded(source,Integer.MAX_VALUE,output,112)>=0
                    || nativeCode.probeEncoded(MemorySegment.NULL,32,output,112)>=0 || nativeCode.probeEncoded(source,32,MemorySegment.NULL,112)>=0)
                throw new AssertionError("Encoded invalid input accepted");
            for (byte b : output.toArray(ValueLayout.JAVA_BYTE)) if (b != 0x5a) throw new AssertionError("Encoded invalid input wrote output");
        }
    }

    private static void verify(BlockMeshNative nativeCode) throws Throwable {
        for (int i = 0; i < 256; i++) check(nativeCode, data(1 + i % 37, i % 16 == 0 ? 1024 : i % 513, 19971008L + i));
        invalid(nativeCode);
        physicalSegments(nativeCode);
        for (int i = 0; i < 128; i++) checkEncoded(nativeCode, i % 16 == 0 ? 1024 : i, 772299L + i);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int w = 0; w < 4; w++) {
                final int worker = w;
                futures.add(pool.submit(() -> {
                    try {
                        for (int i = 0; i < 32; i++) {
                            check(nativeCode, data(6, 64 + i, 9191L + worker * 100 + i));
                            checkEncoded(nativeCode, 64 + i, 6633L + worker * 100 + i);
                        }
                    }
                    catch (Throwable e) { throw new AssertionError("Concurrent native packet", e); }
                }));
            }
            for (var f : futures) f.get();
        }
        System.out.println("PASS: 384 template packets + 256 Indigo-encoded packets (640 total), sequential/concurrent byte-exact oracle, unaligned output guards, native NULL/length/index checks.");
    }

    private static long time(BlockMeshNative nativeCode, BlockMeshNative.Scratch scratch, Case c,
                             MemorySegment out, boolean cpp, int loops) throws Throwable {
        long start = System.nanoTime();
        for (int i = 0; i < loops; i++) {
            if (cpp) nativeCode.pack(c.templates,c.templateCount,c.records,c.quads,out,scratch);
            else BlockMeshPacking.packJava(c.templates,c.templateCount,c.records,c.quads,out);
            sink += out.get(ValueLayout.JAVA_BYTE, (i * 17L) % out.byteSize());
        }
        return System.nanoTime() - start;
    }

    private static void benchmark(BlockMeshNative nativeCode) throws Throwable {
        Case c = data(24, 1024, 12345);
        BlockMeshNative.Scratch scratch = new BlockMeshNative.Scratch();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate((long)c.quads * 112, 16);
            for (int i = 0; i < 5; i++) { time(nativeCode,scratch,c,out,false,100); time(nativeCode,scratch,c,out,true,100); }
            long[] javaTimes = new long[7], nativeTimes = new long[7];
            for (int i = 0; i < 7; i++) {
                if ((i & 1) == 0) { javaTimes[i]=time(nativeCode,scratch,c,out,false,200); nativeTimes[i]=time(nativeCode,scratch,c,out,true,200); }
                else { nativeTimes[i]=time(nativeCode,scratch,c,out,true,200); javaTimes[i]=time(nativeCode,scratch,c,out,false,200); }
            }
            System.out.println("RAW Java ns: " + Arrays.toString(javaTimes));
            System.out.println("RAW native end-to-end ns: " + Arrays.toString(nativeTimes));
            Arrays.sort(javaTimes); Arrays.sort(nativeTimes);
            System.out.printf(Locale.ROOT, "PACK BENCH: 1024 quads/batch; median Java %.3f us, C++ + input copies + FFM %.3f us, ratio %.3f, sink %d%n",
                    javaTimes[3]/200000.0, nativeTimes[3]/200000.0, (double)javaTimes[3]/nativeTimes[3], sink);
            System.out.println("NOT an FPS or whole-section benchmark. Packet construction is excluded here; /dwmesh compare measures actual full compilation.");
        }
    }

    public static void main(String[] args) throws Throwable {
        String library = System.getProperty("dw.voxel.native.library", "");
        if (library.isBlank()) throw new IllegalArgumentException("Native library path required");
        try (BlockMeshNative nativeCode = new BlockMeshNative(Path.of(library))) {
            verify(nativeCode);
            if (args.length > 0 && args[0].equals("benchmark")) benchmark(nativeCode);
        }
    }
}
