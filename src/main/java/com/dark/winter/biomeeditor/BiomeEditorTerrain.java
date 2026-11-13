// Builds serializable Minecraft density graphs for the editor; generation remains in the native Java worldgen pipeline.
package com.dark.winter.biomeeditor;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Stream;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DistanceMetric;
import net.minecraft.world.level.levelgen.densityfunction.TilingMode;
import net.minecraft.world.level.levelgen.densityfunction.op.SplineFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

public final class BiomeEditorTerrain {
    public static final int MAX_ENCODED_BYTES = 8 * 1024 * 1024;
    public static final int MAX_ENCODED_NODES = 150_000;
    private static final ResourceKey<NormalNoise> BROAD = ResourceKey.create(Registries.NOISE,
            Identifier.fromNamespaceAndPath("darkwinter", "test_gentle_broad_v2"));
    private static final ResourceKey<NormalNoise> DETAIL = ResourceKey.create(Registries.NOISE,
            Identifier.fromNamespaceAndPath("darkwinter", "test_gentle_detail_v2"));

    private BiomeEditorTerrain() {}

    public record Prepared(NoiseBasedChunkGenerator generator, HolderLookup.Provider registries,
            Map<Identifier, JsonElement> densityFunctions, JsonElement settings) {
        public Prepared { densityFunctions = Collections.unmodifiableMap(new LinkedHashMap<>(densityFunctions)); }
    }

    public static NoiseBasedChunkGenerator create(HolderLookup.Provider registries, ChunkGenerator source,
            BiomeEditorProfile profile) {
        NoiseBasedChunkGenerator result = build(registries, source, profile, null);
        encodedSettings(registries, result);
        return result;
    }

    /** Keeps shared density stages as native registry references instead of repeating inline operation history. */
    public static Prepared prepare(HolderLookup.Provider registries, ChunkGenerator source, BiomeEditorProfile profile) {
        Objects.requireNonNull(registries); Objects.requireNonNull(profile);
        if (!(source instanceof NoiseBasedChunkGenerator noise)) throw new IllegalArgumentException("A native noise generator is required");
        String sourceSettings = NoiseGeneratorSettings.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE),
                noise.generatorSettings()).getOrThrow().toString();
        StageGraph stages = new StageGraph(registries, stagePrefix(profile.canonicalValidatedJson() + "\n" + sourceSettings));
        NoiseBasedChunkGenerator result = build(registries, source, profile, stages);
        stages.freeze();
        var serialization = stages.registries().createSerializationContext(JsonOps.INSTANCE);
        JsonElement settings = NoiseGeneratorSettings.CODEC.encodeStart(serialization, result.generatorSettings()).getOrThrow();
        int[] nodes = {0};
        long bytes = validateEncoded(settings, nodes);
        Map<Identifier, JsonElement> functions = new LinkedHashMap<>();
        for (var stage : stages.functions().entrySet()) {
            JsonElement encoded = DensityFunctions.DIRECT_CODEC.encodeStart(serialization, stage.getValue()).getOrThrow();
            bytes += validateEncoded(encoded, nodes);
            if (bytes > 16 * 1024 * 1024) throw new IllegalArgumentException("Native density stages exceed the 16 MiB export limit");
            functions.put(stage.getKey(), encoded);
        }
        return new Prepared(result, stages.registries(), functions, settings);
    }

    private static NoiseBasedChunkGenerator build(HolderLookup.Provider registries, ChunkGenerator source,
            BiomeEditorProfile profile, StageGraph stages) {
        Objects.requireNonNull(registries); Objects.requireNonNull(profile);
        if (!(source instanceof NoiseBasedChunkGenerator noise) || !(source.getBiomeSource() instanceof FixedBiomeSource fixed)) {
            throw new IllegalArgumentException("The editor requires a native noise generator with the selected fixed biome");
        }
        if (!fixed.getNoiseBiome(0, 0, 0).is(ResourceKey.create(Registries.BIOME, Identifier.parse(profile.biome())))) {
            throw new IllegalArgumentException("The selected fixed biome does not match the profile");
        }
        NoiseGeneratorSettings base = noise.generatorSettings().value();
        int minY = base.noiseSettings().minY(), maxY = minY + base.noiseSettings().height() - 1;
        requireHeight(profile.terrain().baseHeight(), minY, maxY, "baseHeight");
        var noises = registries.lookupOrThrow(Registries.NOISE);
        var t = profile.terrain();
        DensityFunction broad = DensityFunctions.noise(noises.getOrThrow(BROAD), t.noiseScale(), 0).clamp(-1, 1);
        DensityFunction detail = DensityFunctions.noise(noises.getOrThrow(DETAIL), t.noiseScale() / 1.5, 0).clamp(-1, 1);
        DensityFunction broadSurface = broad.mul(t.broadAmplitude()).add(t.baseHeight());
        if (stages != null) broadSurface = stages.store("broad", broadSurface);
        DensityFunction surface = broadSurface.add(detail.mul(t.detailAmplitude()));
        int surfaceOperations = 0;
        for (var op : profile.operations()) {
            if (!op.enabled() || op.strength() == 0 || !op.terrain() || op.kind().equals("cave") || op.kind().equals("tunnel")) continue;
            DensityFunction distance = op.kind().equals("river") ? pathDistance(op) : pointDistance(op.x(), 0, op.z(), op.radius(), false);
            DensityFunction weight = brush(distance, op.kind().equals("river") ? op.width() * .5f : op.radius(), op.falloff());
            float blend = Math.min(1, op.strength());
            switch (op.kind()) {
                case "raise" -> surface = surface.add(weight.mul(op.strength()));
                case "lower" -> surface = surface.sub(weight.mul(op.strength()));
                case "mountain" -> {
                    requireHeight(op.height(), minY, maxY, "mountain height");
                    // Height is a summit offset from the procedural base, rather than a second copy of the previous graph.
                    float amplitude = Math.max(op.strength(), op.height() - t.baseHeight());
                    surface = surface.add(brush(distance, op.radius(), 1).mul(amplitude));
                }
                case "flatten", "plains", "plateau" -> {
                    requireHeight(op.height(), minY, maxY, "target height");
                    surface = DensityFunctions.lerp(weight.mul(blend), surface, DensityFunctions.constant(op.height()));
                }
                case "smooth" -> {
                    // Remove fine procedural relief locally; preserve the broad seeded terrain.
                    surface = DensityFunctions.lerp(weight.mul(blend), surface, broadSurface);
                }
                case "river", "lake" -> {
                    requireHeight(op.waterLevel(), minY, maxY, "waterLevel");
                    float bed = Math.max(minY, op.waterLevel() - Math.min(maxY - minY, op.depth() * op.strength()));
                    float r = op.kind().equals("river") ? op.width() * .5f : op.radius();
                    // The outer bank tends to the world ceiling, so min() never lowers unrelated columns.
                    float shoulder = Math.max(.05f, Math.min(.95f, 1 - op.falloff()));
                    DensityFunction basin = DensityFunctions.spline(CubicSpline.builder(new SplineFunction.Coordinate(distance.div(r)))
                            .addPoint(0, bed).addPoint(shoulder, op.waterLevel()).addPoint(1, maxY).build());
                    surface = DensityFunctions.min(surface, basin);
                }
                default -> throw new IllegalArgumentException("Unsupported terrain operation: " + op.kind());
            }
            if (stages != null && ++surfaceOperations % 8 == 0) surface = stages.store("surface_" + surfaceOperations / 8, surface);
        }
        surface = DensityFunctions.cache(surface.clamp(minY, maxY));
        if (stages != null) surface = stages.store("surface", surface);
        DensityFunction y = DensityFunctions.yClampedGradient(minY, maxY, minY, maxY);
        DensityFunction relief = surface.sub(y).mul(.125f).add(DensityFunctions.beardifier());
        // The cave transition follows this column's surface; a distant brush cannot change local caves.
        DensityFunction density = DensityFunctions.lerp(caveBlend(surface, y, minY),
                base.noiseRouter().finalDensity(), relief);
        if (stages != null) density = stages.store("base_density", density);
        int carveOperations = 0;
        // Carves apply to the final solid field and cannot fill an existing native cave.
        for (var op : profile.operations()) {
            if (!op.enabled() || op.strength() == 0 || (!op.kind().equals("cave") && !op.kind().equals("tunnel"))) continue;
            requireHeight((float) op.y(), minY, maxY, "cave/tunnel y");
            float horizontal = (op.kind().equals("tunnel") ? op.width() * .5f : op.radius()) * Math.min(1, op.strength());
            float vertical = op.depth() > 0 ? op.depth() * Math.min(1, op.strength()) : horizontal;
            DensityFunction horizontalDistance = op.kind().equals("tunnel") ? pathDistance(op)
                    : pointDistance(op.x(), 0, op.z(), horizontal, false);
            DensityFunction dy = relative(Direction.Axis.Y, op.y(), vertical + 2).mul(horizontal / vertical);
            DensityFunction sdf = horizontalDistance.square().add(dy.square()).sqrt().sub(horizontal).mul(.125f);
            density = DensityFunctions.min(density, sdf);
            if (stages != null && ++carveOperations % 8 == 0) density = stages.store("carve_" + carveOperations / 8, density);
        }
        if (stages != null) density = stages.store("density", density);
        var old = base.noiseRouter();
        NoiseRouter router = new NoiseRouter(old.temperature(), old.vegetation(), old.continents(), old.erosion(),
                old.depth(), old.ridges(), surface, density);
        final DensityFunction finalSurface = surface, finalDensity = density;
        var aquifers = base.aquifers().map(a -> new Aquifer.Config(a.barrierNoise(), a.fluidLevelFloodednessNoise(),
                a.fluidLevelSpreadNoise(), a.lavaNoise(), a.exclusion(), finalSurface));
        var debug = new NoiseGeneratorSettings.DebugFunctions(base.debugFunctions().functions().stream()
                .map(entry -> switch (entry.label()) {
                    case "N" -> new NoiseGeneratorSettings.DebugFunctionEntry(entry.label(), finalDensity);
                    case "PS" -> new NoiseGeneratorSettings.DebugFunctionEntry(entry.label(), finalSurface);
                    default -> entry;
                }).toList());
        NoiseGeneratorSettings settings = new NoiseGeneratorSettings(base.noiseSettings(), base.defaultBlock(),
                base.defaultFluid(), router, base.materialRule(), base.spawnTarget(), base.seaLevel(),
                base.disableMobGeneration(), aquifers, base.useLegacyRandomSource(), debug);
        NoiseBasedChunkGenerator result = new NoiseBasedChunkGenerator(source.getBiomeSource(), Holder.direct(settings));
        return result;
    }


    static DensityFunction caveBlend(DensityFunction surface, DensityFunction y, int minY) {
        DensityFunction lower = DensityFunctions.cache(DensityFunctions.max(DensityFunctions.constant(minY),
                DensityFunctions.min(DensityFunctions.constant(32), surface.sub(32))));
        DensityFunction upper = DensityFunctions.cache(DensityFunctions.max(lower.add(1),
                DensityFunctions.min(DensityFunctions.constant(56), surface.sub(8))));
        // Width is at least one block. Multiplying by its reciprocal matches the stock gradient's float arithmetic.
        DensityFunction inverseWidth = DensityFunctions.cache(upper.sub(lower).reciprocal());
        return y.sub(lower).mul(inverseWidth).clamp(0, 1);
    }

    /** Uses the native holder codec, including inline settings and registry-backed source noise references. */
    public static JsonElement encodedSettings(HolderLookup.Provider registries, NoiseBasedChunkGenerator generator) {
        JsonElement encoded = NoiseGeneratorSettings.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE),
                generator.generatorSettings()).getOrThrow();
        checkTree(encoded, 0, new int[1]);
        if (encoded.toString().getBytes(StandardCharsets.UTF_8).length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Native terrain settings exceed the 8 MiB export limit");
        }
        return encoded;
    }

    private static DensityFunction brush(DensityFunction distance, float radius, float falloff) {
        float inner = Math.min(.999f, Math.max(0, 1 - falloff));
        // Zero endpoint derivatives give exact smoothstep without serializing three copies of the distance graph.
        return DensityFunctions.spline(CubicSpline.builder(new SplineFunction.Coordinate(distance.div(radius)))
                .addPoint(inner, 1).addPoint(1, 0).build());
    }

    private static DensityFunction pointDistance(double x, double y, double z, float radius, boolean spatial) {
        if (x == Math.rint(x) && y == Math.rint(y) && z == Math.rint(z)) {
            DensityFunction d = DensityFunctions.distanceToPoint(new Vec3i((int) x, (int) y, (int) z), DistanceMetric.EUCLIDEAN);
            return spatial ? d : DensityFunctions.sliceY(d, (int) y);
        }
        DensityFunction dx = relative(Direction.Axis.X, x, radius + 2);
        DensityFunction dz = relative(Direction.Axis.Z, z, radius + 2);
        DensityFunction squared = dx.square().add(dz.square());
        return (spatial ? squared.add(relative(Direction.Axis.Y, y, radius + 2).square()) : squared).sqrt();
    }

    private static DensityFunction relative(Direction.Axis axis, double origin, double extent) {
        int center = (int) Math.floor(origin), reach = (int) Math.ceil(Math.max(2, extent));
        float fraction = (float) (origin - center);
        return DensityFunctions.gradient(axis, TilingMode.CLAMP_TO_EDGE, center - reach, center + reach,
                -reach - fraction, reach - fraction);
    }

    private static DensityFunction pathDistance(BiomeEditorProfile.Operation op) {
        List<BiomeEditorProfile.Point> p = op.points();
        List<DensityFunction> distances = new ArrayList<>();
        for (int i = 0; i + 1 < p.size(); i++) {
            var a = p.get(i); var b = p.get(i + 1);
            var before = p.get(Math.max(0, i - 1)); var after = p.get(Math.min(p.size() - 1, i + 2));
            var previous = a;
            for (int step = 1; step <= 4; step++) {
                double t = step * .25;
                var next = new BiomeEditorProfile.Point(curve(before.x(), a.x(), b.x(), after.x(), t),
                        curve(before.z(), a.z(), b.z(), after.z(), t));
                distances.add(segmentDistance(previous, next, op.width() * .5f));
                previous = next;
            }
        }
        return minimum(distances, 0, distances.size());
    }

    private static double curve(double before, double a, double b, double after, double t) {
        if (t == 1) return b;
        double v = .5 * ((2 * a) + (-before + b) * t + (2 * before - 5 * a + 4 * b - after) * t * t
                + (-before + 3 * a - 3 * b + after) * t * t * t);
        // Keep the native graph footprint inside the drawn control polygon's per-span bounds.
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), v));
    }

    private static DensityFunction segmentDistance(BiomeEditorProfile.Point a, BiomeEditorProfile.Point b, float radius) {
        double dx = b.x() - a.x(), dz = b.z() - a.z(), length = Math.hypot(dx, dz);
        if (length < 1e-6) return pointDistance(a.x(), 0, a.z(), radius, false);
        if (length > 32768) throw new IllegalArgumentException("A native river/tunnel segment exceeds 32768 blocks");
        float ux = (float) (dx / length), uz = (float) (dz / length);
        DensityFunction px = relative(Direction.Axis.X, a.x(), length + radius + 2);
        DensityFunction pz = relative(Direction.Axis.Z, a.z(), length + radius + 2);
        DensityFunction projected = DensityFunctions.cache(px.mul(ux).add(pz.mul(uz)).clamp(0, (float) length));
        return px.sub(projected.mul(ux)).square().add(pz.sub(projected.mul(uz)).square()).sqrt();
    }

    private static DensityFunction minimum(List<DensityFunction> values, int from, int to) {
        if (to - from == 1) return values.get(from);
        if (from == to) throw new IllegalArgumentException("Empty native path");
        int middle = (from + to) >>> 1;
        return DensityFunctions.min(minimum(values, from, middle), minimum(values, middle, to));
    }


    /** Matches the float coordinate and capsule operations used in the serialized native graph. */
    public static float horizontalDistance(BiomeEditorProfile.Operation op, int x, int z) {
        if (!op.kind().equals("river") && !op.kind().equals("tunnel")) {
            return pointDistanceValue(op.x(), op.z(), op.radius(), x, z);
        }
        var points = op.points();
        float best = Float.POSITIVE_INFINITY;
        for (int i = 0; i + 1 < points.size(); i++) {
            var a = points.get(i); var b = points.get(i + 1);
            var before = points.get(Math.max(0, i - 1)); var after = points.get(Math.min(points.size() - 1, i + 2));
            double previousX = a.x(), previousZ = a.z();
            for (int step = 1; step <= 4; step++) {
                double progress = step * .25;
                double nextX = curve(before.x(), a.x(), b.x(), after.x(), progress);
                double nextZ = curve(before.z(), a.z(), b.z(), after.z(), progress);
                double dx = nextX - previousX, dz = nextZ - previousZ, length = Math.hypot(dx, dz);
                float distance;
                if (length < 1e-6) distance = pointDistanceValue(previousX, previousZ, op.width() * .5f, x, z);
                else {
                    float ux = (float) (dx / length), uz = (float) (dz / length);
                    float px = relativeValue(previousX, length + op.width() * .5f + 2, x);
                    float pz = relativeValue(previousZ, length + op.width() * .5f + 2, z);
                    float projected = Math.clamp(px * ux + pz * uz, 0f, (float) length);
                    float rx = px - projected * ux, rz = pz - projected * uz;
                    distance = (float) Math.sqrt(rx * rx + rz * rz);
                }
                best = Math.min(best, distance);
                previousX = nextX; previousZ = nextZ;
            }
        }
        return best;
    }

    public static float horizontalRadius(BiomeEditorProfile.Operation op) {
        return op.kind().equals("river") || op.kind().equals("tunnel") ? op.width() * .5f : op.radius();
    }

    private static float pointDistanceValue(double centerX, double centerZ, float radius, int x, int z) {
        float dx, dz;
        if (centerX == Math.rint(centerX) && centerZ == Math.rint(centerZ)) {
            dx = (int) centerX - x; dz = (int) centerZ - z;
        } else {
            dx = relativeValue(centerX, radius + 2, x); dz = relativeValue(centerZ, radius + 2, z);
        }
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    private static float relativeValue(double origin, double extent, int coordinate) {
        int center = (int) Math.floor(origin), reach = (int) Math.ceil(Math.max(2, extent));
        float fraction = (float) (origin - center);
        float from = -reach - fraction, to = reach - fraction;
        float factor = (to - from) / (reach * 2);
        return from + (Math.clamp(coordinate, center - reach, center + reach) - (center - reach)) * factor;
    }

    private static void requireHeight(float value, int minY, int maxY, String name) {
        if (!Float.isFinite(value) || value < minY || value > maxY) {
            throw new IllegalArgumentException(name + " must stay inside native build height " + minY + ".." + maxY);
        }
    }


    private static int validateEncoded(JsonElement encoded, int[] nodes) {
        checkTree(encoded, 0, nodes);
        int bytes = encoded.toString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_ENCODED_BYTES) throw new IllegalArgumentException("Native density file exceeds the 8 MiB export limit");
        return bytes;
    }

    private static String stagePrefix(String input) {
        try {
            return "stage_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    /** A call-scoped native registry; existing registry owners remain valid and no global registry is modified. */
    static final class StageGraph {
        private final MappedRegistry<DensityFunction> local = new MappedRegistry<>(Registries.DENSITY_FUNCTION, Lifecycle.stable(), true);
        private final LinkedHashMap<Identifier, DensityFunction> functions = new LinkedHashMap<>();
        private final HolderLookup.Provider registries;
        private final String prefix;
        private boolean frozen;

        StageGraph(HolderLookup.Provider base, String prefix) {
            if (!prefix.matches("[a-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid density stage prefix");
            this.prefix = prefix;
            var parent = base.lookupOrThrow(Registries.DENSITY_FUNCTION);
            HolderLookup.RegistryLookup<DensityFunction> merged = new HolderLookup.RegistryLookup.Delegate<>() {
                @Override public HolderLookup.RegistryLookup<DensityFunction> parent() { return parent; }
                @Override public Optional<Holder.Reference<DensityFunction>> get(ResourceKey<DensityFunction> key) {
                    return local.get(key).or(() -> parent.get(key));
                }
                @Override public Stream<Holder.Reference<DensityFunction>> listElements() {
                    return Stream.concat(parent.listElements().filter(h -> !functions.containsKey(h.key().identifier())), local.listElements());
                }
                @Override public boolean canSerialize(HolderOwner<DensityFunction> owner) {
                    return local.canSerialize(owner) || parent.canSerialize(owner);
                }
            };
            List<HolderLookup.RegistryLookup<?>> lookups = new ArrayList<>();
            base.listRegistries().filter(r -> !r.key().equals(Registries.DENSITY_FUNCTION)).forEach(lookups::add);
            lookups.add(merged);
            registries = HolderLookup.Provider.create(lookups.stream());
        }

        DensityFunction store(String name, DensityFunction value) {
            if (frozen) throw new IllegalStateException("Density stages are frozen");
            if (value instanceof DensityFunctions.HolderHolder) return value;
            if (functions.size() >= 64) throw new IllegalArgumentException("Too many native density stages");
            Identifier id = Identifier.fromNamespaceAndPath("darkwinter_editor", prefix + "/" + name);
            local.createIntrusiveHolder(value);
            Holder.Reference<DensityFunction> holder = local.register(ResourceKey.create(Registries.DENSITY_FUNCTION, id),
                    value, RegistrationInfo.BUILT_IN);
            functions.put(id, value);
            return new DensityFunctions.HolderHolder(holder);
        }

        void freeze() { local.freeze(); frozen = true; }
        HolderLookup.Provider registries() { return registries; }
        Map<Identifier, DensityFunction> functions() { return Collections.unmodifiableMap(functions); }
    }

    private static void checkTree(JsonElement value, int depth, int[] nodes) {
        if (++nodes[0] > MAX_ENCODED_NODES || depth > 768) throw new IllegalArgumentException("Native terrain graph exceeds its complexity limit");
        if (value.isJsonObject()) for (var entry : value.getAsJsonObject().entrySet()) checkTree(entry.getValue(), depth + 1, nodes);
        else if (value.isJsonArray()) for (JsonElement e : value.getAsJsonArray()) checkTree(e, depth + 1, nodes);
    }
}
