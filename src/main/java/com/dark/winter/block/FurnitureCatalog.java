// Immutable catalog shared with the generated models; invalid geometry fails at startup.
package com.dark.winter.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Immutable catalog shared with the generated models; invalid geometry fails at startup. */
public record FurnitureCatalog(Map<String, String> palette, List<Entry> entries) {
    public static final String RESOURCE = "/assets/darkwinter/furniture/catalog.json";
    private static final Set<String> MATERIALS = Set.of("wood", "metal", "stone", "glass", "fabric", "plant", "earth", "road");

    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                      String texture, boolean collision) {}
    public record Part(BlockPos offset, List<Box> boxes) {}
    public record Entry(String id, String group, String ru, String en, String material, List<Part> parts, boolean snowVariants) {
        public Entry(String id, String group, String ru, String en, String material, List<Part> parts) {
            this(id, group, ru, en, material, parts, false);
        }
    }

    public static FurnitureCatalog load() {
        try (InputStream stream = FurnitureCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Missing furniture catalog: " + RESOURCE);
            return read(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read furniture catalog", exception);
        }
    }

    public static FurnitureCatalog read(InputStream stream) {
        try {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            require(root.has("schema") && root.get("schema").getAsInt() == 1, "Expected furniture schema 1");
            Map<String, String> palette = new LinkedHashMap<>();
            for (var member : root.getAsJsonObject("palette").entrySet()) {
                String texture = member.getValue().getAsString();
                require(member.getKey().matches("[a-z0-9_]+"), "Invalid palette key: " + member.getKey());
                require(texture.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"), "Invalid texture: " + texture);
                palette.put(member.getKey(), texture);
            }
            require(!palette.isEmpty(), "Furniture palette is empty");
            List<Entry> entries = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (JsonElement element : root.getAsJsonArray("entries")) {
                JsonObject object = element.getAsJsonObject();
                String id = text(object, "id");
                require(id.matches("[a-z0-9_]+") && ids.add(id), "Invalid or duplicate furniture id: " + id);
                String material = text(object, "material");
                require(MATERIALS.contains(material), id + ": unknown material " + material);
                JsonArray rawParts = object.getAsJsonArray("parts");
                require(rawParts.size() >= 1 && rawParts.size() <= FurnitureBlock.MAX_PARTS,
                        id + ": expected 1.." + FurnitureBlock.MAX_PARTS + " parts");
                List<Part> parts = new ArrayList<>();
                Set<BlockPos> offsets = new HashSet<>();
                for (JsonElement rawPart : rawParts) {
                    JsonObject part = rawPart.getAsJsonObject();
                    double[] offsetVector = vector(part, "offset");
                    for (double value : offsetVector) require(value == Math.rint(value) && Math.abs(value) <= 2, id + ": invalid part offset");
                    BlockPos offset = new BlockPos((int)offsetVector[0], (int)offsetVector[1], (int)offsetVector[2]);
                    require(offsets.add(offset), id + ": duplicate part offset " + offset);
                    if (parts.isEmpty()) require(offset.equals(BlockPos.ZERO), id + ": part 0 must be at the origin");
                    List<Box> boxes = new ArrayList<>();
                    for (JsonElement rawBox : part.getAsJsonArray("boxes")) {
                        JsonObject box = rawBox.getAsJsonObject();
                        double[] from = vector(box, "from"), to = vector(box, "to");
                        for (int axis = 0; axis < 3; axis++) require(from[axis] >= 0 && to[axis] <= 16 && from[axis] < to[axis], id + ": box outside local 0..16 bounds");
                        String texture = text(box, "texture");
                        require(palette.containsKey(texture), id + ": unknown texture palette key " + texture);
                        boxes.add(new Box(from[0], from[1], from[2], to[0], to[1], to[2], texture,
                                !box.has("collision") || box.get("collision").getAsBoolean()));
                    }
                    require(!boxes.isEmpty() && boxes.size() <= 128, id + ": expected 1..128 boxes per part");
                    parts.add(new Part(offset.immutable(), List.copyOf(boxes)));
                }
                // Every part must belong to one face-connected assembly, including tall lamps.
                Set<BlockPos> connected = new HashSet<>();
                connected.add(BlockPos.ZERO);
                for (int pass = 0; pass < parts.size(); pass++) {
                    for (BlockPos offset : offsets) {
                        for (BlockPos other : List.copyOf(connected)) {
                            if (offset.distManhattan(other) == 1) connected.add(offset);
                        }
                    }
                }
                require(connected.size() == parts.size(), id + ": disconnected footprint");
                require(!object.has("snow_variants") || object.get("snow_variants").isJsonPrimitive()
                                && object.getAsJsonPrimitive("snow_variants").isBoolean(), id + ": snow_variants must be boolean");
                boolean snowVariants = object.has("snow_variants") && object.get("snow_variants").getAsBoolean();
                entries.add(new Entry(id, text(object, "group"), text(object, "ru"), text(object, "en"), material, List.copyOf(parts), snowVariants));
            }
            require(!entries.isEmpty(), "Furniture catalog has no entries");
            return new FurnitureCatalog(java.util.Collections.unmodifiableMap(palette), List.copyOf(entries));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid furniture catalog: " + exception.getMessage(), exception);
        }
    }

    private static String text(JsonObject object, String key) {
        String value = object.get(key).getAsString();
        require(!value.isBlank(), "Empty " + key);
        return value;
    }

    private static double[] vector(JsonObject object, String key) {
        JsonArray array = object.getAsJsonArray(key);
        require(array.size() == 3, "Expected three coordinates for " + key);
        double[] result = new double[3];
        for (int i = 0; i < 3; i++) {
            result[i] = array.get(i).getAsDouble();
            require(Double.isFinite(result[i]), "Non-finite coordinate in " + key);
        }
        return result;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
