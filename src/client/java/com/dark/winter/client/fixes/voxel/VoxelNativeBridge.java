// Optional development-only Java 25 FFM bridge to the verified standalone full-cube mask kernel.
package com.dark.winter.client.fixes.voxel;

import com.dark.winter.client.fixes.nativebuffers.NativeScratchBuffers;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Loaded only on demand; missing native access or DLL must never prevent Minecraft startup. */
final class VoxelNativeBridge {
    static final int HALO_SIZE = 18 * 18 * 18;
    static final int MASK_SIZE = 16 * 16 * 16;

    private final Arena arena;
    private final MethodHandle call;
    private final ThreadLocal<Buffers> scratch;

    private static final class Buffers {
        final NativeScratchBuffers memory = new NativeScratchBuffers(NativeScratchBuffers.Kind.VOXEL, HALO_SIZE, MASK_SIZE);
        final MemorySegment halo = memory.segment(0), mask = memory.segment(1);
    }

    private VoxelNativeBridge(Path library) throws Throwable {
        Arena allocated = Arena.ofShared();
        try {
            SymbolLookup symbols = SymbolLookup.libraryLookup(library.toAbsolutePath(), allocated);
            MethodHandle apiVersion = Linker.nativeLinker().downcallHandle(
                    symbols.findOrThrow("dw_voxel_api_version"), FunctionDescriptor.of(JAVA_INT));
            if ((int) apiVersion.invokeExact() != 1) {
                throw new IllegalStateException("Unsupported voxel native API version");
            }
            this.call = Linker.nativeLinker().downcallHandle(
                    symbols.findOrThrow("dw_visible_faces"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        } catch (Throwable failure) {
            allocated.close();
            throw failure;
        }
        this.arena = allocated;
        this.scratch = new ThreadLocal<>();
        try {
            checkKernel();
        } catch (Throwable failure) {
            allocated.close();
            throw failure;
        } finally {
            releaseScratchCaches();
        }
    }

    static VoxelNativeBridge load(Path library) throws Throwable {
        if (library == null || !Files.isRegularFile(library)) {
            throw new IllegalStateException("Native DLL not found: " + library);
        }
        return new VoxelNativeBridge(library);
    }

    int compute(byte[] halo, byte[] masks) throws Throwable {
        if (halo.length != HALO_SIZE || masks.length != MASK_SIZE) {
            throw new IllegalArgumentException("Unexpected voxel buffer dimensions");
        }
        Buffers buffers = scratch.get();
        if (buffers == null || buffers.memory.isRetired()) {
            buffers = new Buffers();
            scratch.set(buffers);
        }
        if (!buffers.memory.tryAcquire()) return computeJava(halo, masks);
        try {
            MemorySegment.copy(MemorySegment.ofArray(halo), 0, buffers.halo, 0, HALO_SIZE);
            int faces = (int) call.invokeExact(buffers.halo, HALO_SIZE, buffers.mask, MASK_SIZE);
            if (faces < 0) throw new IllegalStateException("Native voxel kernel rejected input");
            MemorySegment.copy(buffers.mask, 0, MemorySegment.ofArray(masks), 0, MASK_SIZE);
            return faces;
        } finally {
            buffers.memory.release();
            if (!VoxelFaceRuntime.scratchCachingAllowed()) buffers.memory.retire();
            java.lang.ref.Reference.reachabilityFence(buffers);
        }
    }

    void releaseScratchCaches() {
        NativeScratchBuffers.retireAll(NativeScratchBuffers.Kind.VOXEL);
    }

    static int computeJava(byte[] halo, byte[] masks) {
        int count = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                int row = ((y + 1) * 18 + z + 1) * 18 + 1;
                int outRow = (y * 16 + z) * 16;
                for (int x = 0; x < 16; x++) {
                    int p = row + x;
                    int mask = 0;
                    if (halo[p] != 0) {
                        if (halo[p - 1] == 0) mask |= 1;
                        if (halo[p + 1] == 0) mask |= 2;
                        if (halo[p - 324] == 0) mask |= 4;
                        if (halo[p + 324] == 0) mask |= 8;
                        if (halo[p - 18] == 0) mask |= 16;
                        if (halo[p + 18] == 0) mask |= 32;
                    }
                    masks[outRow + x] = (byte) mask;
                    count += Integer.bitCount(mask);
                }
            }
        }
        return count;
    }

    private void checkKernel() throws Throwable {
        byte[] halo = new byte[HALO_SIZE];
        byte[] expected = new byte[MASK_SIZE];
        byte[] actual = new byte[MASK_SIZE];
        Random random = new Random(1997);
        for (int test = 0; test < 32; test++) {
            for (int i = 0; i < halo.length; i++) {
                halo[i] = (byte) (test == 0 ? 0 : test == 1 ? 1 : random.nextInt(2));
            }
            int expectedFaces = computeJava(halo, expected);
            int observed = compute(halo, actual);
            if (expectedFaces != observed || !Arrays.equals(expected, actual)) {
                throw new IllegalStateException("Native visible-face masks fail Java parity");
            }
        }
    }

    /** Conservative minimum improvement for Auto: include FFM and both memory copies. */
    boolean measurablyFasterThanJava() throws Throwable {
        byte[][] inputs = new byte[16][HALO_SIZE];
        Random random = new Random(19971008);
        for (byte[] input : inputs) {
            for (int i = 0; i < input.length; i++) input[i] = (byte) (random.nextInt(5) != 0 ? 1 : 0);
        }
        byte[] destination = new byte[MASK_SIZE];
        for (int round = 0; round < 16; round++) {
            for (byte[] input : inputs) {
                computeJava(input, destination);
                compute(input, destination);
            }
        }
        long[] javaTime = new long[5];
        long[] nativeTime = new long[5];
        for (int rep = 0; rep < javaTime.length; rep++) {
            long before = System.nanoTime();
            for (byte[] input : inputs) computeJava(input, destination);
            javaTime[rep] = System.nanoTime() - before;
            before = System.nanoTime();
            for (byte[] input : inputs) compute(input, destination);
            nativeTime[rep] = System.nanoTime() - before;
        }
        Arrays.sort(javaTime);
        Arrays.sort(nativeTime);
        return nativeTime[2] * 100L < javaTime[2] * 85L;
    }
}
