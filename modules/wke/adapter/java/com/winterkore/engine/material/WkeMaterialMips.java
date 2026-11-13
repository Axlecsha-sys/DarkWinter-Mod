// CPU material adapter: shared WKE filtering, immutable reload snapshots, no GPU initialization.
package com.winterkore.engine.material;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Transparency;
import com.winterkore.engine.WkeNativeLibraries;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class WkeMaterialMips {
    public static final String NEEDLES = "darkwinter:block/test_spruce_needles";
    public static final String BRANCH = "darkwinter:block/spruce_branch";
    private static final Set<String> MANAGED = Set.of(NEEDLES, BRANCH);
    private static final Logger LOG = LoggerFactory.getLogger("WinterKore/Materials");
    private static final Map<String, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final AtomicLong REVISION = new AtomicLong();
    private static final LongAdder NATIVE_CALLS = new LongAdder(), FALLBACK_CALLS = new LongAdder();
    private static volatile NativeKernel nativeKernel;
    private static volatile boolean attempted;
    private static volatile String status = "not initialized";

    private WkeMaterialMips() { }

    /** Array components are defensive copies; snapshots never retain a world, GPU object or NativeImage. */
    public record Snapshot(String resource, int width, int height, int levels,
                           int[] rgbaWords, double[] coverage, long revision) {
        public Snapshot {
            rgbaWords = rgbaWords.clone();
            coverage = coverage.clone();
        }
        @Override public int[] rgbaWords() { return rgbaWords.clone(); }
        @Override public double[] coverage() { return coverage.clone(); }
        public int offset(int level) {
            if (level < 0 || level >= levels) throw new IndexOutOfBoundsException(level);
            int offset = 0;
            for (int i = 0; i < level; i++) offset += Math.max(1, width >> i) * Math.max(1, height >> i);
            return offset;
        }
    }

    public static long revision() { return REVISION.get(); }
    public static Map<String, Snapshot> snapshots() { return Map.copyOf(SNAPSHOTS); }
    public static long nativeCalls() { return NATIVE_CALLS.sum(); }
    public static long fallbackCalls() { return FALLBACK_CALLS.sum(); }
    public static String status() { return status; }

    /** Returns null to preserve the host path for unrelated or explicitly customized materials. */
    public static NativeImage[] generate(Identifier name, NativeImage[] current, int requested,
                                         MipmapStrategy strategy, float alphaBias, Transparency transparency) {
        String key = name.toString();
        if (!MANAGED.contains(key)) return null;
        if (current.length == 0 || requested < 0 || requested > 15 || alphaBias != 0f
                || strategy != MipmapStrategy.AUTO && strategy != MipmapStrategy.CUTOUT
                || !transparency.hasTransparent() || current[0].getWidth() != current[0].getHeight()) {
            remove(key);
            return null;
        }
        NativeKernel kernel = kernel();
        if (kernel == null) { FALLBACK_CALLS.increment(); remove(key); return null; }
        int width = current[0].getWidth(), height = current[0].getHeight();
        NativeImage[] prepared = null;
        try {
            if (width <= 0 || height <= 0 || width > 16384 || height > 16384
                    || (long) width * height > 32L * 1024 * 1024)
                throw new IllegalArgumentException("Material source exceeds the CPU mip budget");
            int[] source = new int[Math.multiplyExact(width, height)];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
                source[y * width + x] = swapRedBlue(current[0].getPixel(x, y));
            Pixels pixels = kernel.build(source, width, height);
            if (requested >= pixels.levels) throw new IllegalArgumentException("Requested material mip dimensions");
            prepared = new NativeImage[requested + 1];
            prepared[0] = current[0]; // SpriteContents.originalImage retains this exact ownership.
            int offset = width * height;
            for (int level = 1; level <= requested; level++) {
                int w = Math.max(1, width >> level), h = Math.max(1, height >> level);
                NativeImage image = new NativeImage(w, h, false);
                prepared[level] = image;
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
                    image.setPixel(x, y, swapRedBlue(pixels.words[offset + y * w + x]));
                offset += w * h;
            }
            // Technical RGB padding only: alpha and every visible base texel are unchanged.
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
                current[0].setPixel(x, y, swapRedBlue(pixels.words[y * width + x]));
            for (int level = 1; level < current.length; level++) current[level].close();
            publish(key, width, height, pixels);
            return prepared;
        } catch (Throwable failure) {
            if (prepared != null) for (int level = 1; level < prepared.length; level++)
                if (prepared[level] != null) prepared[level].close();
            if (failure instanceof Error error && !(error instanceof LinkageError)) throw error;
            FALLBACK_CALLS.increment();
            remove(key);
            LOG.warn("WKE material fallback for {}: {}", key, failure.toString());
            return null;
        }
    }

    private static void publish(String key, int width, int height, Pixels pixels) {
        SNAPSHOTS.compute(key, (ignored, previous) -> {
            if (previous != null && previous.width == width && previous.height == height
                    && Arrays.equals(previous.rgbaWords, pixels.words)) return previous;
            return new Snapshot(key, width, height, pixels.levels, pixels.words, pixels.coverage, REVISION.incrementAndGet());
        });
    }

    private static void remove(String key) {
        if (SNAPSHOTS.remove(key) != null) REVISION.incrementAndGet();
    }

    private static int swapRedBlue(int value) {
        return (value & 0xff00ff00) | ((value >>> 16) & 255) | ((value & 255) << 16);
    }

    private record Pixels(int levels, int[] words, double[] coverage) { }

    private static synchronized NativeKernel kernel() {
        if (attempted) return nativeKernel;
        attempted = true;
        if (!WkeMaterialMips.class.getModule().isNativeAccessEnabled()) {
            status = "native access unavailable; host material path retained";
            return null;
        }
        try {
            nativeKernel = new NativeKernel(WkeNativeLibraries.resolve("winterkore.material.library", "wke_render.dll"));
            status = "CPU ABI1; linear premultiplied raw chain, bilinear cutout coverage, RGB edge padding";
            LOG.info("WKE material kernel ready: {}", status);
        } catch (Throwable failure) {
            if (failure instanceof Error error && !(error instanceof LinkageError)) throw error;
            status = failure.toString();
            LOG.warn("WKE material kernel unavailable; host path retained: {}", status);
        }
        return nativeKernel;
    }

    private static final class NativeKernel {
        // The automatic shared arena keeps the CPU-only library available to parallel resource workers.
        final Arena libraryArena = Arena.ofAuto();
        final MethodHandle words, build;

        NativeKernel(Path path) throws Throwable {
            SymbolLookup library = SymbolLookup.libraryLookup(path, libraryArena);
            Linker linker = Linker.nativeLinker();
            MethodHandle version = linker.downcallHandle(library.find("wke_material_api_version").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
            if ((int) version.invokeExact() != 1) throw new IllegalStateException("WKE material ABI mismatch");
            words = linker.downcallHandle(library.find("wke_material_mip_words").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            build = linker.downcallHandle(library.find("wke_material_build_mips").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        }

        Pixels build(int[] rgba, int width, int height) throws Throwable {
            int levels = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
            long count = (long) words.invokeExact(width, height, levels);
            if (count <= 0 || count > 32L * 1024 * 1024) throw new IllegalArgumentException("Material mip budget");
            try (Arena scratch = Arena.ofConfined()) {
                MemorySegment source = scratch.allocateFrom(ValueLayout.JAVA_INT, rgba);
                MemorySegment output = scratch.allocate(Math.multiplyExact(count, 4L), 4);
                MemorySegment coverage = scratch.allocate((long) levels * 8, 8);
                int code = (int) build.invokeExact(source, (long) rgba.length, width, height, levels, .5f, 3,
                        output, count, coverage, (long) levels);
                if (code != 0) throw new IllegalStateException("WKE material kernel error " + code);
                NATIVE_CALLS.increment();
                return new Pixels(levels, output.toArray(ValueLayout.JAVA_INT), coverage.toArray(ValueLayout.JAVA_DOUBLE));
            }
        }
    }
}
