// Exports editor profiles as bounded native Minecraft datapacks without touching existing worlds.
package com.dark.winter.biomeeditor;

import com.google.gson.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

public final class BiomeEditorPack {
    public record Result(Path directory, String biomeId, String presetId, JsonObject manifest) {}
    public static final int MAX_FILES = 128, MAX_FILE_BYTES = 8 * 1024 * 1024, MAX_TOTAL_BYTES = 16 * 1024 * 1024;
    private static final String NAMESPACE = "darkwinter_editor";
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private BiomeEditorPack() {}

    public static Result write(Path directory, HolderLookup.Provider registries, BiomeEditorProfile profile) throws IOException {
        Objects.requireNonNull(directory); Objects.requireNonNull(registries); Objects.requireNonNull(profile);
        Path target = directory.toAbsolutePath().normalize();
        validateNewTarget(target);
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        Holder<Biome> selected = registries.lookupOrThrow(Registries.BIOME).getOrThrow(
                ResourceKey.create(Registries.BIOME, Identifier.parse(profile.biome())));
        WorldPreset normal = registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value();
        var stem = normal.overworld().orElseThrow(() -> new IllegalArgumentException("Native normal preset has no overworld"));
        if (!(stem.generator() instanceof NoiseBasedChunkGenerator base)) {
            throw new IllegalArgumentException("Native normal preset is not a noise generator");
        }
        NoiseBasedChunkGenerator fixed = new NoiseBasedChunkGenerator(new FixedBiomeSource(selected), base.generatorSettings());
        BiomeEditorTerrain.Prepared prepared = BiomeEditorTerrain.prepare(registries, fixed, profile);
        ops = prepared.registries().createSerializationContext(JsonOps.INSTANCE);
        JsonElement noise = prepared.settings();
        JsonObject biome = encode(Biome.DIRECT_CODEC, selected.value(), ops).getAsJsonObject();
        JsonObject preset = encode(WorldPreset.DIRECT_CODEC, normal, ops).getAsJsonObject();
        String id = profile.id(), biomeId = NAMESPACE + ":" + id, presetId = biomeId;
        String finishId = NAMESPACE + ":" + id + "_zz_finish";
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        JsonArray changes = new JsonArray();
        for (var density : prepared.densityFunctions().entrySet()) {
            put(files, "data/" + density.getKey().getNamespace() + "/worldgen/density_function/"
                    + density.getKey().getPath() + ".json", density.getValue());
        }
        JsonArray features = features(registries, selected.value(), profile, files, changes, ops);
        int top = GenerationStep.Decoration.TOP_LAYER_MODIFICATION.ordinal();
        while (features.size() <= top) features.add(new JsonArray());
        features.get(top).getAsJsonArray().add(finishId);
        biome.add("features", features);

        BiomeEditorFinishFeature finish = new BiomeEditorFinishFeature(profile.canonicalValidatedJson());
        put(files, data("feature", id + "_zz_finish"), encode(Feature.DIRECT_CODEC, finish, ops));
        JsonObject placedFinish = encode(PlacedFeature.DIRECT_CODEC, new PlacedFeature(Holder.direct(finish), List.of()), ops).getAsJsonObject();
        placedFinish.addProperty("feature", finishId);
        put(files, data("placed_feature", id + "_zz_finish"), placedFinish);
        put(files, data("noise_settings", id), noise);
        put(files, data("biome", id), biome);
        JsonObject generator = preset.getAsJsonObject("dimensions").getAsJsonObject(LevelStem.OVERWORLD.identifier().toString()).getAsJsonObject("generator");
        if (!"minecraft:noise".equals(generator.get("type").getAsString())) throw new IllegalArgumentException("Unexpected native normal generator codec");
        generator.addProperty("settings", biomeId);
        JsonObject biomeSource = new JsonObject(); biomeSource.addProperty("type", "minecraft:fixed"); biomeSource.addProperty("biome", biomeId);
        generator.add("biome_source", biomeSource);
        put(files, data("world_preset", id), preset);
        put(files, "data/minecraft/tags/worldgen/world_preset/normal.json", tag(presetId));
        boolean cold = biome.get("has_precipitation").getAsBoolean() && biome.get("temperature").getAsFloat() <= .15f;
        if (cold) put(files, "data/darkwinter/tags/worldgen/biome/snow_terrain.json", tag(biomeId));
        var version = SharedConstants.getCurrentVersion();
        var format = version.packVersion(PackType.SERVER_DATA);
        PackMetadataSection metadata = new PackMetadataSection(Component.literal(profile.name()), new InclusiveRange<>(format, format));
        JsonObject mcmeta = new JsonObject();
        mcmeta.add("pack", encode(PackMetadataSection.SERVER_TYPE.codec(), metadata, ops));
        put(files, "pack.mcmeta", mcmeta);
        put(files, "darkwinter_editor_profile.json", profile.json());

        JsonObject manifest = new JsonObject();
        manifest.addProperty("schema", 1); manifest.addProperty("name", profile.name());
        manifest.addProperty("biomeId", biomeId); manifest.addProperty("presetId", presetId);
        manifest.addProperty("baseBiome", profile.biome()); manifest.addProperty("basePreset", "minecraft:normal");
        manifest.addProperty("minecraftVersion", version.name()); manifest.addProperty("seed", profile.seedDecimal());
        manifest.addProperty("packMajor", format.major()); manifest.addProperty("packMinor", format.minor());
        manifest.addProperty("requiresDarkWinter", true); manifest.addProperty("existingChunksModified", false);
        manifest.addProperty("profileSource", "darkwinter_editor_profile.json");
        manifest.addProperty("profileSha256", sha(profile.canonicalValidatedJson().getBytes(StandardCharsets.UTF_8)));
        manifest.addProperty("profileSourceFileSha256", sha(files.get("darkwinter_editor_profile.json")));
        manifest.addProperty("nativeNoiseSettingsSha256", sha(files.get(data("noise_settings", id))));
        manifest.addProperty("nativeWorldgenCodec", "minecraft:noise");
        manifest.addProperty("nativeDensityStages", prepared.densityFunctions().size());
        manifest.addProperty("densityScope", "tree count provider");
        manifest.addProperty("snowTerrain", cold); manifest.add("featureChanges", changes);
        JsonObject hashes = new JsonObject(); files.forEach((path, bytes) -> hashes.addProperty(path, sha(bytes)));
        manifest.add("files", hashes); manifest.addProperty("fileCountIncludingManifest", files.size() + 1);
        manifest.addProperty("complete", true);
        put(files, "darkwinter_editor_manifest.json", manifest);
        long total = files.values().stream().mapToLong(a -> a.length).sum();
        if (files.size() > MAX_FILES || total > MAX_TOTAL_BYTES) throw new IOException("Editor datapack exceeds its file/size budget");
        // The trusted worker supplies a fresh owned target. Exclusive creation never overwrites an existing export.
        Files.createDirectory(target);
        for (var file : files.entrySet()) {
            Path output = target.resolve(file.getKey()).normalize();
            if (!output.startsWith(target)) throw new IOException("Datapack path leaves its owned target");
            Files.createDirectories(output.getParent());
            noLinks(output.getParent());
            Files.write(output, file.getValue(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        return new Result(target, biomeId, presetId, manifest.deepCopy());
    }

    private static JsonArray features(HolderLookup.Provider registries, Biome source, BiomeEditorProfile profile,
            Map<String, byte[]> files, JsonArray changes, DynamicOps<JsonElement> ops) throws IOException {
        JsonObject vegetation = profile.json().getAsJsonObject("vegetation");
        boolean trees = vegetation.get("trees").getAsBoolean(), grass = vegetation.get("grass").getAsBoolean();
        boolean bushes = vegetation.get("bushes").getAsBoolean(), decor = vegetation.get("decor").getAsBoolean();
        BigDecimal density = vegetation.get("density").getAsBigDecimal().stripTrailingZeros();
        JsonArray output = new JsonArray();
        int stage = 0, cloneIndex = 0;
        for (var set : source.getGenerationSettings().features()) {
            JsonArray entries = new JsonArray();
            for (Holder<PlacedFeature> holder : set) {
                String original = holder.unwrapKey().map(k -> k.identifier().toString()).orElse("");
                if (original.startsWith(NAMESPACE + ":") && original.endsWith("_zz_finish")) continue;
                String category = category(original, holder.value());
                boolean mixed = original.equals("minecraft:patch_grass_taiga_2");
                boolean keep = switch (category) {
                    case "trees" -> trees;
                    case "grass" -> grass;
                    case "bushes" -> bushes;
                    case "decor" -> decor;
                    case "ground" -> grass || bushes;
                    default -> true;
                };
                if (mixed) keep = grass || bushes;
                if (!keep) { change(changes, original, "removed", category); continue; }
                if (category.equals("trees") && density.compareTo(BigDecimal.ONE) != 0) {
                    if (!holder.value().feature().is(ResourceKey.create(Registries.FEATURE,
                            Identifier.fromNamespaceAndPath("darkwinter", "test_spruce_tree")))) {
                        throw new IllegalArgumentException("Density scaling is not implemented for native tree feature " + original);
                    }
                    JsonObject clone = encode(PlacedFeature.DIRECT_CODEC, holder.value(), ops).getAsJsonObject();
                    scaleCount(clone, density);
                    PlacedFeature.DIRECT_CODEC.parse(ops, clone).getOrThrow();
                    String path = profile.id() + "_trees_" + cloneIndex++;
                    put(files, data("placed_feature", path), clone);
                    entries.add(NAMESPACE + ":" + path); change(changes, original, "scaled_native_count", category);
                    continue;
                }
                if (mixed && (!grass || !bushes)) {
                    JsonObject clone = encode(PlacedFeature.DIRECT_CODEC, holder.value(), ops).getAsJsonObject();
                    JsonObject configured = encode(Feature.DIRECT_CODEC, holder.value().feature().value(), ops).getAsJsonObject();
                    requireAirTarget(clone);
                    filterGrassMix(configured, grass, bushes);
                    Feature.DIRECT_CODEC.parse(ops, configured).getOrThrow();
                    clone.add("feature", configured);
                    PlacedFeature.DIRECT_CODEC.parse(ops, clone).getOrThrow();
                    String path = profile.id() + "_grass_mix_" + cloneIndex++;
                    put(files, data("placed_feature", path), clone);
                    entries.add(NAMESPACE + ":" + path); change(changes, original, "filtered_native_weighted_provider", "grass/bushes");
                    continue;
                }
                if (stage == GenerationStep.Decoration.VEGETAL_DECORATION.ordinal() && category.isEmpty()
                        && (!trees || !grass || !bushes || !decor || density.compareTo(BigDecimal.ONE) != 0)) {
                    throw new IllegalArgumentException("Vegetation toggles are not classified for native feature " + original);
                }
                entries.add(encode(PlacedFeature.CODEC, holder, ops));
            }
            output.add(entries); stage++;
        }
        return output;
    }

    private static String category(String id, PlacedFeature placed) {
        if (placed.feature().is(ResourceKey.create(Registries.FEATURE, Identifier.fromNamespaceAndPath("darkwinter", "test_spruce_tree")))
                || id.startsWith("minecraft:trees_") || id.startsWith("minecraft:tree_")) return "trees";
        return switch (id) {
            case "darkwinter:test_ground_plants" -> "ground";
            case "darkwinter:test_forest_decor", "minecraft:glow_lichen", "minecraft:brown_mushroom_taiga",
                    "minecraft:red_mushroom_taiga", "minecraft:patch_pumpkin" -> "decor";
            case "minecraft:patch_large_fern", "minecraft:patch_berry_rare", "minecraft:patch_firefly_bush_near_water" -> "bushes";
            case "minecraft:patch_grass_taiga_2", "minecraft:patch_sugar_cane" -> "grass";
            default -> id.startsWith("minecraft:patch_grass") || id.startsWith("minecraft:patch_tall_grass") ? "grass"
                    : id.startsWith("minecraft:flower_") ? "decor" : "";
        };
    }

    private static void requireAirTarget(JsonObject placed) {
        JsonArray placement = placed.getAsJsonArray("placement");
        if (placement.isEmpty()) throw new IllegalArgumentException("Native grass placement has no air filter");
        JsonObject last = placement.get(placement.size() - 1).getAsJsonObject();
        if (!"minecraft:block_predicate_filter".equals(last.get("type").getAsString())) {
            throw new IllegalArgumentException("Native grass placement must finish with its air filter");
        }
        JsonObject predicate = last.getAsJsonObject("predicate");
        if (!"minecraft:matching_block_tag".equals(predicate.get("type").getAsString())
                || !"minecraft:air".equals(predicate.get("tag").getAsString())) {
            throw new IllegalArgumentException("Native grass placement's air predicate changed");
        }
    }

    private static void filterGrassMix(JsonObject feature, boolean grass, boolean bushes) {
        if (!"minecraft:simple_block".equals(feature.get("type").getAsString())) throw new IllegalArgumentException("Unsupported native taiga grass feature");
        JsonObject provider = feature.getAsJsonObject("to_place");
        if (!"minecraft:weighted".equals(provider.get("type").getAsString())) throw new IllegalArgumentException("Unsupported native taiga grass provider");
        JsonArray entries = provider.getAsJsonArray("entries");
        boolean sawGrass = false, sawFern = false;
        for (JsonElement value : entries) {
            JsonObject entry = value.getAsJsonObject();
            JsonElement data = entry.get("data");
            if (!data.isJsonPrimitive() || !data.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Unsupported native taiga grass state");
            String block = data.getAsString();
            if (block.equals("minecraft:short_grass")) { sawGrass = true; if (!grass) entry.addProperty("data", "minecraft:air"); }
            else if (block.equals("minecraft:fern")) { sawFern = true; if (!bushes) entry.addProperty("data", "minecraft:air"); }
            else throw new IllegalArgumentException("Unclassified native taiga grass state " + block);
        }
        if (!sawGrass || !sawFern) throw new IllegalArgumentException("Native taiga weighted grass/fern layout changed");
        // AIR preserves original weights/random consumption and does not increase the surviving category's density.
    }

    private static void scaleCount(JsonObject placed, BigDecimal density) {
        if (density.scale() > 6) throw new IllegalArgumentException("Native density supports at most six decimal places");
        JsonObject countPlacement = null;
        for (JsonElement p : placed.getAsJsonArray("placement")) {
            JsonObject object = p.getAsJsonObject();
            if ("minecraft:count".equals(object.get("type").getAsString())) {
                if (countPlacement != null) throw new IllegalArgumentException("Multiple native tree count placements are unsupported");
                countPlacement = object;
            }
        }
        if (countPlacement == null) throw new IllegalArgumentException("Native tree placement has no count provider");
        JsonElement provider = countPlacement.get("count");
        TreeMap<Integer, Long> distribution = new TreeMap<>();
        if (provider.isJsonPrimitive() && provider.getAsJsonPrimitive().isNumber()) scaled(distribution, provider.getAsInt(), 1, density);
        else {
            JsonObject object = provider.getAsJsonObject();
            if (!"minecraft:weighted_list".equals(object.get("type").getAsString())) throw new IllegalArgumentException("Unsupported native tree count provider");
            for (JsonElement value : object.getAsJsonArray("distribution")) {
                JsonObject entry = value.getAsJsonObject(); JsonElement data = entry.get("data");
                if (!data.isJsonPrimitive() || !data.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Nested native count providers are unsupported");
                scaled(distribution, data.getAsInt(), entry.get("weight").getAsLong(), density);
            }
        }
        if (distribution.size() == 1) countPlacement.addProperty("count", distribution.firstKey());
        else {
            long gcd = 0; for (long weight : distribution.values()) gcd = gcd(gcd, weight);
            JsonArray entries = new JsonArray(); long total = 0;
            for (var entry : distribution.entrySet()) {
                long weight = entry.getValue() / gcd; total += weight;
                if (weight <= 0 || weight > Integer.MAX_VALUE || total > Integer.MAX_VALUE) throw new IllegalArgumentException("Native count weights exceed integer bounds");
                JsonObject item = new JsonObject(); item.addProperty("data", entry.getKey()); item.addProperty("weight", weight); entries.add(item);
            }
            JsonObject result = new JsonObject(); result.addProperty("type", "minecraft:weighted_list"); result.add("distribution", entries);
            countPlacement.add("count", result);
        }
    }

    private static void scaled(Map<Integer, Long> output, int count, long weight, BigDecimal density) {
        if (count < 0 || count > 4096 || weight <= 0 || weight > 1_000_000) throw new IllegalArgumentException("Native count data exceeds supported bounds");
        BigDecimal value = density.multiply(BigDecimal.valueOf(count));
        int low = value.setScale(0, RoundingMode.FLOOR).intValueExact(), high = value.setScale(0, RoundingMode.CEILING).intValueExact();
        if (high > 4096) throw new IllegalArgumentException("Scaled native count exceeds 4096");
        long denominator = BigDecimal.TEN.pow(Math.max(0, density.scale())).longValueExact();
        long fraction = value.subtract(BigDecimal.valueOf(low)).multiply(BigDecimal.valueOf(denominator)).longValueExact();
        if (denominator > fraction) output.merge(low, Math.multiplyExact(weight, denominator - fraction), Math::addExact);
        if (fraction > 0) output.merge(high, Math.multiplyExact(weight, fraction), Math::addExact);
    }

    private static long gcd(long a, long b) { while (b != 0) { long r = a % b; a = b; b = r; } return a; }
    private static String data(String kind, String id) { return "data/" + NAMESPACE + "/worldgen/" + kind + "/" + id + ".json"; }
    private static JsonObject tag(String id) {
        JsonObject tag = new JsonObject(); tag.addProperty("replace", false); JsonArray values = new JsonArray(); values.add(id); tag.add("values", values); return tag;
    }
    private static void change(JsonArray changes, String id, String action, String category) {
        JsonObject result = new JsonObject(); result.addProperty("feature", id); result.addProperty("action", action);
        result.addProperty("category", category); changes.add(result);
    }
    private static <T> JsonElement encode(Codec<T> codec, T value, DynamicOps<JsonElement> ops) { return codec.encodeStart(ops, value).getOrThrow(); }
    private static void put(Map<String, byte[]> files, String name, JsonElement json) throws IOException {
        byte[] bytes = (JSON.toJson(json) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES || files.putIfAbsent(name, bytes) != null) throw new IOException("Duplicate or oversized datapack file: " + name);
        if (files.size() > MAX_FILES) throw new IOException("Too many datapack files");
    }
    private static void validateNewTarget(Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Owned export parent must already exist");
        noLinks(parent);
        for (Path p = parent; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Datapack export cannot write inside an existing world");
        }
        String ipc = System.getProperty("darkwinter.biomeEditor.ipc");
        if (ipc != null && !ipc.isBlank()) {
            Path root = Path.of(ipc).toAbsolutePath().normalize().resolve("export");
            if (!target.startsWith(root) || target.equals(root)) throw new IOException("Worker export must remain below its owned IPC export root");
        }
    }
    private static void noLinks(Path path) throws IOException {
        for (Path p = path; p != null; p = p.getParent()) {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther() || !attrs.isDirectory()) throw new IOException("Export ancestor is not a plain directory: " + p);
        }
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
