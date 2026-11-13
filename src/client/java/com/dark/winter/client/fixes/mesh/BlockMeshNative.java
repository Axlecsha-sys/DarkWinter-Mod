// Calls the experimental native mesh kernel with bounded, explicitly retired worker scratch.
package com.dark.winter.client.fixes.mesh;

import com.dark.winter.client.fixes.nativebuffers.NativeScratchBuffers;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** A synchronous, bounded FFM call. Native code never retains the borrowed output address. */
public final class BlockMeshNative implements AutoCloseable {
    private final Arena libraryArena;
    private final MethodHandle pack;
    private final MethodHandle packEncoded;

    public static final class Scratch implements AutoCloseable {
        private final NativeScratchBuffers buffers = new NativeScratchBuffers(NativeScratchBuffers.Kind.MESH,
                (long) BlockMeshPacking.MAX_TEMPLATES * 80, (long) BlockMeshPacking.MAX_QUADS * 48,
                (long) BlockMeshPacking.MAX_QUADS * 128);
        final MemorySegment templates = buffers.segment(0);
        final MemorySegment records = buffers.segment(1);
        final MemorySegment encoded = buffers.segment(2);
        public long copyNanos = BlockMeshPacking.UNAVAILABLE_NANOS;
        public long callNanos = BlockMeshPacking.UNAVAILABLE_NANOS;
        public boolean isRetired() { return buffers.isRetired(); }
        @Override public void close() { buffers.retire(); }
    }

    public static final class ScratchRetiredException extends IllegalStateException {
        private ScratchRetiredException() { super("Native mesh scratch was retired"); }
    }

    public BlockMeshNative(Path library) throws Throwable {
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            throw new UnsupportedOperationException("The experimental input ABI is little-endian only");
        }
        if (library == null || !Files.isRegularFile(library)) {
            throw new IllegalArgumentException("Block mesh library not found: " + library);
        }
        Arena arena = Arena.ofShared();
        try {
            SymbolLookup symbols = SymbolLookup.libraryLookup(library.toRealPath(), arena);
            Linker linker = Linker.nativeLinker();
            MethodHandle version = linker.downcallHandle(symbols.findOrThrow("dw_block_mesh_api_version"),
                    FunctionDescriptor.of(JAVA_INT));
            int actual = (int) version.invokeExact();
            if (actual != 1) throw new IllegalStateException("Unsupported block mesh ABI: " + actual);
            pack = linker.downcallHandle(symbols.findOrThrow("dw_pack_block_mesh"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
            packEncoded = linker.downcallHandle(symbols.findOrThrow("dw_pack_indigo_mesh"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
            libraryArena = arena;
        } catch (Throwable failure) {
            arena.close();
            throw failure;
        }
    }

    public int pack(int[] templates, int templateCount, int[] records, int quadCount,
                    MemorySegment output, Scratch scratch) throws Throwable {
        BlockMeshPacking.validate(templates, templateCount, records, quadCount, output);
        if (!output.isNative()) throw new IllegalArgumentException("Native output must be native memory");
        int templateWords = templateCount * BlockMeshPacking.TEMPLATE_WORDS;
        int recordWords = quadCount * BlockMeshPacking.RECORD_WORDS;
        int bytes = quadCount * BlockMeshPacking.QUAD_BYTES;
        if (!scratch.buffers.tryAcquire()) throw new ScratchRetiredException();
        try {
            long start = BlockMeshPacking.DETAILED_TIMING ? System.nanoTime() : 0L;
            MemorySegment.copy(MemorySegment.ofArray(templates), 0, scratch.templates, 0, (long) templateWords * 4);
            MemorySegment.copy(MemorySegment.ofArray(records), 0, scratch.records, 0, (long) recordWords * 4);
            long called = BlockMeshPacking.DETAILED_TIMING ? System.nanoTime() : 0L;
            int result = (int) pack.invokeExact(scratch.templates, templateWords, scratch.records, recordWords, output, bytes);
            if (BlockMeshPacking.DETAILED_TIMING) {
                scratch.callNanos = System.nanoTime() - called;
                scratch.copyNanos = called - start;
            }
            if (result != bytes) throw new IllegalStateException("Native block mesh rejected packet: " + result);
            return result;
        } finally {
            scratch.buffers.release();
            java.lang.ref.Reference.reachabilityFence(scratch);
        }
    }

    public int packEncoded(int[] vertices, int quads, MemorySegment output, Scratch scratch) throws Throwable {
        BlockMeshPacking.validateEncoded(vertices, quads, output);
        if (!output.isNative()) throw new IllegalArgumentException("Native output required");
        int words = quads * 32, bytes = quads * BlockMeshPacking.QUAD_BYTES;
        if (!scratch.buffers.tryAcquire()) throw new ScratchRetiredException();
        try {
            long start = BlockMeshPacking.DETAILED_TIMING ? System.nanoTime() : 0L;
            MemorySegment.copy(MemorySegment.ofArray(vertices), 0, scratch.encoded, 0, (long) words * 4);
            long called = BlockMeshPacking.DETAILED_TIMING ? System.nanoTime() : 0L;
            int result = (int) packEncoded.invokeExact(scratch.encoded, words, output, bytes);
            if (BlockMeshPacking.DETAILED_TIMING) {
                scratch.callNanos = System.nanoTime() - called;
                scratch.copyNanos = called - start;
            }
            if (result != bytes) throw new IllegalStateException("Native encoded packet rejected: " + result);
            return result;
        } finally {
            scratch.buffers.release();
            java.lang.ref.Reference.reachabilityFence(scratch);
        }
    }

    /** Exercises native numeric validation without allowing an invalid physical segment. Test use only. */
    public int probeEncoded(MemorySegment vertices, int words, MemorySegment output, int outputBytes) throws Throwable {
        long inputAddress = probeAddress(vertices, false, "vertices");
        long outputAddress = probeAddress(output, true, "output");
        boolean validShape = words >= 0 && words <= BlockMeshPacking.MAX_QUADS * 32
                && words % 32 == 0 && outputBytes >= 0;
        int bytes = validShape ? (words / 32) * BlockMeshPacking.QUAD_BYTES : 0;
        // NULL and malformed numeric packets still reach the C guards, which return before dereferencing.
        if (validShape && words != 0 && inputAddress != 0 && outputAddress != 0 && outputBytes >= bytes) {
            requireProbeExtent(vertices, (long) words * Integer.BYTES, Integer.BYTES, "vertices");
            requireProbeExtent(output, bytes, 1, "output");
        }
        return (int) packEncoded.invokeExact(vertices, words, output, outputBytes);
    }

    /** Exercises native numeric validation without allowing an invalid physical segment. Test use only. */
    public int probe(MemorySegment templates, int templateWords, MemorySegment records, int recordWords,
                     MemorySegment output, int outputBytes) throws Throwable {
        long templateAddress = probeAddress(templates, false, "templates");
        long recordAddress = probeAddress(records, false, "records");
        long outputAddress = probeAddress(output, true, "output");
        boolean validShape = templateWords >= 0 && templateWords <= BlockMeshPacking.MAX_TEMPLATES * BlockMeshPacking.TEMPLATE_WORDS
                && recordWords >= 0 && recordWords <= BlockMeshPacking.MAX_QUADS * BlockMeshPacking.RECORD_WORDS
                && templateWords % BlockMeshPacking.TEMPLATE_WORDS == 0
                && recordWords % BlockMeshPacking.RECORD_WORDS == 0 && outputBytes >= 0;
        int bytes = validShape ? (recordWords / BlockMeshPacking.RECORD_WORDS) * BlockMeshPacking.QUAD_BYTES : 0;
        if (validShape && templateWords != 0 && recordWords != 0 && templateAddress != 0
                && recordAddress != 0 && outputAddress != 0 && outputBytes >= bytes) {
            requireProbeExtent(templates, (long) templateWords * Integer.BYTES, Integer.BYTES, "templates");
            requireProbeExtent(records, (long) recordWords * Integer.BYTES, Integer.BYTES, "records");
            requireProbeExtent(output, bytes, 1, "output");
        }
        return (int) pack.invokeExact(templates, templateWords, records, recordWords, output, outputBytes);
    }

    private static long probeAddress(MemorySegment segment, boolean writable, String name) {
        if (segment == null) throw new NullPointerException(name);
        if (!segment.isNative()) throw new IllegalArgumentException("Native probe " + name + " must be native memory");
        if (!segment.scope().isAlive()) throw new IllegalStateException("Native probe " + name + " is closed");
        if (!segment.isAccessibleBy(Thread.currentThread())) {
            throw new WrongThreadException("Native probe " + name + " is not accessible on this thread");
        }
        long address = segment.address();
        if (address != 0 && writable && segment.isReadOnly()) {
            throw new IllegalArgumentException("Native probe " + name + " must be writable");
        }
        return address;
    }

    private static void requireProbeExtent(MemorySegment segment, long bytes, int alignment, String name) {
        if (segment.byteSize() < bytes) {
            throw new IllegalArgumentException("Native probe " + name + " needs " + bytes + " accessible bytes");
        }
        // C reads inputs through uint32_t pointers; byte-wise output deliberately permits unaligned addresses.
        if ((segment.address() & (alignment - 1L)) != 0) {
            throw new IllegalArgumentException("Native probe " + name + " needs " + alignment + "-byte alignment");
        }
    }

    @Override public void close() {
        // Caller must have joined every user of this library before closing.
        libraryArena.close();
    }
}
