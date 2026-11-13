// Validates the editor profile shared by exact previews and exported native world generation.
package com.dark.winter.biomeeditor;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

public final class BiomeEditorProfile {
    public static final int MAX_OPERATIONS = 128;
    public static final int MAX_PATH_POINTS = 32;
    public static final int MAX_TOTAL_PATH_SEGMENTS = 256;
    public static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    public static final double WORLD_LIMIT = 30_000_000;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,79}");
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern SEED = Pattern.compile("-?[0-9]{1,19}");
    private static final Set<String> KINDS = Set.of("raise", "lower", "flatten", "plains", "smooth", "mountain",
            "plateau", "river", "lake", "cave", "tunnel", "set_block", "paint", "vegetation");

    public record Terrain(float baseHeight, float broadAmplitude, float detailAmplitude, double noiseScale) {}
    public record Point(double x, double z) {}
    public record Operation(String kind, boolean enabled, double x, double y, double z, float radius,
            float strength, float height, float width, float depth, float waterLevel, float falloff,
            List<Point> points, String block) {
        public Operation { points = List.copyOf(points); }
        public boolean terrain() { return !Set.of("set_block", "paint", "vegetation").contains(kind); }
    }

    private final String name, id, biome, canonicalValidatedJson;
    private final long seed;
    private final int chunkX, chunkZ, chunks;
    private final Terrain terrain;
    private final List<Operation> operations;
    private final JsonObject validated;

    private BiomeEditorProfile(JsonObject root) {
        keys(root, "profile", "schema", "name", "id", "seed", "biome", "chunkX", "chunkZ", "chunks",
                "terrain", "operations", "vegetation", "environment", "camera");
        integer(root, "schema", 1, 1);
        name = string(root, "name", 1, 160);
        id = string(root, "id", 1, 80);
        require(ID.matcher(id).matches(), "id must be a lowercase file-safe identifier");
        String seedText = string(root, "seed", 1, 20);
        require(SEED.matcher(seedText).matches(), "seed must be a decimal signed 64-bit string");
        try { seed = Long.parseLong(seedText); }
        catch (NumberFormatException e) { throw invalid("seed is outside signed 64-bit range"); }
        root.addProperty("seed", Long.toString(seed));
        biome = string(root, "biome", 3, 256);
        resource(biome, "biome");
        chunkX = integer(root, "chunkX", -1_875_000, 1_875_000);
        chunkZ = integer(root, "chunkZ", -1_875_000, 1_875_000);
        chunks = integer(root, "chunks", 3, 15);
        require((long) chunkX * 16 >= -WORLD_LIMIT && ((long) chunkX + chunks) * 16 - 1 <= WORLD_LIMIT
                && (long) chunkZ * 16 >= -WORLD_LIMIT && ((long) chunkZ + chunks) * 16 - 1 <= WORLD_LIMIT,
                "preview bounds exceed the native world border");
        JsonObject t = object(root, "terrain");
        keys(t, "terrain", "baseHeight", "broadAmplitude", "detailAmplitude", "noiseScale");
        terrain = new Terrain((float) number(t, "baseHeight", -64, 319),
                (float) number(t, "broadAmplitude", 0, 256), (float) number(t, "detailAmplitude", 0, 64),
                number(t, "noiseScale", .05, 64));
        JsonArray ops = array(root, "operations");
        require(ops.size() <= MAX_OPERATIONS, "operations exceed " + MAX_OPERATIONS);
        List<Operation> parsed = new ArrayList<>();
        int segments = 0, totalPoints = 0;
        for (int i = 0; i < ops.size(); i++) {
            require(ops.get(i).isJsonObject(), "operation " + i + " must be an object");
            Operation op = operation(ops.get(i).getAsJsonObject(), terrain.baseHeight());
            parsed.add(op);
            totalPoints += op.points().size();
            // Each Catmull-Rom span is deterministically represented by four native distance segments.
            if (op.kind().equals("river") || op.kind().equals("tunnel")) {
                segments += Math.max(0, op.points().size() - 1) * 4;
            }
        }
        require(totalPoints <= 256, "operation paths exceed 256 total control points");
        require(segments <= MAX_TOTAL_PATH_SEGMENTS, "river/tunnel paths exceed " + MAX_TOTAL_PATH_SEGMENTS + " native segments");
        operations = List.copyOf(parsed);
        JsonObject v = object(root, "vegetation");
        keys(v, "vegetation", "trees", "grass", "bushes", "decor", "density");
        for (String key : List.of("trees", "grass", "bushes", "decor")) bool(v, key);
        number(v, "density", 0, 8);
        JsonObject e = object(root, "environment");
        keys(e, "environment", "time", "weather", "effects");
        integer(e, "time", 0, 23999);
        require(Set.of("clear", "rain", "snow", "thunder").contains(string(e, "weather", 1, 16)), "unknown weather");
        bool(e, "effects");
        JsonObject c = object(root, "camera");
        keys(c, "camera", "targetX", "targetY", "targetZ", "yaw", "pitch", "span", "cutY");
        number(c, "targetX", -WORLD_LIMIT, WORLD_LIMIT);
        number(c, "targetZ", -WORLD_LIMIT, WORLD_LIMIT);
        number(c, "targetY", -512, 1024);
        number(c, "yaw", -360000, 360000);
        number(c, "pitch", -90, 90);
        if (c.has("cutY")) number(c, "cutY", -64, 320);
        number(c, "span", 4, 4096);
        validated = sorted(root).getAsJsonObject();
        canonicalValidatedJson = validated.toString();
    }

    public static BiomeEditorProfile parse(String json) {
        Objects.requireNonNull(json, "profile");
        require(json.length() <= MAX_JSON_BYTES
                && json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_JSON_BYTES, "profile JSON is too large");
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            JsonElement value = read(reader, 0);
            require(reader.peek() == JsonToken.END_DOCUMENT && value.isJsonObject(), "profile must be one JSON object");
            return new BiomeEditorProfile(value.getAsJsonObject());
        } catch (IOException | IllegalStateException e) {
            throw invalid("invalid profile JSON: " + e.getMessage());
        }
    }

    public static BiomeEditorProfile parse(JsonObject json) { return parse(json.toString()); }
    public String name() { return name; }
    public String id() { return id; }
    public String biome() { return biome; }
    public long seed() { return seed; }
    public String seedDecimal() { return Long.toString(seed); }
    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public int chunks() { return chunks; }
    public Terrain terrain() { return terrain; }
    public List<Operation> operations() { return operations; }
    public String canonicalValidatedJson() { return canonicalValidatedJson; }
    public JsonObject json() { return validated.deepCopy(); }

    private static Operation operation(JsonObject o, float baseHeight) {
        keys(o, "operation", "kind", "enabled", "x", "y", "z", "radius", "strength", "height",
                "width", "depth", "waterLevel", "falloff", "points", "block");
        String kind = string(o, "kind", 1, 32);
        require(KINDS.contains(kind), "unknown operation kind: " + kind);
        boolean enabled = optionalBool(o, "enabled", true);
        double x = optionalNumber(o, "x", 0, -WORLD_LIMIT, WORLD_LIMIT);
        double y = optionalNumber(o, "y", baseHeight, -WORLD_LIMIT, WORLD_LIMIT);
        double z = optionalNumber(o, "z", 0, -WORLD_LIMIT, WORLD_LIMIT);
        float radius = (float) optionalNumber(o, "radius", 8, .001, 1024);
        float strength = (float) optionalNumber(o, "strength", 1, 0, 1024);
        float height = (float) optionalNumber(o, "height", baseHeight, -WORLD_LIMIT, WORLD_LIMIT);
        float width = (float) optionalNumber(o, "width", 5, .001, 1024);
        float depth = (float) optionalNumber(o, "depth", 4, 0, 1024);
        float water = (float) optionalNumber(o, "waterLevel", 63, -WORLD_LIMIT, WORLD_LIMIT);
        float falloff = (float) optionalNumber(o, "falloff", .65, 0, 1);
        List<Point> points = new ArrayList<>();
        if (o.has("points")) {
            JsonArray a = array(o, "points");
            require(a.size() <= MAX_PATH_POINTS, "too many points in operation");
            for (JsonElement point : a) {
                require(point.isJsonArray() && point.getAsJsonArray().size() == 2, "path point must contain [x,z]");
                JsonArray p = point.getAsJsonArray();
                Point parsedPoint = new Point(finite(p.get(0), -WORLD_LIMIT, WORLD_LIMIT, "point.x"),
                        finite(p.get(1), -WORLD_LIMIT, WORLD_LIMIT, "point.z"));
                require(Math.abs(parsedPoint.x()) + width * .5 <= WORLD_LIMIT
                        && Math.abs(parsedPoint.z()) + width * .5 <= WORLD_LIMIT, "path footprint exceeds world bounds");
                if (!points.isEmpty()) {
                    Point previous = points.getLast();
                    require(Math.hypot(parsedPoint.x() - previous.x(), parsedPoint.z() - previous.z()) <= 32768,
                            "path control-point spans must not exceed 32768 blocks");
                }
                points.add(parsedPoint);
            }
        }
        if (kind.equals("river") || kind.equals("tunnel")) require(points.size() >= 2, kind + " requires at least two points");
        String block = o.has("block") ? string(o, "block", 3, 256) : "minecraft:stone";
        resource(block, "block");
        if (!kind.equals("river") && !kind.equals("tunnel")) {
            require(Math.abs(x) + radius <= WORLD_LIMIT && Math.abs(z) + radius <= WORLD_LIMIT, "operation footprint exceeds world bounds");
        }
        // Store explicit defaults so a saved profile has exactly the same meaning on every rebuild.
        o.addProperty("enabled", enabled);
        o.addProperty("x", x); o.addProperty("y", y); o.addProperty("z", z);
        o.addProperty("radius", radius); o.addProperty("strength", strength); o.addProperty("height", height);
        o.addProperty("width", width); o.addProperty("depth", depth); o.addProperty("waterLevel", water);
        o.addProperty("falloff", falloff); o.addProperty("block", block);
        if (!o.has("points")) o.add("points", new JsonArray());
        return new Operation(kind, enabled, x, y, z, radius, strength, height, width, depth, water, falloff, points, block);
    }

    private static JsonElement read(JsonReader r, int depth) throws IOException {
        require(depth <= 16, "JSON nesting exceeds 16");
        return switch (r.peek()) {
            case BEGIN_OBJECT -> {
                r.beginObject(); JsonObject o = new JsonObject();
                while (r.hasNext()) { String key = r.nextName(); require(!o.has(key), "duplicate JSON key: " + key); o.add(key, read(r, depth + 1)); }
                r.endObject(); yield o;
            }
            case BEGIN_ARRAY -> {
                r.beginArray(); JsonArray a = new JsonArray();
                while (r.hasNext()) { require(a.size() < 4096, "JSON array is too large"); a.add(read(r, depth + 1)); }
                r.endArray(); yield a;
            }
            case STRING -> new JsonPrimitive(r.nextString());
            case NUMBER -> {
                String n = r.nextString();
                try { yield new JsonPrimitive(new BigDecimal(n)); }
                catch (NumberFormatException e) { throw invalid("invalid JSON number"); }
            }
            case BOOLEAN -> new JsonPrimitive(r.nextBoolean());
            case NULL -> { r.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw invalid("unexpected JSON token");
        };
    }

    private static JsonElement sorted(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            new TreeMap<>(value.getAsJsonObject().asMap()).forEach((k, v) -> result.add(k, sorted(v)));
            return result;
        }
        if (value.isJsonArray()) { JsonArray a = new JsonArray(); value.getAsJsonArray().forEach(v -> a.add(sorted(v))); return a; }
        return value.deepCopy();
    }

    private static void keys(JsonObject o, String where, String... allowed) {
        Set<String> set = Set.of(allowed);
        for (String key : o.keySet()) require(set.contains(key), "unknown " + where + " field: " + key);
    }
    private static JsonObject object(JsonObject o, String k) {
        require(o.has(k) && o.get(k).isJsonObject(), k + " must be an object"); return o.getAsJsonObject(k);
    }
    private static JsonArray array(JsonObject o, String k) {
        require(o.has(k) && o.get(k).isJsonArray(), k + " must be an array"); return o.getAsJsonArray(k);
    }
    private static String string(JsonObject o, String k, int min, int max) {
        require(o.has(k) && o.get(k).isJsonPrimitive() && o.getAsJsonPrimitive(k).isString(), k + " must be a string");
        String s = o.get(k).getAsString();
        require(s.length() >= min && s.length() <= max && s.chars().noneMatch(ch -> ch < 32), k + " is invalid"); return s;
    }
    private static boolean bool(JsonObject o, String k) {
        require(o.has(k) && o.get(k).isJsonPrimitive() && o.getAsJsonPrimitive(k).isBoolean(), k + " must be boolean"); return o.get(k).getAsBoolean();
    }
    private static boolean optionalBool(JsonObject o, String k, boolean d) { return o.has(k) ? bool(o, k) : d; }
    private static double number(JsonObject o, String k, double min, double max) {
        require(o.has(k), "missing " + k); return finite(o.get(k), min, max, k);
    }
    private static double optionalNumber(JsonObject o, String k, double d, double min, double max) { return o.has(k) ? number(o, k, min, max) : d; }
    private static double finite(JsonElement e, double min, double max, String k) {
        require(e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber(), k + " must be numeric");
        double n = e.getAsDouble(); require(Double.isFinite(n) && n >= min && n <= max, k + " is outside bounds"); return n;
    }
    private static int integer(JsonObject o, String k, int min, int max) {
        double n = number(o, k, min, max); require(n == Math.rint(n), k + " must be an integer"); return (int) n;
    }
    private static void resource(String value, String key) { require(RESOURCE.matcher(value).matches() && !value.contains(".."), key + " must be a safe resource identifier"); }
    private static void require(boolean valid, String message) { if (!valid) throw invalid(message); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
