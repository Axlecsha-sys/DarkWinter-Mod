// Native ABI binding only; WKE borrows the host Vulkan device and never creates a window or swapchain.
package com.winterkore.engine.render;

import com.winterkore.engine.WkeNativeLibraries;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

public final class WkeVulkanNative {
    public static final int ABI_VERSION = 1, PACKET_BYTES = 128, STATS_WORDS = 16;
    public static final int OK = 0, INTERNAL_ERROR = -5;
    private WkeVulkanNative() { }

    private static final class Library {
        final Arena lifetime = Arena.ofShared();
        MethodHandle attach, create, destroy, record, stats, detach;
        String failure = "";
        Library() {
            try {
                var path = WkeNativeLibraries.resolve("winterkore.renderer.library", "wke_render.dll");
                SymbolLookup symbols = SymbolLookup.libraryLookup(path, lifetime);
                Linker linker = Linker.nativeLinker();
                attach = linker.downcallHandle(symbols.findOrThrow("wke_vk_attach"),
                        FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS));
                create = linker.downcallHandle(symbols.findOrThrow("wke_vk_create_graphics"),
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));
                destroy = linker.downcallHandle(symbols.findOrThrow("wke_vk_destroy_graphics"),
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG));
                record = linker.downcallHandle(symbols.findOrThrow("wke_vk_record_terrain"),
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
                stats = linker.downcallHandle(symbols.findOrThrow("wke_vk_copy_stats"),
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT));
                detach = linker.downcallHandle(symbols.findOrThrow("wke_vk_detach"),
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
            } catch (Throwable problem) {
                failure = problem.getClass().getSimpleName() + ": " + problem.getMessage();
                lifetime.close();
            }
        }
    }
    private static final class Holder { static final Library LIBRARY = new Library(); }
    public static boolean available() { return Holder.LIBRARY.failure.isEmpty(); }
    public static String failureReason() { return Holder.LIBRARY.failure; }
    private static Library library() {
        Library lib = Holder.LIBRARY;
        if (!lib.failure.isEmpty()) throw new IllegalStateException("WKE Vulkan unavailable: " + lib.failure);
        return lib;
    }
    private static IllegalStateException failure(String operation, Throwable problem) {
        if (problem instanceof IllegalStateException state) return state;
        return new IllegalStateException("WKE Vulkan " + operation + " failed", problem);
    }
    private static void check(int code, String operation) {
        if (code != OK) throw new IllegalStateException("WKE Vulkan " + operation + " result=" + code);
    }
    public static long attach(long device, long getDeviceProcAddr) {
        if (device == 0 || getDeviceProcAddr == 0) throw new IllegalArgumentException("Null Vulkan host handle");
        Library lib = library();
        try (Arena temporary = Arena.ofConfined()) {
            MemorySegment out = temporary.allocate(JAVA_LONG);
            int code = (int)lib.attach.invokeExact(ABI_VERSION, device, getDeviceProcAddr, out);
            check(code, "attach");
            long context = out.get(JAVA_LONG, 0);
            if (context == 0) throw new IllegalStateException("WKE returned a null Vulkan context");
            return context;
        } catch (Throwable problem) { throw failure("attach", problem); }
    }
    public static long createGraphics(long context, long cache, long createInfoAddress) {
        if (context == 0 || createInfoAddress == 0) throw new IllegalArgumentException("Null pipeline create argument");
        Library lib = library();
        try (Arena temporary = Arena.ofConfined()) {
            MemorySegment out = temporary.allocate(JAVA_LONG);
            MemorySegment info = MemorySegment.ofAddress(createInfoAddress);
            int code = (int)lib.create.invokeExact(context, cache, info, out);
            check(code, "create graphics");
            long pipeline = out.get(JAVA_LONG, 0);
            if (pipeline == 0) throw new IllegalStateException("WKE returned a null pipeline");
            return pipeline;
        } catch (Throwable problem) { throw failure("create graphics", problem); }
    }
    public static void destroyGraphics(long context, long pipeline) {
        Library lib = library();
        try { check((int)lib.destroy.invokeExact(context, pipeline), "destroy graphics"); }
        catch (Throwable problem) { throw failure("destroy graphics", problem); }
    }
    /** Native prevalidates every packet before issuing the first command. Nonzero means no draw was recorded. */
    public static int recordTerrain(long context, long commandBuffer, MemorySegment packets, int count) {
        if (context == 0 || commandBuffer == 0 || count <= 0 || count > 65536
                || packets == null || !packets.isNative() || packets.byteSize() < (long)count * PACKET_BYTES)
            return -1;
        Library lib = library();
        try { return (int)lib.record.invokeExact(context, commandBuffer, packets, count); }
        catch (Throwable problem) { throw failure("record terrain", problem); }
    }
    public static long[] copyStats(long context) {
        Library lib = library();
        try (Arena temporary = Arena.ofConfined()) {
            MemorySegment words = temporary.allocate((long)STATS_WORDS * Long.BYTES, Long.BYTES);
            check((int)lib.stats.invokeExact(context, words, STATS_WORDS), "copy statistics");
            return words.toArray(JAVA_LONG);
        } catch (Throwable problem) { throw failure("copy statistics", problem); }
    }
    /** Only after all native pipelines have been retired and the host has drained its GPU work. */
    public static void detach(long context) {
        Library lib = library();
        try { check((int)lib.detach.invokeExact(context), "detach"); }
        catch (Throwable problem) { throw failure("detach", problem); }
    }
}
