// Resource-pack-aware polygon meshes baked once into ordinary chunk and item geometry.
package com.dark.winter.client;

import com.dark.winter.DarkWinter;
import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.SnowVariantFurnitureBlock;
import com.dark.winter.block.FloorSegmentBlock;
import com.dark.winter.block.FrozenWindowBlock;
import com.dark.winter.block.WallSegmentBlock;
import com.dark.winter.registry.ModFurniture;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Transformation;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.MeshQuadCollection;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Resource-pack-aware polygon meshes baked once into ordinary chunk and item geometry. */
public final class FurnitureMeshModels {
    public static final Identifier RESOURCE = Identifier.fromNamespaceAndPath("darkwinter", "furniture/meshes.json");
    public static final Identifier BUILDING_RESOURCE = Identifier.fromNamespaceAndPath("darkwinter", "furniture/building-meshes.json");
    private static final Set<String> BUILDING_IDS = Set.of("wall_segment", "weathered_wall_segment_gray",
            "weathered_wall_segment_dark", "weathered_wall_segment_patchwork", "weathered_wall_segment_painted",
            "floor_segment", "weathered_floor_segment_gray", "weathered_floor_segment_dark",
            "weathered_floor_segment_patchwork", "weathered_floor_segment_painted", "frozen_window",
            "frozen_window_gray", "frozen_window_dark");
    public static final int MESH_QUAD_TAG = 0x44574D;
    // Growth of the outdoor catalogue has its own bounded allocation; the
    // established furniture allocation and each object's bake limit stay fixed.
    private static final Set<String> OUTDOOR_FENCE_IDS = outdoorFenceIds();
    private static final Set<String> DEADWOOD_B_IDS = Set.of("spruce_stump", "fallen_spruce_log", "forest_branch_pile");
    private static boolean registered;

    private static Set<String> outdoorFenceIds() {
        Set<String> ids = new java.util.HashSet<>();
        for (String style : List.of("picket", "horizontal", "wire_mesh")) {
            for (String height : List.of("low", "tall")) {
                for (String width : List.of("1m", "2m")) {
                    ids.add(style + "_fence_" + height + "_" + width);
                    ids.add(style + "_gate_" + height + "_clear_" + width);
                }
            }
        }
        return Set.copyOf(ids);
    }

    /** Exposes actual baked geometry for client integration checks, without consulting source files. */
    public interface MeshBackedModel {
        Mesh mesh();
        default Mesh mesh(ItemStack stack) { return mesh(); }
        default Mesh mesh(BlockPos pos) { return mesh(); }
        String furnitureId();
    }

    private FurnitureMeshModels() {}

    public static synchronized void init() {
        if (registered) return;
        registered = true;
        PreparableModelLoadingPlugin.register(
                (shared, executor) -> CompletableFuture.supplyAsync(() -> read(shared.resourceManager()), executor),
                (data, plugin) -> {
                    plugin.modifyBlockModelAfterBake().register((model, context) -> {
                        if (context.state().getBlock() instanceof FurnitureBlock block) {
                            String id = block.definition().id();
                            boolean snowy = block instanceof SnowVariantFurnitureBlock
                                    && context.state().getValue(SnowVariantFurnitureBlock.SNOWY);
                            BakedFurniture baked = data.bake(id, context.baker(), snowy);
                            int part = context.state().getValue(FurnitureBlock.PART);
                            int facing = facingIndex(context.state().getValue(FurnitureBlock.FACING));
                            int grid = snowy ? data.snowUvGrids.getOrDefault(id, 1) : 1;
                            if (data.surfaceEntries.containsKey(id) || grid > 1) {
                                List<List<BakedPart>> variants = new ArrayList<>();
                                int patterns = data.surfaceEntries.containsKey(id) ? 1 + data.surfaceEntries.get(id).size() : 1;
                                for (int variant = 0; variant < patterns; variant++) {
                                    List<BakedPart> tiles = new ArrayList<>();
                                    for (int tile = 0; tile < grid * grid; tile++)
                                        tiles.add(data.bakeSurface(id, context.baker(), snowy, variant, tile).parts()[part][facing]);
                                    variants.add(List.copyOf(tiles));
                                }
                                BlockPos offset = part < block.definition().parts().size()
                                        ? block.partOffset(part, context.state().getValue(FurnitureBlock.FACING)) : BlockPos.ZERO;
                                return new FurnitureBlockModel(model, id, List.copyOf(variants), offset, grid);
                            }
                            return new FurnitureBlockModel(model, id, baked.parts()[part][facing]);
                        }
                        if (!isBuildingModule(context.state().getBlock())) return model;
                        Identifier blockId = BuiltInRegistries.BLOCK.getKey(context.state().getBlock());
                        if (!blockId.getNamespace().equals(DarkWinter.MOD_ID) || !BUILDING_IDS.contains(blockId.getPath())) return model;
                        String id = blockId.getPath();
                        return new FurnitureBlockModel(model, id, data.buildings.bake(id, context.baker()).part(context.state()));
                    });
                    plugin.modifyItemModelAfterBake().register((model, context) -> {
                        if (!context.itemId().getNamespace().equals(DarkWinter.MOD_ID)) return model;
                        String id = context.itemId().getPath();
                        ModelBaker baker = context.bakingContext().blockModelBaker();
                        BakedPart itemGeometry;
                        if (ModFurniture.get(id) != null) itemGeometry = data.bake(id, baker).item();
                        else if (BUILDING_IDS.contains(id)) itemGeometry = data.buildings.bake(id, baker).item();
                        else return model;
                        ModelRenderProperties properties;
                        Matrix4fc transformation = context.transformation();
                        if (context.sourceModel() instanceof CuboidItemModelWrapper.Unbaked source) {
                            var resolved = baker.getModel(source.model());
                            properties = ModelRenderProperties.fromResolvedModel(baker, resolved, resolved.getTopTextureSlots());
                            transformation = Transformation.compose(transformation, source.transformation());
                            properties = new ModelRenderProperties(properties.usesBlockLight(), itemGeometry.particleMaterial(), properties.transforms());
                        } else {
                            properties = new ModelRenderProperties(true, itemGeometry.particleMaterial(), ItemTransforms.NO_TRANSFORMS);
                        }
                        BakedPart snowGeometry = ModFurniture.get(id) instanceof SnowVariantFurnitureBlock
                                ? data.bake(id, baker, true).item() : null;
                        return new FurnitureItemModel(id, itemGeometry, snowGeometry, properties, new Matrix4f(transformation));
                    });
                });
    }

    private record Vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        Vertex(float x, float y, float z, float u, float v) { this(x,y,z,u,v,0,0,0); }
    }
    private record Face(String texture, List<Vertex> vertices, boolean cutout) {
        private Face(String texture, List<Vertex> vertices) { this(texture, vertices, false); }
    }
    private record VertexKey(String texture, long x, long y, long z) {}
    private record WeightedNormal(float x, float y, float z, float weight) {}
    private record Part(BlockPos offset, List<Face> faces) {}
    private record Entry(String id, List<Part> parts) {}
    private record BakedFurniture(BakedPart[][] parts, BakedPart item) {}
    private record SurfaceEntry(Entry clean, Entry snow) {}
    private record SurfaceKey(String id, boolean snowy, int variant, int tile) {}
    private record SnowUvGrid(int grid, int tile) {
        int x() { return tile % grid; }
        int z() { return tile / grid; }
        SnowUvGrid atPart(BlockPos offset, int facing) {
            int dx = offset.getX(), dz = offset.getZ();
            for (int turn = 0; turn < facing; turn++) { int nextX = -dz; dz = dx; dx = nextX; }
            return new SnowUvGrid(grid, Math.floorMod(x() + dx, grid) + Math.floorMod(z() + dz, grid) * grid);
        }
    }
    private record BuildingVariant(Map<String, String> match, List<Face> faces) {}
    private record BuildingEntry(String id, List<BuildingVariant> variants) {}
    private record BakedBuilding(BuildingEntry source, List<BakedFurniture> variants, BakedPart item) {
        private BakedPart part(BlockState state) {
            int facing = state.hasProperty(WallSegmentBlock.FACING) ? facingIndex(state.getValue(WallSegmentBlock.FACING)) : 0;
            return variants.get(matchingVariant(source, state)).parts()[0][facing];
        }
    }

    private static final class BuildingData {
        private final Map<String, Identifier> palette;
        private final Map<String, BuildingEntry> entries;
        private final Map<String, BakedBuilding> baked = new ConcurrentHashMap<>();

        private BuildingData(Map<String, Identifier> palette, Map<String, BuildingEntry> entries) {
            this.palette = Map.copyOf(palette); this.entries = Map.copyOf(entries);
        }

        private BakedBuilding bake(String id, ModelBaker baker) {
            return baked.computeIfAbsent(id, ignored -> {
                BuildingEntry source = entries.get(id);
                List<BakedFurniture> variants = source.variants().stream().map(variant -> bakeFurniture(
                        new Entry(id, List.of(new Part(BlockPos.ZERO, variant.faces()))), palette, baker)).toList();
                BlockState defaultState = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DarkWinter.MOD_ID, id)).defaultBlockState();
                return new BakedBuilding(source, variants, variants.get(matchingVariant(source, defaultState)).item());
            });
        }
    }

    private static final class MeshData {
        private final Map<String, Identifier> palette;
        private final Map<String, Entry> entries;
        private final Map<String, BakedFurniture> baked = new ConcurrentHashMap<>();
        private final BuildingData buildings;
        private final Map<String, Entry> snowEntries;
        private final Map<String, BakedFurniture> snowBaked = new ConcurrentHashMap<>();
        private final Map<String, List<SurfaceEntry>> surfaceEntries;
        private final Map<SurfaceKey, BakedFurniture> surfaceBaked = new ConcurrentHashMap<>();
        private final Map<String, Integer> snowUvGrids;

        private MeshData(Map<String, Identifier> palette, Map<String, Entry> entries,
                         Map<String, Entry> snowEntries, Map<String, List<SurfaceEntry>> surfaceEntries,
                         Map<String, Integer> snowUvGrids, BuildingData buildings) {
            this.palette = Map.copyOf(palette);
            this.entries = Map.copyOf(entries);
            this.snowEntries = Map.copyOf(snowEntries);
            this.surfaceEntries = Map.copyOf(surfaceEntries);
            this.snowUvGrids = Map.copyOf(snowUvGrids);
            this.buildings = buildings;
        }

        private BakedFurniture bake(String id, ModelBaker baker) {
            return baked.computeIfAbsent(id, ignored -> bakeFurniture(entries.get(id), palette, baker));
        }

        private BakedFurniture bake(String id, ModelBaker baker, boolean snowy) {
            if (!snowy) return bake(id, baker);
            return snowBaked.computeIfAbsent(id, ignored -> bakeFurniture(snowEntries.get(id), palette, baker, snowUv(id, 0)));
        }

        private @Nullable SnowUvGrid snowUv(String id, int tile) {
            int grid = snowUvGrids.getOrDefault(id, 1);
            return grid == 1 ? null : new SnowUvGrid(grid, tile);
        }

        private BakedFurniture bakeSurface(String id, ModelBaker baker, boolean snowy, int variant, int tile) {
            if (!snowy) tile = 0;
            if (variant == 0 && tile == 0) return bake(id, baker, snowy);
            SurfaceKey key = new SurfaceKey(id, snowy, variant, tile);
            return surfaceBaked.computeIfAbsent(key, ignored -> {
                Entry source;
                if (variant == 0) source = snowy ? snowEntries.get(id) : entries.get(id);
                else {
                    SurfaceEntry alternate = surfaceEntries.get(id).get(variant - 1);
                    source = snowy ? alternate.snow() : alternate.clean();
                }
                return bakeFurniture(source, palette, baker, snowy ? snowUv(id, key.tile()) : null);
            });
        }
    }

    private static MeshData read(ResourceManager resources) {
        try (Reader reader = resources.openAsReader(RESOURCE)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            require(root.has("schema") && root.get("schema").getAsInt() == 1, "Expected furniture mesh schema 1");
            Map<String, Identifier> palette = new LinkedHashMap<>();
            root.getAsJsonObject("palette").entrySet().forEach(member ->
                    palette.put(member.getKey(), Identifier.parse(member.getValue().getAsString())));
            require(!palette.isEmpty(), "Mesh palette is empty");
            Map<String, Entry> entries = new LinkedHashMap<>();
            Map<String, Entry> snowEntries = new LinkedHashMap<>();
            Map<String, List<SurfaceEntry>> surfaceEntries = new LinkedHashMap<>();
            Map<String, Integer> snowUvGrids = new LinkedHashMap<>();
            int quadCount = 0;
            int outdoorFenceQuads = 0;
            int deadwoodQuads = 0;
            for (JsonElement element : root.getAsJsonArray("entries")) {
                int entryStart = quadCount;
                JsonObject object = element.getAsJsonObject();
                String id = object.get("id").getAsString();
                require(id.matches("[a-z0-9_]+") && !entries.containsKey(id), "Invalid or duplicate mesh id: " + id);
                List<Part> parts = new ArrayList<>();
                for (JsonElement rawPart : object.getAsJsonArray("parts")) {
                    JsonObject part = rawPart.getAsJsonObject();
                    JsonArray offset = part.getAsJsonArray("offset");
                    require(offset.size() == 3, id + ": expected a three-coordinate offset");
                    int[] coordinates = new int[3];
                    for (int axis = 0; axis < 3; axis++) {
                        double coordinate = offset.get(axis).getAsDouble();
                        require(Double.isFinite(coordinate) && coordinate == Math.rint(coordinate) && Math.abs(coordinate) <= 2,
                                id + ": invalid part offset");
                        coordinates[axis] = (int) coordinate;
                    }
                    List<Face> faces = readFaces(part.getAsJsonArray("quads"), palette, id);
                    quadCount += faces.size();
                    parts.add(new Part(new BlockPos(coordinates[0], coordinates[1], coordinates[2]), List.copyOf(faces)));
                }
                require(!parts.isEmpty() && parts.size() <= FurnitureBlock.MAX_PARTS, id + ": invalid mesh part count");
                require(parts.stream().anyMatch(part -> !part.faces().isEmpty()), id + ": empty furniture mesh");
                entries.put(id, new Entry(id, List.copyOf(parts)));
                if (object.has("snow_parts")) {
                    JsonArray rawSnow = object.getAsJsonArray("snow_parts");
                    require(rawSnow.size() == parts.size(), id + ": snow overlay must preserve the part count");
                    List<Part> snowyParts = new ArrayList<>();
                    int overlayCount = 0;
                    for (int index = 0; index < parts.size(); index++) {
                        Part base = parts.get(index);
                        JsonObject overlay = rawSnow.get(index).getAsJsonObject();
                        JsonArray offset = overlay.getAsJsonArray("offset");
                        require(offset.size() == 3 && offset.get(0).getAsDouble() == base.offset().getX()
                                        && offset.get(1).getAsDouble() == base.offset().getY()
                                        && offset.get(2).getAsDouble() == base.offset().getZ(),
                                id + ": snow overlay changes the part order or footprint");
                        List<Face> snowFaces = readFaces(overlay.getAsJsonArray("quads"), palette, id + " snow overlay", true);
                        overlayCount += snowFaces.size();
                        List<Face> combined = new ArrayList<>(base.faces());
                        combined.addAll(snowFaces);
                        snowyParts.add(new Part(base.offset(), List.copyOf(combined)));
                    }
                    require(overlayCount > 0, id + ": snow overlay must contain visible geometry");
                    require(snowyParts.stream().mapToInt(part -> part.faces().size()).sum() <= 5000,
                            id + ": snowy mesh exceeds the existing 5000-quad object budget");
                    quadCount += overlayCount;
                    snowEntries.put(id, new Entry(id, List.copyOf(snowyParts)));
                }
                if (object.has("snow_uv_grid")) {
                    FurnitureBlock block = ModFurniture.get(id);
                    require(block instanceof SnowVariantFurnitureBlock && snowEntries.containsKey(id),
                            id + ": snow UV grid requires explicit snow-variant opt-in");
                    JsonElement rawGrid = object.get("snow_uv_grid");
                    require(rawGrid.isJsonPrimitive() && rawGrid.getAsJsonPrimitive().isNumber(), id + ": snow_uv_grid must be an integer");
                    double value = rawGrid.getAsDouble();
                    require(value == 1 || value == 2 || value == 4, id + ": snow_uv_grid must be 1, 2 or 4");
                    require(!id.equals("forest_branch_pile") || parts.stream().noneMatch(part -> part.faces().stream().anyMatch(Face::cutout)) || value == 1,
                            id + ": private dry-bough base cutout requires an unsplit UV grid");
                    require(!id.equals("forest_fern") || parts.stream().noneMatch(part -> part.faces().stream().anyMatch(Face::cutout)) || value == 1,
                            id + ": private fern-frond base cutout requires an unsplit UV grid");
                    snowUvGrids.put(id, (int) value);
                }
                if (object.has("surface_variants")) {
                    FurnitureBlock block = ModFurniture.get(id);
                    require(block instanceof SnowVariantFurnitureBlock && block.definition().snowVariants()
                                    && snowEntries.containsKey(id), id + ": surface variants require explicit snow-variant opt-in");
                    JsonArray rawVariants = object.getAsJsonArray("surface_variants");
                    require(rawVariants.size() == 3, id + ": expected three alternate surface patterns");
                    List<SurfaceEntry> variants = new ArrayList<>();
                    for (int index = 0; index < rawVariants.size(); index++) {
                        JsonObject variant = rawVariants.get(index).getAsJsonObject();
                        Entry clean = remapSurface(entries.get(id), variant.getAsJsonObject("texture_remap"), palette);
                        Entry snow = addSnowOverlay(clean, variant.getAsJsonArray("snow_parts"), palette);
                        // The remapped base reuses authored geometry; every alternate snow pattern is counted once.
                        quadCount += faceCount(snow) - faceCount(clean);
                        variants.add(new SurfaceEntry(clean, snow));
                    }
                    surfaceEntries.put(id, List.copyOf(variants));
                }
                if (OUTDOOR_FENCE_IDS.contains(id)) outdoorFenceQuads += quadCount - entryStart;
                if (DEADWOOD_B_IDS.contains(id)) deadwoodQuads += quadCount - entryStart;
            }
            for (FurnitureBlock block : ModFurniture.all()) {
                String id = block.definition().id();
                Entry entry = entries.get(id);
                require(entry != null, "Missing furniture mesh for " + id);
                require(block.definition().snowVariants() == snowEntries.containsKey(id),
                        id + ": snow overlay and opt-in catalogue metadata must agree");
                require(entry.parts().size() == block.definition().parts().size(), id + ": mesh and collision part counts differ");
                for (int part = 0; part < entry.parts().size(); part++) {
                    require(entry.parts().get(part).offset().equals(block.definition().parts().get(part).offset()),
                            id + ": mesh part order/offset differs from the saved-world footprint");
                }
            }
            DarkWinter.LOGGER.info("Prepared {} furniture mesh entries ({} quads) from resource packs", entries.size(), quadCount);
            require(quadCount - outdoorFenceQuads - deadwoodQuads <= 100000,
                    "Established furniture meshes and snow overlays exceed the existing 100000-quad budget");
            require(deadwoodQuads <= 10000, "Deadwood B exceeds its bounded 10000-quad allocation");
            require(outdoorFenceQuads <= 20000, "Outdoor fences exceed their separate 20000-quad budget");
            require(quadCount <= 120000, "Combined furniture and outdoor fences exceed the 120000-quad budget");
            return new MeshData(palette, entries, snowEntries, surfaceEntries, snowUvGrids, readBuildings(resources));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot load " + RESOURCE + ": " + exception.getMessage(), exception);
        }
    }

    private static int faceCount(Entry entry) {
        return entry.parts().stream().mapToInt(part -> part.faces().size()).sum();
    }

    private static Entry remapSurface(Entry base, JsonObject rawRemap, Map<String, Identifier> palette) {
        require(rawRemap != null, base.id() + ": alternate surface needs texture_remap");
        Map<String, String> remap = new LinkedHashMap<>();
        Set<String> used = new java.util.HashSet<>();
        for (Part part : base.parts()) for (Face face : part.faces()) used.add(face.texture());
        for (var member : rawRemap.entrySet()) {
            String target = member.getValue().getAsString();
            require(palette.containsKey(member.getKey()) && used.contains(member.getKey()) && palette.containsKey(target),
                    base.id() + ": alternate texture remap refers to an unknown or unused base material");
            require(!base.id().equals("forest_branch_pile") || !member.getKey().equals("deadwood_b_dry_boughs") || target.equals(member.getKey()),
                    base.id() + ": private dry-bough cutout cannot be remapped to another material");
            require(!member.getKey().equals("plants_c_fronds") && !target.equals("plants_c_fronds"),
                    base.id() + ": private fern-frond material cannot be remapped");
            remap.put(member.getKey(), target);
        }
        List<Part> parts = new ArrayList<>();
        for (Part part : base.parts()) {
            List<Face> faces = part.faces().stream()
                    .map(face -> new Face(remap.getOrDefault(face.texture(), face.texture()), face.vertices(), face.cutout())).toList();
            parts.add(new Part(part.offset(), faces));
        }
        return new Entry(base.id(), List.copyOf(parts));
    }

    private static Entry addSnowOverlay(Entry base, JsonArray rawSnow, Map<String, Identifier> palette) {
        require(rawSnow != null && rawSnow.size() == base.parts().size(), base.id() + ": alternate snow changes part count");
        List<Part> parts = new ArrayList<>();
        int overlayCount = 0;
        for (int index = 0; index < base.parts().size(); index++) {
            Part part = base.parts().get(index);
            JsonObject overlay = rawSnow.get(index).getAsJsonObject();
            JsonArray offset = overlay.getAsJsonArray("offset");
            require(offset != null && offset.size() == 3 && offset.get(0).getAsDouble() == part.offset().getX()
                            && offset.get(1).getAsDouble() == part.offset().getY()
                            && offset.get(2).getAsDouble() == part.offset().getZ(),
                    base.id() + ": alternate snow changes part order or footprint");
            List<Face> snow = readFaces(overlay.getAsJsonArray("quads"), palette, base.id() + " alternate snow", true);
            overlayCount += snow.size();
            List<Face> combined = new ArrayList<>(part.faces());
            combined.addAll(snow);
            parts.add(new Part(part.offset(), List.copyOf(combined)));
        }
        require(overlayCount > 0, base.id() + ": alternate snow pattern must be visible");
        Entry result = new Entry(base.id(), List.copyOf(parts));
        require(faceCount(result) <= 5000, base.id() + ": alternate snowy mesh exceeds the existing 5000-quad budget");
        return result;
    }

    /** Tile index in an authored continuous snow mask, independent of surface-pattern hashing. */
    public static int atlasTileIndex(BlockPos pos, int grid) {
        if (grid != 1 && grid != 2 && grid != 4) throw new IllegalArgumentException("Snow UV grid must be 1, 2 or 4");
        return Math.floorMod(pos.getX(), grid) + Math.floorMod(pos.getZ(), grid) * grid;
    }

    /** Fixed X/Z hash, independent of ID, marking, facing, time and resource reload. */
    public static int surfaceVariant(BlockPos pos, int count) {
        long hash = (long) pos.getX() * 0x9E3779B97F4A7C15L ^ (long) pos.getZ() * 0xC2B2AE3D27D4EB4FL ^ 0xD4A5C7E19B236F01L;
        hash ^= hash >>> 30;
        hash *= 0xBF58476D1CE4E5B9L;
        hash ^= hash >>> 27;
        hash *= 0x94D049BB133111EBL;
        hash ^= hash >>> 31;
        return Math.floorMod((int) (hash ^ hash >>> 32), count);
    }

    private static List<Face> readFaces(JsonArray rawFaces, Map<String, Identifier> palette, String id) {
        return readFaces(rawFaces, palette, id, false);
    }

    private static List<Face> readFaces(JsonArray rawFaces, Map<String, Identifier> palette, String id, boolean allowCutout) {
        List<Face> faces = new ArrayList<>();
        for (JsonElement rawFace : rawFaces) {
            JsonObject face = rawFace.getAsJsonObject();
            String texture = face.get("texture").getAsString();
            require(palette.containsKey(texture), id + ": unknown mesh material " + texture);
            JsonArray positions = face.getAsJsonArray("p"), uvs = face.getAsJsonArray("uv");
            require(positions.size() == 4 && uvs.size() == 4, id + ": quads require four vertices and UV pairs");
            List<Vertex> vertices = new ArrayList<>(4);
            for (int vertex = 0; vertex < 4; vertex++) {
                JsonArray position = positions.get(vertex).getAsJsonArray(), uv = uvs.get(vertex).getAsJsonArray();
                require(position.size() == 3 && uv.size() == 2, id + ": invalid vertex/UV dimensions");
                float x = finite(position.get(0)), y = finite(position.get(1)), z = finite(position.get(2));
                require(x >= -0.5F && x <= 16.5F && y >= -0.5F && y <= 16.5F && z >= -0.5F && z <= 16.5F,
                        id + ": mesh vertex exceeds its part bounds");
                float u = finite(uv.get(0)), v = finite(uv.get(1));
                require(u >= -0.0001F && u <= 16.0001F && v >= -0.0001F && v <= 16.0001F,
                        id + ": UV outside its material's 0..16 range");
                vertices.add(new Vertex(x, y, z, Math.clamp(u, 0, 16), Math.clamp(v, 0, 16)));
            }
            require(normal(vertices)[3] > 0.00000001F, id + ": degenerate mesh face");
            require(!face.has("cutout") || face.get("cutout").isJsonPrimitive()
                            && face.getAsJsonPrimitive("cutout").isBoolean(), id + ": cutout must be boolean");
            boolean cutout = face.has("cutout") && face.get("cutout").getAsBoolean();
            boolean dryBoughCutout = id.equals("forest_branch_pile") && texture.equals("deadwood_b_dry_boughs")
                    && Identifier.fromNamespaceAndPath("darkwinter", "block/nature/b_deadwood_dry_boughs").equals(palette.get(texture));
            boolean fernFrondCutout = id.equals("forest_fern") && texture.equals("plants_c_fronds")
                    && Identifier.fromNamespaceAndPath("darkwinter", "block/nature/c_fern_fronds").equals(palette.get(texture));
            require(!cutout || allowCutout || dryBoughCutout || fernFrondCutout,
                    id + ": explicit cutout is supported only for opt-in snow overlays or exact private dry-bough/fern-frond materials");
            faces.add(new Face(texture, List.copyOf(vertices), cutout));
        }
        return List.copyOf(faces);
    }

    private static BuildingData readBuildings(ResourceManager resources) {
        try (Reader reader = resources.openAsReader(BUILDING_RESOURCE)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            require(root.has("schema") && root.get("schema").getAsInt() == 1, "Expected building mesh schema 1");
            Map<String, Identifier> palette = new LinkedHashMap<>();
            root.getAsJsonObject("palette").entrySet().forEach(member ->
                    palette.put(member.getKey(), Identifier.parse(member.getValue().getAsString())));
            require(!palette.isEmpty(), "Building mesh palette is empty");
            Map<String, BuildingEntry> entries = new LinkedHashMap<>();
            for (JsonElement rawEntry : root.getAsJsonArray("entries")) {
                JsonObject object = rawEntry.getAsJsonObject();
                String id = object.get("id").getAsString();
                require(BUILDING_IDS.contains(id) && !entries.containsKey(id), "Unknown or duplicate building mesh: " + id);
                List<BuildingVariant> variants = new ArrayList<>();
                for (JsonElement rawVariant : object.getAsJsonArray("variants")) {
                    JsonObject variant = rawVariant.getAsJsonObject();
                    Map<String, String> match = new LinkedHashMap<>();
                    variant.getAsJsonObject("match").entrySet().forEach(member -> match.put(member.getKey(), member.getValue().getAsString()));
                    List<Face> faces = readFaces(variant.getAsJsonArray("quads"), palette, id);
                    require(!faces.isEmpty(), id + ": empty building mesh variant");
                    variants.add(new BuildingVariant(Map.copyOf(match), faces));
                }
                require(!variants.isEmpty(), id + ": no building mesh variants");
                BuildingEntry entry = new BuildingEntry(id, List.copyOf(variants));
                Block block = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DarkWinter.MOD_ID, id));
                require(block != null && isBuildingModule(block), id + ": building mesh does not refer to a registered module");
                for (BuildingVariant variant : variants) for (var condition : variant.match().entrySet()) {
                    Property<?> property = block.getStateDefinition().getProperty(condition.getKey());
                    require(property != null && property.getValue(condition.getValue()).isPresent(), id + ": unknown building match " + condition);
                }
                for (BlockState state : block.getStateDefinition().getPossibleStates()) matchingVariant(entry, state);
                entries.put(id, entry);
            }
            require(entries.keySet().equals(BUILDING_IDS), "Building meshes must cover all 13 wall, floor and window modules");
            DarkWinter.LOGGER.info("Prepared {} building mesh entries from resource packs", entries.size());
            return new BuildingData(palette, entries);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot load " + BUILDING_RESOURCE + ": " + exception.getMessage(), exception);
        }
    }

    private static boolean isBuildingModule(Block block) {
        return block instanceof WallSegmentBlock || block instanceof FrozenWindowBlock || block instanceof FloorSegmentBlock;
    }

    private static int matchingVariant(BuildingEntry entry, BlockState state) {
        int found = -1;
        for (int index = 0; index < entry.variants().size(); index++) {
            boolean matches = true;
            for (var condition : entry.variants().get(index).match().entrySet()) {
                Property<?> property = state.getBlock().getStateDefinition().getProperty(condition.getKey());
                if (property == null || !propertyValue(state, property).equals(condition.getValue())) matches = false;
            }
            if (matches) {
                require(found == -1, entry.id() + ": overlapping mesh matches for " + state);
                found = index;
            }
        }
        require(found != -1, entry.id() + ": no mesh variant matches " + state);
        return found;
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static float finite(JsonElement element) {
        float value = element.getAsFloat();
        require(Float.isFinite(value), "Non-finite mesh coordinate");
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static BakedFurniture bakeFurniture(Entry entry, Map<String, Identifier> palette, ModelBaker baker) {
        return bakeFurniture(entry, palette, baker, null);
    }

    private static BakedFurniture bakeFurniture(Entry entry, Map<String, Identifier> palette, ModelBaker baker,
                                                 @Nullable SnowUvGrid snowUv) {
        entry = smoothSurfaces(entry);
        final String meshId=entry.id();
        Map<String, Material.Baked> materials = new LinkedHashMap<>();
        for (Part part : entry.parts()) for (Face face : part.faces()) {
            materials.computeIfAbsent(face.texture(), key -> baker.materials().get(new Material(palette.get(key)),
                    () -> "DarkWinter furniture mesh " + meshId));
        }
        Material.Baked particle = materials.values().iterator().next();
        BakedPart[][] parts = new BakedPart[FurnitureBlock.MAX_PARTS][4];
        for (int part = 0; part < FurnitureBlock.MAX_PARTS; part++) {
            for (int facing = 0; facing < 4; facing++) {
                parts[part][facing] = bakePart(part < entry.parts().size() ? entry.parts().get(part).faces() : List.of(),
                        materials, particle, facing, null,
                        snowUv == null || part >= entry.parts().size() ? null : snowUv.atPart(entry.parts().get(part).offset(), facing));
            }
        }
        // Item geometry contains every companion part, uniformly scaled into a 14-unit display cube.
        List<Face> whole = new ArrayList<>();
        float[] minimum = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] maximum = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (Part part : entry.parts()) for (Face face : part.faces()) {
            List<Vertex> vertices = new ArrayList<>(4);
            for (Vertex vertex : face.vertices()) {
                float x = vertex.x() + part.offset().getX() * 16, y = vertex.y() + part.offset().getY() * 16, z = vertex.z() + part.offset().getZ() * 16;
                minimum[0] = Math.min(minimum[0], x); minimum[1] = Math.min(minimum[1], y); minimum[2] = Math.min(minimum[2], z);
                maximum[0] = Math.max(maximum[0], x); maximum[1] = Math.max(maximum[1], y); maximum[2] = Math.max(maximum[2], z);
                vertices.add(new Vertex(x, y, z, vertex.u(), vertex.v(), vertex.nx(), vertex.ny(), vertex.nz()));
            }
            whole.add(new Face(face.texture(), List.copyOf(vertices), face.cutout()));
        }
        float largest = Math.max(maximum[0] - minimum[0], Math.max(maximum[1] - minimum[1], maximum[2] - minimum[2]));
        float[] itemNormalization = {14 / largest, (minimum[0] + maximum[0]) / 2, (minimum[1] + maximum[1]) / 2, (minimum[2] + maximum[2]) / 2};
        return new BakedFurniture(parts, bakePart(whole, materials, particle, 0, itemNormalization, snowUv));
    }

    private static BakedPart bakePart(List<Face> faces, Map<String, Material.Baked> materials, Material.Baked particle,
                                      int facing, float @Nullable [] normalization, @Nullable SnowUvGrid snowUv) {
        var mutable = Renderer.get().mutableMesh();
        QuadEmitter emitter = mutable.emitter();
        List<BakedQuad> vanilla = new ArrayList<>(faces.size());
        for (Face face : faces) {
            List<Vertex> positioned = new ArrayList<>(4);
            for (int vertex = 0; vertex < 4; vertex++) {
                Vertex source = face.vertices().get(vertex);
                float x = source.x(), y = source.y(), z = source.z();
                if (normalization != null) {
                    x = (x - normalization[1]) * normalization[0] + 8;
                    y = (y - normalization[2]) * normalization[0] + 8;
                    z = (z - normalization[3]) * normalization[0] + 8;
                } else {
                    for (int turn = 0; turn < facing; turn++) { float nextX = 16 - z; z = x; x = nextX; }
                }
                emitter.pos(vertex, x / 16, y / 16, z / 16);
                if (face.cutout() && snowUv != null) {
                    float uvX = normalization == null ? x : source.x();
                    float uvZ = normalization == null ? z : source.z();
                    emitter.uv(vertex, (uvX + snowUv.x() * 16) / snowUv.grid(),
                            (uvZ + snowUv.z() * 16) / snowUv.grid());
                } else emitter.uv(vertex, source.u(), source.v());
                positioned.add(new Vertex(x, y, z, source.u(), source.v()));
            }
            float[] normal = normal(positioned);
            for (int vertex = 0; vertex < 4; vertex++) {
                Vertex source = face.vertices().get(vertex);
                float nx=source.nx(), ny=source.ny(), nz=source.nz();
                if (nx*nx+ny*ny+nz*nz<.5f) { nx=normal[0]; ny=normal[1]; nz=normal[2]; }
                else if (normalization==null) for(int turn=0;turn<facing;turn++) { float nextX=-nz; nz=nx; nx=nextX; }
                emitter.normal(vertex,nx,ny,nz);
            }
            Material.Baked material = materials.get(face.texture());
            emitter.materialBake(material, 0);
            if (face.cutout()) {
                emitter.chunkLayer(ChunkSectionLayer.CUTOUT)
                        .itemRenderType(Sheets.cutoutBlockItemSheet())
                        .itemGlintRenderType(Sheets.cutoutBlockItemGlintSheet())
                        .itemGlintSpecialRenderType(Sheets.cutoutBlockItemGlintSpecialSheet());
            }
            emitter.tag(MESH_QUAD_TAG).cullFace(null).tintIndex(-1).ambientOcclusion(TriState.TRUE);
            vanilla.add(emitter.toBakedQuad(material.sprite()));
            emitter.emit();
        }
        Mesh mesh = mutable.immutableCopy();
        int flags = new MeshQuadCollection(mesh).materialFlags();
        return new BakedPart(mesh, List.copyOf(vanilla), particle, flags);
    }

    /** Angle weighting avoids visible bands on curved surfaces and on clipped part boundaries.
     * Material boundaries and corners sharper than forty degrees retain their hard edge. */
    private static Entry smoothSurfaces(Entry entry) {
        Map<VertexKey,List<WeightedNormal>> adjacency=new java.util.HashMap<>();
        for(Part part:entry.parts()) for(Face face:part.faces()) {
            float[] n=normal(face.vertices());
            for(int i=0;i<4;i++) {
                Vertex point=face.vertices().get(i);
                if(i==3 && samePosition(point,face.vertices().get(2))) continue;
                Vertex before=face.vertices().get((i+3)%4), after=face.vertices().get((i+1)%4);
                if(samePosition(point,before)) before=face.vertices().get((i+2)%4);
                if(samePosition(point,after)) after=face.vertices().get((i+2)%4);
                float ax=before.x()-point.x(),ay=before.y()-point.y(),az=before.z()-point.z();
                float bx=after.x()-point.x(),by=after.y()-point.y(),bz=after.z()-point.z();
                float length=(float)Math.sqrt((ax*ax+ay*ay+az*az)*(bx*bx+by*by+bz*bz));
                if(length<1e-10f) continue;
                float angle=(float)Math.acos(Math.clamp((ax*bx+ay*by+az*bz)/length,-1,1));
                adjacency.computeIfAbsent(vertexKey(face.texture(),point,part.offset()),ignored->new ArrayList<>())
                        .add(new WeightedNormal(n[0],n[1],n[2],angle));
            }
        }
        List<Part> parts=new ArrayList<>();
        for(Part part:entry.parts()) {
            List<Face> faces=new ArrayList<>();
            for(Face face:part.faces()) {
                float[] faceNormal=normal(face.vertices());
                List<Vertex> vertices=new ArrayList<>(4);
                for(Vertex v:face.vertices()) {
                    float nx=0,ny=0,nz=0;
                    for(WeightedNormal candidate:adjacency.getOrDefault(vertexKey(face.texture(),v,part.offset()),List.of())) {
                        if(candidate.x()*faceNormal[0]+candidate.y()*faceNormal[1]+candidate.z()*faceNormal[2]<.7660444f) continue;
                        nx+=candidate.x()*candidate.weight(); ny+=candidate.y()*candidate.weight(); nz+=candidate.z()*candidate.weight();
                    }
                    float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
                    if(length<1e-8f) {nx=faceNormal[0];ny=faceNormal[1];nz=faceNormal[2];length=1;}
                    vertices.add(new Vertex(v.x(),v.y(),v.z(),v.u(),v.v(),nx/length,ny/length,nz/length));
                }
                faces.add(new Face(face.texture(),List.copyOf(vertices),face.cutout()));
            }
            parts.add(new Part(part.offset(),List.copyOf(faces)));
        }
        return new Entry(entry.id(),List.copyOf(parts));
    }

    private static boolean samePosition(Vertex a,Vertex b) {
        return Math.abs(a.x()-b.x())<1e-6f && Math.abs(a.y()-b.y())<1e-6f && Math.abs(a.z()-b.z())<1e-6f;
    }

    private static VertexKey vertexKey(String texture,Vertex v,BlockPos offset) {
        return new VertexKey(texture,Math.round((v.x()+offset.getX()*16)*10000d),
                Math.round((v.y()+offset.getY()*16)*10000d),Math.round((v.z()+offset.getZ()*16)*10000d));
    }

    /** Newell normal also handles triangles encoded by a repeated fourth vertex. */
    private static float[] normal(List<Vertex> vertices) {
        float x = 0, y = 0, z = 0;
        for (int i = 0; i < 4; i++) {
            Vertex a = vertices.get(i), b = vertices.get((i + 1) % 4);
            x += (a.y() - b.y()) * (a.z() + b.z());
            y += (a.z() - b.z()) * (a.x() + b.x());
            z += (a.x() - b.x()) * (a.y() + b.y());
        }
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        return length > 0 ? new float[]{x / length, y / length, z / length, length} : new float[]{0, 1, 0, 0};
    }

    private static int facingIndex(Direction facing) {
        return switch (facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
    }

    private record BakedPart(Mesh mesh, List<BakedQuad> quads, Material.Baked particleMaterial,
                             int materialFlags) implements BlockStateModelPart {
        @Override public List<BakedQuad> getQuads(@Nullable Direction side) { return side == null ? quads : List.of(); }
        @Override public boolean useAmbientOcclusion() { return true; }
        @Override public void emitQuads(QuadEmitter emitter, Predicate<@Nullable Direction> cullTest) {
            if (!cullTest.test(null)) mesh.outputTo(emitter);
        }
    }

    private static final class FurnitureBlockModel extends WrapperBlockStateModel implements MeshBackedModel {
        private final String id;
        private final BakedPart part;
        private final @Nullable List<List<BakedPart>> surfaceParts;
        private final BlockPos surfaceOffset;
        private final int snowGrid;

        private FurnitureBlockModel(BlockStateModel wrapped, String id, BakedPart part) {
            super(wrapped); this.id = id; this.part = part;
            this.surfaceParts = null; this.surfaceOffset = BlockPos.ZERO; this.snowGrid = 1;
        }
        private FurnitureBlockModel(BlockStateModel wrapped, String id, List<List<BakedPart>> surfaceParts, BlockPos surfaceOffset, int snowGrid) {
            super(wrapped); this.id = id; this.part = surfaceParts.getFirst().getFirst();
            this.surfaceParts = surfaceParts; this.surfaceOffset = surfaceOffset; this.snowGrid = snowGrid;
        }
        private BakedPart positionedPart(BlockPos pos) {
            if (surfaceParts == null) return part;
            BlockPos origin = pos.subtract(surfaceOffset);
            return surfaceParts.get(surfaceVariant(origin, surfaceParts.size())).get(atlasTileIndex(origin, snowGrid));
        }
        @Override public Mesh mesh() { return part.mesh(); }
        @Override public Mesh mesh(BlockPos pos) { return positionedPart(pos).mesh(); }
        @Override public String furnitureId() { return id; }
        // The position-less vanilla collection path has a stable base fallback; chunk rendering uses emitQuads below.
        @Override public void collectParts(RandomSource random, List<BlockStateModelPart> output) { output.add(part); }
        @Override public Material.Baked particleMaterial() { return part.particleMaterial(); }
        @Override public int materialFlags() { return part.materialFlags(); }
        @Override public boolean hasMaterialFlag(int flag) { return (part.materialFlags() & flag) != 0; }
        @Override public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) { return positionedPart(pos).particleMaterial(); }
        @Override public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) { return positionedPart(pos).materialFlags(); }
        @Override public boolean hasMaterialFlag(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, int flag) { return (positionedPart(pos).materialFlags() & flag) != 0; }
        @Override public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state,
                                       RandomSource random, Predicate<@Nullable Direction> cullTest) { positionedPart(pos).emitQuads(emitter, cullTest); }
        @Override public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) { return positionedPart(pos).mesh(); }
    }

    private static final class FurnitureItemModel implements ItemModel, MeshBackedModel {
        private final String id;
        private final BakedPart geometry;
        private final @Nullable BakedPart snowGeometry;
        private final ModelRenderProperties properties;
        private final Matrix4fc transformation;

        private FurnitureItemModel(String id, BakedPart geometry, @Nullable BakedPart snowGeometry,
                                   ModelRenderProperties properties, Matrix4fc transformation) {
            this.id = id; this.geometry = geometry; this.snowGeometry = snowGeometry;
            this.properties = properties; this.transformation = transformation;
        }
        @Override public Mesh mesh() { return geometry.mesh(); }
        @Override public Mesh mesh(ItemStack stack) { return selectedGeometry(stack).mesh(); }
        private BakedPart selectedGeometry(ItemStack stack) {
            return snowGeometry != null && SnowVariantFurnitureBlock.isSnowy(stack) ? snowGeometry : geometry;
        }
        @Override public String furnitureId() { return id; }
        @Override public void update(ItemStackRenderState output, ItemStack stack, ItemModelResolver resolver,
                                     ItemDisplayContext displayContext, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
            output.appendModelIdentityElement(this);
            BakedPart selectedGeometry = selectedGeometry(stack);
            if (snowGeometry != null) output.appendModelIdentityElement(selectedGeometry.mesh());
            var layer = output.newLayer();
            layer.setLocalTransform(transformation);
            properties.applyToLayer(layer, displayContext);
            if (stack.hasFoil()) {
                layer.setFoilType(ItemStackRenderState.FoilType.STANDARD);
                output.appendModelIdentityElement(ItemStackRenderState.FoilType.STANDARD);
                output.setAnimated();
            }
            selectedGeometry.mesh().outputTo(layer.emitter());
            if ((selectedGeometry.materialFlags() & BakedQuad.FLAG_ANIMATED) != 0) output.setAnimated();
        }
    }
}
