// Resource-pack-aware polygon meshes baked once into ordinary chunk and item geometry.
package com.dark.winter.client;

import com.dark.winter.DarkWinter;
import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.DiningChairBlock;
import com.dark.winter.block.PotFillFurnitureBlock;
import com.dark.winter.block.RoofSegmentBlock;
import com.dark.winter.block.SnowVariantFurnitureBlock;
import com.dark.winter.block.FloorSegmentBlock;
import com.dark.winter.block.FrozenWindowBlock;
import com.dark.winter.block.WallSegmentBlock;
import com.dark.winter.placement.AnchorRuntime;
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
import com.dark.winter.block.PanelDoorBlock;
import com.dark.winter.block.CurtainedWindowBlock;
import com.dark.winter.block.FenceGateFurnitureBlock;
import com.dark.winter.block.StorageFurnitureBlock;
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
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.properties.Property;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Resource-pack-aware polygon meshes baked once into ordinary chunk and item geometry. */
public final class FurnitureMeshModels {
    public static final Identifier RESOURCE = Identifier.fromNamespaceAndPath("darkwinter", "furniture/meshes.json");
    public static final Identifier BUILDING_RESOURCE = Identifier.fromNamespaceAndPath("darkwinter", "furniture/building-meshes.json");
    public static final Identifier POT_FILL_RESOURCE = Identifier.fromNamespaceAndPath("darkwinter", "furniture/cooking-pot-fill.json");
    private static final Set<String> BUILDING_IDS = Set.of("wall_segment", "weathered_wall_segment_gray",
            "weathered_wall_segment_dark", "weathered_wall_segment_patchwork", "weathered_wall_segment_painted",
            "floor_segment", "weathered_floor_segment_gray", "weathered_floor_segment_dark",
            "weathered_floor_segment_patchwork", "weathered_floor_segment_painted", "frozen_window",
            "frozen_window_gray", "frozen_window_dark", "cabin_wall_segment");
    private static final int CABIN_QUAD_BUDGET = 11500;
    private static final int INTERIOR_QUAD_BUDGET = 6500;
    private static final int HOME_FURNITURE_QUAD_BUDGET = 14400;
    private static final Set<String> HOME_FURNITURE_IDS = Set.of("worn_sofa", "wooden_wardrobe", "old_fridge",
            "kitchen_sink", "old_tv", "interior_pine_bed", "interior_kitchen_hutch", "interior_rocking_chair",
            "interior_boot_bench", "interior_landline_phone", "interior_linen_chest", "interior_long_kitchen_shelf",
            "interior_kitchen_worktable", "interior_long_wall_shelf", "interior_cabin_dining_table",
            "interior_spindle_chair", "interior_cabin_pendant", "interior_wall_shelf", "interior_wide_dresser");
    public static final int MESH_QUAD_TAG = 0x44574D;
    // Growth of the outdoor catalogue has its own bounded allocation; the
    // established furniture allocation and each object's bake limit stay fixed.
    private static final Set<String> FOREST_FOOD_IDS = Set.of("cattail_clump", "cattail_rhizome", "juniper_bush", "cranberry_patch", "lingonberry_patch", "rosehip_bush", "juniper_bush_no_berries", "cranberry_patch_no_berries", "lingonberry_patch_no_berries", "rosehip_bush_no_berries");
    private static final Set<String> OUTDOOR_FENCE_IDS = outdoorFenceIds();
    private static final Set<String> DEADWOOD_B_IDS = Set.of("spruce_stump", "fallen_spruce_log", "forest_branch_pile");
    private static final Set<String> NORTHERN_PROP_IDS = Set.of("wall_lamp", "wall_mailbox", "kitchen_stove", "kerosene_lantern");
    private static final Set<String> SUPPLY_PROP_IDS = Set.of("firewood_crib", "camp_cooking_pot", "camp_wide_pot", "fuel_jerrycan", "metal_bucket", "oval_bucket", "metal_toolbox", "carpenter_tool_tote", "sleeping_bag_roll", "sleeping_bag_sack", "sleeping_bag_folded", "canned_meat", "canned_fish", "canned_beans", "oat_carton", "cracker_box", "ration_bundle", "water_bottle", "canvas_canteen", "vacuum_flask", "matchbox", "folded_newspaper", "tied_newspapers", "rolled_newspapers");
    private static final Map<String, Identifier> SUPPLY_PRIVATE_PALETTE = Map.of(
            "camping_cloth", Identifier.fromNamespaceAndPath("darkwinter", "block/decor/camping_cloth"),
            "provision_labels", Identifier.fromNamespaceAndPath("darkwinter", "block/decor/provision_labels"),
            "camping_galvanized", Identifier.fromNamespaceAndPath("darkwinter", "block/decor/camping_galvanized"));
    private static final Set<String> REFINEMENT_IDS = Set.of("fuel_jerrycan", "metal_bucket", "metal_toolbox", "carpenter_tool_tote", "sleeping_bag_sack", "sleeping_bag_folded", "canned_meat", "canned_fish", "cracker_box", "ration_bundle", "water_bottle", "canvas_canteen", "vacuum_flask", "rolled_newspapers");
    private static final Map<String, Identifier> REFINEMENT_PRIVATE_PALETTE = Map.of(
            "northern_refined_materials", Identifier.fromNamespaceAndPath("darkwinter", "block/decor/northern_refined_materials"),
            "northern_refined_labels", Identifier.fromNamespaceAndPath("darkwinter", "block/decor/northern_refined_labels"));
    private static final Map<String, Identifier> CAMP_PROP_PRIVATE_PALETTE = Map.of(
            "camp_pot_snow", Identifier.fromNamespaceAndPath("darkwinter", "block/decor/camp_pot_snow"),
            "camp_pot_water", Identifier.fromNamespaceAndPath("darkwinter", "block/decor/camp_pot_water"),
            "campfire_fire", Identifier.fromNamespaceAndPath("darkwinter", "block/decor/campfire_fire"),
            "campfire_materials", Identifier.fromNamespaceAndPath("darkwinter", "block/decor/campfire_materials"));
    private static boolean registered;
    private static volatile MeshData previewData;

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
                    previewData = data;
                    plugin.modifyBlockModelAfterBake().register((model, context) -> {
                        if (context.state().getBlock() instanceof com.dark.winter.block.UtilityWireBlock)
                            return RoadFurnitureModels.wire(model, context.state(), context.baker());
                        if (context.state().getBlock() instanceof FurnitureBlock block) {
                            String id = block.definition().id();
                            int part = context.state().getValue(FurnitureBlock.PART);
                            int facing = facingIndex(context.state().getValue(FurnitureBlock.FACING));
                            if (block instanceof StorageFurnitureBlock storage) {
                                var baked = data.bakeStorage(storage, context.baker());
                                return new FurnitureBlockModel(model, id, baked.body()[part][facing],
                                        baked.leaves()[part]);
                            }
                            if (block instanceof FenceGateFurnitureBlock gate) {
                                var baked = data.bakeGate(gate, context.baker(), context.state().getValue(SnowVariantFurnitureBlock.SNOWY));
                                return new FurnitureBlockModel(model, id, baked.frame()[part][facing], baked.leaf()[part]);
                            }
                            if (block instanceof CurtainedWindowBlock) {
                                BakedCurtains curtains = data.bakeCurtains(id, context.baker());
                                return new FurnitureBlockModel(model, id, curtains.frame()[part][facing], curtains.cloth()[part]);
                            }
                            if (block instanceof PanelDoorBlock) {
                                BakedDoor door = data.bakeDoor(id, context.baker());
                                return new FurnitureBlockModel(model, id, door.frame()[part][facing],
                                        List.of(door.leaf()[part][facing]));
                            }
                            if (block instanceof RoofSegmentBlock && context.state().getValue(RoofSegmentBlock.SPAN) != RoofSegmentBlock.FULL_SPAN) {
                                BakedPart roof = data.bakeFittedRoof(id, context.state(), context.baker())[part][facing];
                                return new FurnitureBlockModel(model, id, roof);
                            }
                            boolean snowy = block instanceof SnowVariantFurnitureBlock
                                    && context.state().getValue(SnowVariantFurnitureBlock.SNOWY);
                            BakedFurniture baked = block instanceof PotFillFurnitureBlock
                                    ? data.bakePot(context.state().getValue(PotFillFurnitureBlock.POT_FILL), context.baker())
                                    : data.bake(id, context.baker(), snowy);
                            if (block instanceof DiningChairBlock && part == 0) {
                                var geometry = baked.parts()[part][facing];
                                var empty = new BakedPart(Renderer.get().mutableMesh().immutableCopy(), List.of(),
                                        geometry.particleMaterial(), 0);
                                return new FurnitureBlockModel(model, id, empty, List.of(geometry));
                            }
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
                                return RoadFurnitureModels.wrap(new FurnitureBlockModel(model, id, List.copyOf(variants), offset, grid), id, context.state(), context.baker());
                            }
                            return RoadFurnitureModels.wrap(new FurnitureBlockModel(model, id, baked.parts()[part][facing]), id, context.state(), context.baker());
                        }
                        if (!isBuildingModule(context.state().getBlock())) return model;
                        Identifier blockId = BuiltInRegistries.BLOCK.getKey(context.state().getBlock());
                        if (!blockId.getNamespace().equals(DarkWinter.MOD_ID) || !BUILDING_IDS.contains(blockId.getPath())) return model;
                        String id = blockId.getPath();
                        return new FurnitureBlockModel(model, id, data.buildings.part(id, context.state(), context.baker()));
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
                        Map<PotFillFurnitureBlock.Fill, BakedPart> potGeometry = new LinkedHashMap<>();
                        if (ModFurniture.get(id) instanceof PotFillFurnitureBlock) {
                            for (PotFillFurnitureBlock.Fill fill : PotFillFurnitureBlock.Fill.values())
                                potGeometry.put(fill, data.bakePot(fill, baker).item());
                        }
                        return new FurnitureItemModel(id, itemGeometry, snowGeometry, Map.copyOf(potGeometry), properties, new Matrix4f(transformation));
                    });
                });
    }

    private record Vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        Vertex(float x, float y, float z, float u, float v) { this(x,y,z,u,v,0,0,0); }
    }
    private record Face(String texture, List<Vertex> vertices, boolean cutout) {
        private Face(String texture, List<Vertex> vertices) { this(texture, vertices, false); }
    }
    record PreviewQuad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, Vec3 normal) {}

    static List<PreviewQuad> previewQuads(BlockState state) {
        MeshData data = previewData;
        if (data == null || !(state.getBlock() instanceof FurnitureBlock furniture)) return List.of();
        String id = furniture.definition().id();
        Direction facing = state.getValue(FurnitureBlock.FACING);
        int turns = facingIndex(facing);
        boolean snowy = furniture instanceof SnowVariantFurnitureBlock && state.getValue(SnowVariantFurnitureBlock.SNOWY);
        PotFillFurnitureBlock.Fill fill = furniture instanceof PotFillFurnitureBlock
                ? state.getValue(PotFillFurnitureBlock.POT_FILL) : null;
        return data.previews.computeIfAbsent(new PreviewKey(id, facing, snowy, fill), key -> {
            Entry entry = fill != null ? data.potEntries.get(fill)
                    : snowy ? data.snowEntries.getOrDefault(id, data.entries.get(id)) : data.entries.get(id);
            if (entry == null) return List.of();
            var quads = new ArrayList<PreviewQuad>();
            for (int index = 0; index < entry.parts().size(); index++) {
                Vec3 offset = Vec3.atLowerCornerOf(furniture.partOffset(index, facing));
                for (Face face : entry.parts().get(index).faces()) {
                    // A flat silhouette does not reproduce transparent texels from cutout cards.
                    if (face.cutout()) continue;
                    var vertices = face.vertices();
                    float[] normal = normal(vertices);
                    Vec3 direction = new Vec3(normal[0], normal[1], normal[2]);
                    for (int turn = 0; turn < turns; turn++) {
                        direction = new Vec3(-direction.z, direction.y, direction.x);
                    }
                    quads.add(new PreviewQuad(previewVertex(vertices.get(0), turns, offset),
                            previewVertex(vertices.get(1), turns, offset),
                            previewVertex(vertices.get(2), turns, offset),
                            previewVertex(vertices.get(3), turns, offset), direction));
                }
            }
            return List.copyOf(quads);
        });
    }

    private static Vec3 previewVertex(Vertex vertex, int turns, Vec3 offset) {
        float x = vertex.x(), z = vertex.z();
        for (int turn = 0; turn < turns; turn++) {
            float previousX = x;
            x = 16 - z;
            z = previousX;
        }
        return offset.add(x / 16, vertex.y() / 16, z / 16);
    }

    private record PreviewKey(String id, Direction facing, boolean snowy, PotFillFurnitureBlock.@Nullable Fill fill) {}
    private record VertexKey(String texture, long x, long y, long z) {}
    private record WeightedNormal(float x, float y, float z, float weight) {}
    private record Part(BlockPos offset, List<Face> faces) {}
    private record Entry(String id, List<Part> parts) {}
    private record BakedFurniture(BakedPart[][] parts, BakedPart item) {}
    private record BakedDoor(BakedPart[][] frame, BakedPart[][] leaf) {}
    private record BakedCurtains(BakedPart[][] frame, CurtainMesh[] cloth) {}
    public record GateLeaf(List<BlockStateModelPart> solid, List<BlockStateModelPart> snow) {}
    private record BakedGate(BakedPart[][] frame, GateLeaf[] leaf) {}
    private record GateModelKey(String id, boolean snowy) {}
    public record StorageLeaves(List<BlockStateModelPart> first, List<BlockStateModelPart> second) {
        public List<BlockStateModelPart> at(int index) { return index == 0 ? first : second; }
    }
    private record BakedStorage(BakedPart[][] body, StorageLeaves[] leaves) {}
    private record RoofMeshKey(String id, int span) {}
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
    private record FloorMeshKey(String id, int variant, int spanX, int spanZ) {}
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
        private final Map<FloorMeshKey, BakedPart> fittedFloors = new ConcurrentHashMap<>();

        private BuildingData(Map<String, Identifier> palette, Map<String, BuildingEntry> entries) {
            this.palette = Map.copyOf(palette); this.entries = Map.copyOf(entries);
        }

        private BakedPart part(String id, BlockState state, ModelBaker baker) {
            if (state.getBlock() instanceof FloorSegmentBlock) {
                int spanX = state.getValue(FloorSegmentBlock.SPAN_X);
                int spanZ = state.getValue(FloorSegmentBlock.SPAN_Z);
                if (spanX != FloorSegmentBlock.FULL_SPAN || spanZ != FloorSegmentBlock.FULL_SPAN) {
                    BuildingEntry source = entries.get(id);
                    int variant = matchingVariant(source, state);
                    return fittedFloors.computeIfAbsent(new FloorMeshKey(id, variant, spanX, spanZ), key -> {
                        List<Face> faces = fitFloor(source.variants().get(key.variant()).faces(), key.spanX(), key.spanZ());
                        Entry fitted = smoothSurfaces(new Entry(id, List.of(new Part(BlockPos.ZERO, faces))));
                        Map<String, Material.Baked> materials = resolveMaterials(fitted, palette, baker);
                        return bakePart(fitted.parts().getFirst().faces(), materials, materials.values().iterator().next(),
                                0, null, null);
                    });
                }
            }
            return bake(id, baker).part(state);
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
        private final Map<PreviewKey, List<PreviewQuad>> previews = new ConcurrentHashMap<>();
        private final Map<String, BakedFurniture> baked = new ConcurrentHashMap<>();
        private final Map<String, BakedDoor> doors = new ConcurrentHashMap<>();
        private final Map<String, BakedCurtains> curtains = new ConcurrentHashMap<>();
        private final Map<GateModelKey, BakedGate> gates = new ConcurrentHashMap<>();
        private final Map<StorageFurnitureBlock.Kind, BakedStorage> storage = new ConcurrentHashMap<>();
        private final Map<RoofMeshKey, BakedPart[][]> fittedRoofs = new ConcurrentHashMap<>();
        private final BuildingData buildings;
        private final Map<String, Entry> snowEntries;
        private final Map<String, BakedFurniture> snowBaked = new ConcurrentHashMap<>();
        private final Map<String, List<SurfaceEntry>> surfaceEntries;
        private final Map<SurfaceKey, BakedFurniture> surfaceBaked = new ConcurrentHashMap<>();
        private final Map<String, Integer> snowUvGrids;
        private final Map<PotFillFurnitureBlock.Fill, Entry> potEntries;
        private final Map<PotFillFurnitureBlock.Fill, BakedFurniture> potBaked = new ConcurrentHashMap<>();

        private MeshData(Map<String, Identifier> palette, Map<String, Entry> entries,
                         Map<String, Entry> snowEntries, Map<String, List<SurfaceEntry>> surfaceEntries,
                         Map<String, Integer> snowUvGrids, Map<PotFillFurnitureBlock.Fill, Entry> potEntries, BuildingData buildings) {
            this.palette = Map.copyOf(palette);
            this.entries = Map.copyOf(entries);
            this.snowEntries = Map.copyOf(snowEntries);
            this.surfaceEntries = Map.copyOf(surfaceEntries);
            this.snowUvGrids = Map.copyOf(snowUvGrids);
            this.potEntries = Map.copyOf(potEntries);
            this.buildings = buildings;
        }

        private BakedFurniture bake(String id, ModelBaker baker) {
            return baked.computeIfAbsent(id, ignored -> bakeFurniture(entries.get(id), palette, baker));
        }

        private BakedDoor bakeDoor(String id, ModelBaker baker) {
            return doors.computeIfAbsent(id, ignored -> {
                Entry entry = smoothSurfaces(entries.get(id));
                Map<String, Material.Baked> materials = resolveMaterials(entry, palette, baker);
                Material.Baked particle = materials.values().iterator().next();
                List<Part> frame = new ArrayList<>(), leaf = new ArrayList<>();
                // construction_openings.door emits jamb/sill first, then slab and hardware.
                // cabin_openings.entry_door appends its stationary trim after that same leaf.
                boolean cabin = id.equals("cabin_panel_door");
                int[] firstLeaf = {50, 24}, endLeaf = {381, 219};
                int[] expected = cabin ? new int[]{415, 279} : endLeaf;
                require(entry.parts().size() == 2, id + ": expected two panel door parts");
                for (int part = 0; part < 2; part++) {
                    Part source = entry.parts().get(part);
                    List<Face> faces = source.faces();
                    require(faces.size() == expected[part], id + ": door geometry changed, check the leaf partition");
                    List<Face> stationary = new ArrayList<>(faces.subList(0, firstLeaf[part]));
                    stationary.addAll(faces.subList(endLeaf[part], faces.size()));
                    frame.add(new Part(source.offset(), List.copyOf(stationary)));
                    leaf.add(new Part(source.offset(), faces.subList(firstLeaf[part], endLeaf[part])));
                }
                return new BakedDoor(bakeBlockParts(new Entry(id, frame), materials, particle, null, 1),
                        bakeBlockParts(new Entry(id, leaf), materials, particle, null, 1));
            });
        }

        private BakedStorage bakeStorage(StorageFurnitureBlock block, ModelBaker baker) {
            String id = block.definition().id();
            return storage.computeIfAbsent(block.kind(), kind -> {
                Entry entry = smoothSurfaces(entries.get(id));
                Map<String, Material.Baked> materials = resolveMaterials(entry, palette, baker);
                Material.Baked particle = materials.values().iterator().next();
                var body = new ArrayList<Part>();
                var leaves = new StorageLeaves[FurnitureBlock.MAX_PARTS];
                for (int part = 0; part < entry.parts().size(); part++) {
                    Part source = entry.parts().get(part);
                    List<Face> fixed = new ArrayList<>(), first = new ArrayList<>(), second = new ArrayList<>();
                    require(source.faces().size() == storageFaceCount(kind, part), id + ": check the storage leaf partition");
                    for (int i = 0; i < source.faces().size(); i++) {
                        Face face = source.faces().get(i);
                        switch (storageFace(kind, part, i)) {
                            case 0 -> fixed.add(face);
                            case 1 -> first.add(face);
                            case 2 -> second.add(face);
                            default -> { } // Old false front, hidden by the closed doors.
                        }
                    }
                    addStorageInterior(kind, part, fixed, first, second);
                    body.add(new Part(source.offset(), List.copyOf(fixed)));
                    leaves[part] = new StorageLeaves(first.isEmpty() ? List.of() : List.of(bakePart(first, materials, particle, 0, null, null)),
                            second.isEmpty() ? List.of() : List.of(bakePart(second, materials, particle, 0, null, null)));
                }
                return new BakedStorage(bakeBlockParts(new Entry(id, body), materials, particle, null, 1), leaves);
            });
        }

        private BakedGate bakeGate(FenceGateFurnitureBlock gate, ModelBaker baker, boolean snowy) {
            String id = gate.definition().id();
            return gates.computeIfAbsent(new GateModelKey(id, snowy), ignored -> {
                Entry entry = smoothSurfaces((snowy ? snowEntries : entries).get(id));
                Map<String, Material.Baked> materials = resolveMaterials(entry, palette, baker);
                Material.Baked particle = materials.values().iterator().next();
                List<Part> frame = new ArrayList<>();
                var leaves = new GateLeaf[FurnitureBlock.MAX_PARTS];
                for (int part = 0; part < entry.parts().size(); part++) {
                    Part source = entry.parts().get(part);
                    List<Face> stationary = new ArrayList<>(), leaf = new ArrayList<>(), snow = new ArrayList<>();
                    for (Face face : source.faces()) {
                        int origin = source.offset().getX() * 16;
                        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
                        for (Vertex vertex : face.vertices()) {
                            double x = origin + vertex.x();
                            min = Math.min(min, x);
                            max = Math.max(max, x);
                        }
                        if (!gate.isLeaf(min, max)) stationary.add(face);
                        else if (face.cutout()) snow.add(face);
                        else leaf.add(face);
                    }
                    frame.add(new Part(source.offset(), List.copyOf(stationary)));
                    leaves[part] = new GateLeaf(leaf.isEmpty() ? List.of() : List.of(bakePart(leaf, materials, particle, 0, null, null)),
                            snow.isEmpty() ? List.of() : List.of(bakePart(snow, materials, particle, 0, null, null)));
                }
                return new BakedGate(bakeBlockParts(new Entry(id, frame), materials, particle, null, 1), leaves);
            });
        }

        private BakedCurtains bakeCurtains(String id, ModelBaker baker) {
            return curtains.computeIfAbsent(id, ignored -> {
                Entry entry = smoothSurfaces(entries.get(id));
                Map<String, Material.Baked> materials = resolveMaterials(entry, palette, baker);
                Material.Baked particle = materials.values().iterator().next();
                List<Part> frame = new ArrayList<>();
                var cloth = new CurtainMesh[FurnitureBlock.MAX_PARTS];
                int height = entry.parts().size() * 16;
                for (int part = 0; part < entry.parts().size(); part++) {
                    Part source = entry.parts().get(part);
                    List<Face> stationary = new ArrayList<>(), fabric = new ArrayList<>(), bindings = new ArrayList<>();
                    for (Face face : source.faces()) {
                        if (face.texture().equals("cabin_linen")) fabric.add(face);
                        else if (isCurtainBinding(face)) bindings.add(face);
                        else stationary.add(face);
                    }
                    frame.add(new Part(source.offset(), List.copyOf(stationary)));
                    cloth[part] = new CurtainMesh(
                            bakePart(fabric, materials, particle, 0, null, null).quads(),
                            bakePart(bindings, materials, particle, 0, null, null).quads(),
                            source.offset().getY(), height);
                }
                return new BakedCurtains(bakeBlockParts(new Entry(id, frame), materials, particle, null, 1), cloth);
            });
        }

        private BakedPart[][] bakeFittedRoof(String id, BlockState state, ModelBaker baker) {
            RoofMeshKey key = new RoofMeshKey(id, state.getValue(RoofSegmentBlock.SPAN));
            return fittedRoofs.computeIfAbsent(key, ignored -> {
                Entry entry = smoothSurfaces(entries.get(id));
                Map<String, Material.Baked> materials = resolveMaterials(entry, palette, baker);
                return bakeBlockParts(entry, materials, materials.values().iterator().next(), null,
                        RoofSegmentBlock.width(state));
            });
        }

        private BakedFurniture bakePot(PotFillFurnitureBlock.Fill fill, ModelBaker baker) {
            if (fill == PotFillFurnitureBlock.Fill.CLOSED) return bake(PotFillFurnitureBlock.ID, baker);
            return potBaked.computeIfAbsent(fill, ignored -> bakeFurniture(potEntries.get(fill), palette, baker));
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
            requireSupplyPalette(palette);
            Map<String, Entry> entries = new LinkedHashMap<>();
            Map<String, Entry> snowEntries = new LinkedHashMap<>();
            Map<String, List<SurfaceEntry>> surfaceEntries = new LinkedHashMap<>();
            Map<String, Integer> snowUvGrids = new LinkedHashMap<>();
            int quadCount = 0;
            int outdoorFenceQuads = 0;
            int deadwoodQuads = 0;
            int northernPropQuads = 0;
            int supplyPropQuads = 0;
            int forestFoodQuads = 0;
            int constructionQuads = 0;
            int cabinQuads = 0;
            int interiorQuads = 0;
            int homeFurnitureQuads = 0;
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
                    List<Face> faces = readFaces(part.getAsJsonArray("quads"), palette, id, false, parts.size());
                    quadCount += faces.size();
                    parts.add(new Part(new BlockPos(coordinates[0], coordinates[1], coordinates[2]), List.copyOf(faces)));
                }
                require(!parts.isEmpty() && parts.size() <= FurnitureBlock.MAX_PARTS, id + ": invalid mesh part count");
                require(parts.stream().anyMatch(part -> !part.faces().isEmpty()), id + ": empty furniture mesh");
                entries.put(id, new Entry(id, List.copyOf(parts)));
                require(!id.equals("northern_campfire") || faceCount(entries.get(id)) <= 2500,
                        "K01 exceeds its bounded 2500-quad allocation");
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
                        if (id.equals("snowy_shrub")) snowFaces = shrubSnowFaces(base, snowFaces);
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
                if (NORTHERN_PROP_IDS.contains(id)) northernPropQuads += quadCount - entryStart;
                if (SUPPLY_PROP_IDS.contains(id)) supplyPropQuads += quadCount - entryStart;
                if (FOREST_FOOD_IDS.contains(id)) forestFoodQuads += quadCount - entryStart;
                FurnitureBlock registered = ModFurniture.get(id);
                if (id.startsWith("cabin_")) cabinQuads += quadCount - entryStart;
                else if (HOME_FURNITURE_IDS.contains(id)) homeFurnitureQuads += quadCount - entryStart;
                else if (id.startsWith("interior_")) interiorQuads += quadCount - entryStart;
                else if (registered != null && registered.definition().group().equals("construction"))
                    constructionQuads += quadCount - entryStart;
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
            Map<PotFillFurnitureBlock.Fill, Entry> potEntries = readPotFills(resources, palette, entries.get(PotFillFurnitureBlock.ID));
            int potFillQuads = potEntries.values().stream().mapToInt(FurnitureMeshModels::faceCount).sum();
            quadCount += potFillQuads;
            supplyPropQuads += potFillQuads;
            DarkWinter.LOGGER.info("Prepared {} furniture mesh entries ({} quads including {} pot-fill quads) from resource packs", entries.size(), quadCount, potFillQuads);
            int establishedQuads = quadCount - constructionQuads - cabinQuads - interiorQuads - homeFurnitureQuads;
            require(cabinQuads <= CABIN_QUAD_BUDGET, "Cabin modules exceed their " + CABIN_QUAD_BUDGET + "-quad budget");
            require(interiorQuads <= INTERIOR_QUAD_BUDGET, "Interior meshes exceed their " + INTERIOR_QUAD_BUDGET + "-quad budget");
            require(homeFurnitureQuads <= HOME_FURNITURE_QUAD_BUDGET, "Home furniture meshes exceed their "
                    + HOME_FURNITURE_QUAD_BUDGET + "-quad budget");
            require(constructionQuads <= 5000, "Construction modules exceed their bounded 5000-quad allocation");
            require(establishedQuads - outdoorFenceQuads - deadwoodQuads - northernPropQuads - supplyPropQuads - forestFoodQuads <= 100000,
                    "Established furniture meshes and snow overlays exceed the existing 100000-quad budget");
            require(deadwoodQuads <= 10000, "Deadwood B exceeds its bounded 10000-quad allocation");
            require(outdoorFenceQuads <= 20000, "Outdoor fences exceed their separate 20000-quad budget");
            require(establishedQuads - northernPropQuads - supplyPropQuads - forestFoodQuads <= 120000, "Combined furniture and outdoor fences exceed the 120000-quad budget");
            require(northernPropQuads <= 8000, "Four selected northern props exceed their bounded 8000-quad allocation");
            require(establishedQuads - supplyPropQuads - forestFoodQuads <= 128000, "Furniture with four northern props exceeds the 128000-quad budget");
            require(supplyPropQuads <= 23000, "Selected D8 and northern supplies exceed their bounded 23000-quad allocation");
            require(establishedQuads - forestFoodQuads <= 151000, "Furniture with northern supplies exceeds the 151000-quad budget");
            require(forestFoodQuads <= 30000, "Selected forest-food plants exceed their bounded 30000-quad allocation");
            require(establishedQuads + constructionQuads <= 181000, "Combined furniture, construction and selected forest-food plants exceed the 181000-quad budget");
            return new MeshData(palette, entries, snowEntries, surfaceEntries, snowUvGrids, potEntries, readBuildings(resources));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot load " + RESOURCE + ": " + exception.getMessage(), exception);
        }
    }

    private static int faceCount(Entry entry) {
        return entry.parts().stream().mapToInt(part -> part.faces().size()).sum();
    }

    private static Map<PotFillFurnitureBlock.Fill, Entry> readPotFills(ResourceManager resources,
            Map<String, Identifier> palette, Entry closed) throws IOException {
        try (Reader reader = resources.openAsReader(POT_FILL_RESOURCE)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            require(root.keySet().equals(Set.of("schema", "id", "variants")) && root.get("schema").getAsInt() == 1
                            && root.get("id").getAsString().equals(PotFillFurnitureBlock.ID), "Expected exact T01 pot-fill resource schema 1");
            JsonObject variants = root.getAsJsonObject("variants");
            require(variants.keySet().equals(Set.of("snow", "water")), "T01 requires exactly snow and water alternatives");
            require(closed != null, "T01 closed mesh is missing");
            Set<String> exteriorMaterials = new java.util.HashSet<>();
            closed.parts().forEach(part -> part.faces().forEach(face -> exteriorMaterials.add(face.texture())));
            Map<PotFillFurnitureBlock.Fill, Entry> result = new LinkedHashMap<>();
            for (PotFillFurnitureBlock.Fill fill : List.of(PotFillFurnitureBlock.Fill.SNOW, PotFillFurnitureBlock.Fill.WATER)) {
                String name = fill.getSerializedName();
                JsonObject variant = variants.getAsJsonObject(name);
                require(variant.keySet().equals(Set.of("parts")), "T01 " + name + ": expected only full parts");
                JsonArray rawParts = variant.getAsJsonArray("parts");
                require(rawParts.size() == closed.parts().size(), "T01 fill changes part count");
                List<Part> parts = new ArrayList<>();
                for (int index = 0; index < rawParts.size(); index++) {
                    JsonObject part = rawParts.get(index).getAsJsonObject();
                    require(part.keySet().equals(Set.of("offset", "quads")), "T01 fill has unknown part fields");
                    JsonArray offset = part.getAsJsonArray("offset");
                    BlockPos expected = closed.parts().get(index).offset();
                    require(offset.size() == 3 && offset.get(0).getAsDouble() == expected.getX()
                                    && offset.get(1).getAsDouble() == expected.getY() && offset.get(2).getAsDouble() == expected.getZ(),
                            "T01 fill changes its saved footprint");
                    List<Face> faces = readFaces(part.getAsJsonArray("quads"), palette, PotFillFurnitureBlock.ID);
                    require(faces.stream().noneMatch(Face::cutout), "T01 fill must remain opaque");
                    require(faces.stream().allMatch(face -> exteriorMaterials.contains(face.texture())
                                    || face.texture().equals("camp_pot_" + name)),
                            "T01 fill must preserve its exterior materials and use only its own filling");
                    require(faces.stream().noneMatch(face -> face.texture().equals("camp_pot_" + (name.equals("snow") ? "water" : "snow"))),
                            "T01 fill uses the opposite filling material");
                    parts.add(new Part(expected, faces));
                }
                Entry entry = new Entry(PotFillFurnitureBlock.ID, List.copyOf(parts));
                require(faceCount(entry) > 0 && faceCount(entry) <= 5000, "T01 fill exceeds its existing object budget");
                require(parts.stream().flatMap(part -> part.faces().stream()).anyMatch(face -> face.texture().equals("camp_pot_" + name)),
                        "T01 fill is missing its visible filling material");
                result.put(fill, entry);
            }
            require(result.values().stream().mapToInt(FurnitureMeshModels::faceCount).sum() <= 3000,
                    "T01 snow and water alternatives exceed their bounded 3000-quad allocation");
            return Map.copyOf(result);
        }
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
            requireSupplyMaterial(base.id(), member.getKey(), palette);
            requireSupplyMaterial(base.id(), target, palette);
            remap.put(member.getKey(), target);
        }
        List<Part> parts = new ArrayList<>();
        for (Part part : base.parts()) {
            List<Face> faces = part.faces().stream()
                    .map(face -> {
                        String target = remap.getOrDefault(face.texture(), face.texture());
                        requireSupplyFace(base.id(), target, palette, face.vertices());
                        return new Face(target, face.vertices(), face.cutout());
                    }).toList();
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
            if (base.id().equals("snowy_shrub")) snow = shrubSnowFaces(part, snow);
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

    /** Replace the shrub's old opaque blobs with bounded pillows on its real upward twig faces.
     * Runs once per resource/surface variant. Picking boxes never become visual canopy snow. */
    private static List<Face> shrubSnowFaces(Part base,List<Face> authored){
        var result=new ArrayList<Face>();
        // Keep any explicitly authored ground contact; a shrub's foliage remains independently open.
        if(base.offset().getY()==0)for(var face:authored)
            if(face.vertices().stream().allMatch(v->v.y()<=2.02f))result.add(face);
        if(authored.isEmpty())return List.copyOf(result);
        String texture=authored.getFirst().texture();
        var accepted=new ArrayList<com.winterkore.engine.surface.geometry.CanopySnowClumps.Point>();
        int seed=authored.hashCode(),count=base.faces().size(),start=Math.floorMod(seed,count);
        for(int step=0;step<count&&accepted.size()<com.winterkore.engine.surface.geometry.CanopySnowClumps.MAX_CLUMPS_PER_PART;step++){
            int index=(start+step)%count;Face support=base.faces().get(index);
            if(support.cutout())continue;
            var points=support.vertices().stream().map(v->
                    new com.winterkore.engine.surface.geometry.CanopySnowClumps.Point(v.x(),v.y(),v.z())).toList();
            var cap=com.winterkore.engine.surface.geometry.CanopySnowClumps.onBranch(points,seed+index*31);
            if(cap==null||base.offset().getY()*16+cap.support().y()<3)continue;
            boolean overlaps=false;
            for(var previous:accepted){
                double dx=previous.x()-cap.support().x(),dy=previous.y()-cap.support().y(),dz=previous.z()-cap.support().z();
                if(dx*dx+dy*dy+dz*dz<6.25){overlaps=true;break;}
            }
            if(overlaps)continue;accepted.add(cap.support());
            for(var quad:cap.quads()){
                var vertices=new ArrayList<Vertex>(4);
                for(var v:List.of(quad.a(),quad.b(),quad.c(),quad.d()))
                    vertices.add(new Vertex((float)v.x(),(float)v.y(),(float)v.z(),
                            .01f+(float)v.x()*.998f,.01f+(float)v.z()*.998f,
                            (float)v.nx(),(float)v.ny(),(float)v.nz()));
                result.add(new Face(texture,List.copyOf(vertices)));
            }
        }
        return List.copyOf(result);
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

    private static @Nullable String supplyPrivateKey(String texture, Map<String, Identifier> palette) {
        if (SUPPLY_PRIVATE_PALETTE.containsKey(texture)) return texture;
        if (REFINEMENT_PRIVATE_PALETTE.containsKey(texture)) return texture;
        if (CAMP_PROP_PRIVATE_PALETTE.containsKey(texture)) return texture;
        for (var materials : List.of(SUPPLY_PRIVATE_PALETTE, REFINEMENT_PRIVATE_PALETTE, CAMP_PROP_PRIVATE_PALETTE)) {
            for (var binding : materials.entrySet()) {
                if (binding.getValue().toString().equals(texture) || binding.getValue().equals(palette.get(texture))) return binding.getKey();
            }
        }
        return null;
    }

    private static void requireSupplyPalette(Map<String, Identifier> palette) {
        for (var binding : palette.entrySet()) {
            String key = supplyPrivateKey(binding.getKey(), palette);
            Identifier expected = key == null ? null : CAMP_PROP_PRIVATE_PALETTE.containsKey(key) ? CAMP_PROP_PRIVATE_PALETTE.get(key)
                    : REFINEMENT_PRIVATE_PALETTE.containsKey(key) ? REFINEMENT_PRIVATE_PALETTE.get(key) : SUPPLY_PRIVATE_PALETTE.get(key);
            require(key == null || key.equals(binding.getKey()) && expected.equals(binding.getValue()),
                    "Supply-private texture URI must keep its exact selected palette alias");
        }
        long refined = REFINEMENT_PRIVATE_PALETTE.keySet().stream().filter(palette::containsKey).count();
        require(refined == 0 || refined == 2, "Refined private atlases must keep their exact two-key pair");
        long camp = CAMP_PROP_PRIVATE_PALETTE.keySet().stream().filter(palette::containsKey).count();
        require(camp == 0 || camp == 4, "Camp pot/fire materials must keep their exact four-key set");
    }

    private static void requireSupplyMaterial(String id, String texture, Map<String, Identifier> palette) {
        String key = supplyPrivateKey(texture, palette);
        if (key == null) return;
        String owner = id.contains(" ") ? id.substring(0, id.indexOf(' ')) : id;
        if (CAMP_PROP_PRIVATE_PALETTE.containsKey(key)) {
            require(key.equals(texture) && CAMP_PROP_PRIVATE_PALETTE.get(key).equals(palette.get(texture))
                            && (key.startsWith("campfire_") ? owner.equals("northern_campfire") : owner.equals(PotFillFurnitureBlock.ID)),
                    id + ": camp material must keep its exact alias and owner");
            return;
        }
        boolean refined = REFINEMENT_PRIVATE_PALETTE.containsKey(key);
        Identifier expected = refined ? REFINEMENT_PRIVATE_PALETTE.get(key) : SUPPLY_PRIVATE_PALETTE.get(key);
        require(key.equals(texture) && expected.equals(palette.get(texture))
                        && (refined ? REFINEMENT_IDS.contains(owner) : SUPPLY_PROP_IDS.contains(owner) && !owner.equals("firewood_crib")),
                id + ": supply-private material belongs only to the exact selected owner IDs and aliases");
    }

    private static void requireSupplyFace(String id, String texture, Map<String, Identifier> palette, List<Vertex> vertices) {
        requireSupplyMaterial(id, texture, palette);
        int columns = texture.equals("camping_cloth") || texture.equals("northern_refined_labels") || texture.equals("campfire_fire") || texture.equals("campfire_materials") ? 2
                : texture.equals("provision_labels") || texture.equals("northern_refined_materials") ? 4 : 1;
        int rows = texture.equals("campfire_fire") ? 1 : texture.equals("northern_refined_labels") ? 4 : columns;
        if (columns == 1) return;
        float sizeU = 16F / columns, sizeV = 16F / rows;
        int tileU = (int) Math.floor(vertices.getFirst().u() / sizeU);
        int tileV = (int) Math.floor(vertices.getFirst().v() / sizeV);
        float minU = tileU * sizeU + 0.05F, maxU = (tileU + 1) * sizeU - 0.05F;
        float minV = tileV * sizeV + 0.05F, maxV = (tileV + 1) * sizeV - 0.05F;
        require(tileU >= 0 && tileU < columns && tileV >= 0 && tileV < rows && vertices.stream().allMatch(vertex ->
                        vertex.u() >= minU - 0.000001F && vertex.u() <= maxU + 0.000001F
                                && vertex.v() >= minV - 0.000001F && vertex.v() <= maxV + 0.000001F),
                id + ": supply atlas quad must stay in one tile with a 0.05 UV gutter");
    }

    private static List<Face> readFaces(JsonArray rawFaces, Map<String, Identifier> palette, String id) {
        return readFaces(rawFaces, palette, id, false);
    }

    private static List<Face> readFaces(JsonArray rawFaces, Map<String, Identifier> palette, String id, boolean allowCutout) {
        return readFaces(rawFaces, palette, id, allowCutout, 0);
    }

    private static List<Face> readFaces(JsonArray rawFaces, Map<String, Identifier> palette, String id,
                                        boolean allowCutout, int part) {
        List<Face> faces = new ArrayList<>();
        float top = Math.max(16.5F, com.dark.winter.block.FurnitureCatalog.partHeightLimit(id));
        float right = Math.max(16.5F, com.dark.winter.block.FurnitureCatalog.partWidthLimit(id, part));
        double left = Math.min(-.5, com.dark.winter.block.FurnitureCatalog.partMinimum(id, part, 0));
        double front = Math.min(-.5, com.dark.winter.block.FurnitureCatalog.partMinimum(id, part, 2));
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
                require(x >= left && x <= right && y >= -0.5F && y <= top && z >= front && z <= 16.5F,
                        id + ": mesh vertex exceeds its part bounds");
                float u = finite(uv.get(0)), v = finite(uv.get(1));
                require(u >= -0.0001F && u <= 16.0001F && v >= -0.0001F && v <= 16.0001F,
                        id + ": UV outside its material's 0..16 range");
                vertices.add(new Vertex(x, y, z, Math.clamp(u, 0, 16), Math.clamp(v, 0, 16)));
            }
            requireSupplyFace(id, texture, palette, vertices);
            require(normal(vertices)[3] > 0.00000001F, id + ": degenerate mesh face");
            require(!face.has("cutout") || face.get("cutout").isJsonPrimitive()
                            && face.getAsJsonPrimitive("cutout").isBoolean(), id + ": cutout must be boolean");
            boolean cutout = face.has("cutout") && face.get("cutout").getAsBoolean();
            boolean dryBoughCutout = id.equals("forest_branch_pile") && texture.equals("deadwood_b_dry_boughs")
                    && Identifier.fromNamespaceAndPath("darkwinter", "block/nature/b_deadwood_dry_boughs").equals(palette.get(texture));
            boolean fernFrondCutout = id.equals("forest_fern") && texture.equals("plants_c_fronds")
                    && Identifier.fromNamespaceAndPath("darkwinter", "block/nature/c_fern_fronds").equals(palette.get(texture));
            boolean campfireCutout = id.equals("northern_campfire") && texture.equals("campfire_fire")
                    && CAMP_PROP_PRIVATE_PALETTE.get("campfire_fire").equals(palette.get(texture))
                    && vertices.stream().allMatch(vertex -> vertex.u() >= 8.05F);
            if (texture.equals("campfire_fire") && !(cutout ? campfireCutout
                    : vertices.stream().allMatch(vertex -> vertex.u() <= 7.95F))) {
                throw new IllegalArgumentException("K01 opaque embers must use the left tile; cutout flame must use the right tile"
                        + ": id=" + id + ", cutout=" + cutout + ", material=" + palette.get(texture)
                        + ", u=" + vertices.stream().map(Vertex::u).toList());
            }
            require(!cutout || allowCutout || dryBoughCutout || fernFrondCutout || campfireCutout,
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
            requireSupplyPalette(palette);
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
            require(entries.keySet().equals(BUILDING_IDS), "Building meshes must cover all registered wall, floor and window modules");
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

    private static List<Face> fitFloor(List<Face> faces, int spanX, int spanZ) {
        float unitsPerSpan = 16f / FloorSegmentBlock.FULL_SPAN;
        List<Face> fitted = new ArrayList<>(faces.size());
        for (Face face : faces) {
            List<Vertex> polygon = fitFloorAxis(face.vertices(), Direction.Axis.X, spanX * unitsPerSpan);
            polygon = fitFloorAxis(polygon, Direction.Axis.Z, spanZ * unitsPerSpan);
            if (polygon.size() == 4) {
                fitted.add(new Face(face.texture(), polygon, face.cutout()));
            } else {
                for (int index = 1; index + 1 < polygon.size(); index++) {
                    Vertex last = polygon.get(index + 1);
                    fitted.add(new Face(face.texture(), List.of(polygon.getFirst(), polygon.get(index), last, last), face.cutout()));
                }
            }
        }
        return fitted;
    }

    private static List<Vertex> fitFloorAxis(List<Vertex> polygon, Direction.Axis axis, float limit) {
        if (limit == 16 || polygon.isEmpty()) return polygon;
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (Vertex vertex : polygon) {
            float position = floorCoordinate(vertex, axis);
            min = Math.min(min, position);
            max = Math.max(max, position);
        }
        // Each quarter ends at an authored plank seam. Reuse the finished outer cap and discard
        // the original internal faces on the new boundary, where they would overlap that cap.
        if (min == 16 && max == 16) {
            return polygon.stream().map(vertex -> moveFloorVertex(vertex, axis, limit)).toList();
        }
        if (min >= limit) return List.of();
        if (max <= limit) return polygon;

        List<Vertex> clipped = new ArrayList<>(polygon.size() + 1);
        Vertex previous = polygon.getLast();
        float before = floorCoordinate(previous, axis);
        for (Vertex vertex : polygon) {
            float after = floorCoordinate(vertex, axis);
            if ((before < limit && after > limit) || (before > limit && after < limit)) {
                float fraction = (limit - before) / (after - before);
                Vertex intersection = new Vertex(Mth.lerp(fraction, previous.x(), vertex.x()),
                        Mth.lerp(fraction, previous.y(), vertex.y()), Mth.lerp(fraction, previous.z(), vertex.z()),
                        Mth.lerp(fraction, previous.u(), vertex.u()), Mth.lerp(fraction, previous.v(), vertex.v()),
                        Mth.lerp(fraction, previous.nx(), vertex.nx()), Mth.lerp(fraction, previous.ny(), vertex.ny()),
                        Mth.lerp(fraction, previous.nz(), vertex.nz()));
                clipped.add(moveFloorVertex(intersection, axis, limit));
            }
            if (after <= limit && (clipped.isEmpty() || !samePosition(clipped.getLast(), vertex))) clipped.add(vertex);
            previous = vertex;
            before = after;
        }
        if (clipped.size() > 1 && samePosition(clipped.getFirst(), clipped.getLast())) clipped.removeLast();
        return clipped;
    }

    private static float floorCoordinate(Vertex vertex, Direction.Axis axis) {
        return axis == Direction.Axis.X ? vertex.x() : vertex.z();
    }

    private static Vertex moveFloorVertex(Vertex vertex, Direction.Axis axis, float position) {
        return new Vertex(axis == Direction.Axis.X ? position : vertex.x(), vertex.y(),
                axis == Direction.Axis.Z ? position : vertex.z(), vertex.u(), vertex.v(),
                vertex.nx(), vertex.ny(), vertex.nz());
    }

    private static Map<String, Material.Baked> resolveMaterials(Entry entry, Map<String, Identifier> palette, ModelBaker baker) {
        String meshId = entry.id();
        Map<String, Material.Baked> materials = new LinkedHashMap<>();
        for (Part part : entry.parts()) for (Face face : part.faces()) {
            materials.computeIfAbsent(face.texture(), key -> baker.materials().get(new Material(palette.get(key),
                            meshId.equals("water_bottle") && key.equals("water_bottle_plastic")
                                    || meshId.equals("interior_kitchen_hutch") && key.equals("interior_hutch_glass")
                                    || key.equals("cabin_clear_glass")),
                    () -> "DarkWinter furniture mesh " + meshId));
        }
        return materials;
    }

    private static BakedFurniture bakeFurniture(Entry entry, Map<String, Identifier> palette, ModelBaker baker) {
        return bakeFurniture(entry, palette, baker, null);
    }

    private static BakedFurniture bakeFurniture(Entry entry, Map<String, Identifier> palette, ModelBaker baker,
                                                 @Nullable SnowUvGrid snowUv) {
        entry = smoothSurfaces(entry);
        Map<String, Material.Baked> materials = resolveMaterials(entry, palette, baker);
        Material.Baked particle = materials.values().iterator().next();
        BakedPart[][] parts = bakeBlockParts(entry, materials, particle, snowUv, 1);
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

    private static BakedPart[][] bakeBlockParts(Entry entry, Map<String, Material.Baked> materials, Material.Baked particle,
                                                @Nullable SnowUvGrid snowUv, float width) {
        BakedPart[][] parts = new BakedPart[FurnitureBlock.MAX_PARTS][4];
        for (int part = 0; part < FurnitureBlock.MAX_PARTS; part++) {
            Part source = part < entry.parts().size() ? entry.parts().get(part) : null;
            for (int facing = 0; facing < 4; facing++) {
                parts[part][facing] = bakePart(source == null ? List.of() : source.faces(), materials, particle, facing, null,
                        snowUv == null || source == null ? null : snowUv.atPart(source.offset(), facing), width);
            }
        }
        return parts;
    }

    private static BakedPart bakePart(List<Face> faces, Map<String, Material.Baked> materials, Material.Baked particle,
                                      int facing, float @Nullable [] normalization, @Nullable SnowUvGrid snowUv) {
        return bakePart(faces, materials, particle, facing, normalization, snowUv, 1);
    }

    private static BakedPart bakePart(List<Face> faces, Map<String, Material.Baked> materials, Material.Baked particle,
                                      int facing, float @Nullable [] normalization, @Nullable SnowUvGrid snowUv, float width) {
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
                    x *= width;
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
                else if (normalization == null) {
                    if (width != 1) {
                        nx /= width;
                        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                        nx /= length; ny /= length; nz /= length;
                    }
                    for (int turn = 0; turn < facing; turn++) { float nextX = -nz; nz = nx; nx = nextX; }
                }
                emitter.normal(vertex,nx,ny,nz);
            }
            Material.Baked material = materials.get(face.texture());
            if (face.texture().equals("road_ref_yellow")) emitter.color(0xFFDBB550,0xFFDBB550,0xFFDBB550,0xFFDBB550);
            if (face.texture().equals("road_ref_white")) emitter.color(0xFFF1F1E6,0xFFF1F1E6,0xFFF1F1E6,0xFFF1F1E6);
            emitter.materialBake(material, 0);
            if (face.texture().equals("cabin_stove_fire") || face.texture().equals("cabin_lantern_glow")
                    || face.texture().equals("interior_pendant_bulb")) {
                emitter.emissive(true);
            }
            if (material.forceTranslucent()) {
                if (face.texture().equals("water_bottle_plastic")) {
                    emitter.color(0x8063B9FF, 0x8063B9FF, 0x8063B9FF, 0x8063B9FF);
                }
                emitter.chunkLayer(ChunkSectionLayer.TRANSLUCENT)
                        .itemRenderType(Sheets.translucentBlockItemSheet())
                        .itemGlintRenderType(Sheets.translucentBlockItemGlintSheet())
                        .itemGlintSpecialRenderType(Sheets.translucentBlockItemGlintSpecialSheet());
            } else if (face.cutout()) {
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
                    // Authored analytic canopy normals retain their smooth cushion silhouette.
                    if(v.nx()*v.nx()+v.ny()*v.ny()+v.nz()*v.nz()>.5f){vertices.add(v);continue;}
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

    // cabin_openings.curtain authors the linen bindings with a separate UV patch on the sill texture.
    private static boolean isCurtainBinding(Face face) {
        return face.texture().equals("cabin_window_sill") && face.vertices().stream()
                .allMatch(v -> (v.u() == 6 || v.u() == 8) && (v.v() == 7 || v.v() == 8));
    }

    private static int storageFaceCount(StorageFurnitureBlock.Kind kind, int part) {
        return switch (kind) {
            case FRIDGE -> part == 0 ? 151 : 325;
            case WARDROBE -> part == 0 ? 475 : 283;
            case CABINET -> 3900;
        };
    }

    // Partition the checked-in geometry in the order emitted by interior_kitchen,
    // interior_furnishings and home_pine_n. A handle follows its own leaf.
    private static int storageFace(StorageFurnitureBlock.Kind kind, int part, int face) {
        return switch (kind) {
            case FRIDGE -> face == 3 ? -1 : part == 0 ? (face >= 117 ? 1 : 0)
                    : face >= 175 ? 2 : face >= 43 ? 1 : 0;
            case WARDROBE -> part == 0
                    ? (face < 304 ? 0 : face < 315 ? -1 : face < 339 ? 1 : face < 363 ? 2 : face < 419 ? 1 : 2)
                    : (face < 248 ? 0 : face < 259 ? -1 : face < 271 ? 1 : 2);
            case CABINET -> (face >= 2184 && face < 2808) || (face >= 3432 && face < 3540) ? 1
                    : (face >= 2808 && face < 3432) || (face >= 3540 && face < 3648) ? 2 : 0;
        };
    }

    private static void addStorageInterior(StorageFurnitureBlock.Kind kind, int part,
                                           List<Face> fixed, List<Face> first, List<Face> second) {
        switch (kind) {
            case FRIDGE -> {
                String lining = "interior_fridge_milk";
                // The original enamel shell has a single back-facing facade. Add an inset liner and shelves.
                float floor = part == 0 ? 2.63f : 0;
                float top = part == 0 ? 16 : 14.68f;
                interiorBox(fixed, 2.06f, floor, 1.68f, 2.69f, top, 14.7f, lining);
                interiorBox(fixed, 13.31f, floor, 1.68f, 13.94f, top, 14.7f, lining);
                interiorBox(fixed, 2.68f, floor, 14.10f, 13.32f, top, 14.96f, lining);
                if (part == 0) interiorBox(fixed, 2.67f, 2.64f, 1.70f, 13.33f, 3.18f, 14.18f, lining);
                if (part == 1) interiorBox(fixed, 2.67f, 14.17f, 1.72f, 13.33f, 14.72f, 14.18f, lining);
                float[] shelves = part == 0 ? new float[]{7.82f, 13.10f} : new float[]{2.54f, 6.07f, 10.54f};
                for (float y : shelves) {
                    interiorBox(fixed, 2.69f, y, 2.30f, 13.31f, y + .28f, 14.11f, lining);
                    interiorBox(fixed, 2.69f, y, 2.10f, 13.31f, y + .34f, 2.33f, "metal");
                }
                if (part == 0) {
                    interiorBox(first, 3.0f, 5.04f, 1.52f, 13.03f, 5.40f, 2.83f, lining);
                    interiorBox(first, 3.0f, 5.39f, 2.59f, 13.03f, 6.11f, 2.83f, lining);
                } else {
                    interiorBox(second, 3.0f, 7.16f, 1.53f, 13.03f, 7.46f, 2.64f, lining);
                    interiorBox(second, 3.0f, 7.44f, 2.45f, 13.03f, 8.15f, 2.64f, lining);
                }
            }
            case WARDROBE -> {
                String wood = "interior_dark_timber";
                interiorBox(fixed, 2.0f, part == 0 ? 3.23f : 0, 14.57f,
                        14.0f, part == 0 ? 16 : 13.27f, 14.66f, wood);
                if (part == 0) interiorBox(fixed, 2.10f, 3.20f, 2.32f, 13.88f, 3.84f, 14.65f, wood);
                else {
                    interiorBox(fixed, 2.08f, 10.63f, 2.35f, 13.92f, 11.22f, 14.66f, wood);
                    interiorBox(fixed, 2.02f, 9.23f, 7.91f, 13.96f, 9.65f, 8.35f, "interior_honey_timber");
                }
                float bottom = part == 0 ? 2.69f : 0;
                float top = part == 0 ? 16 : 13.36f;
                interiorPlane(first, 2.20f, bottom, 7.91f, top, 2.19f, wood);
                interiorPlane(second, 8.09f, bottom, 13.80f, top, 2.19f, wood);
            }
            case CABINET -> interiorBox(fixed, 1.94f, 7.98f, 2.92f, 14.05f, 8.45f, 13.14f, "street_s_wood");
        }
    }

    private static void interiorPlane(List<Face> faces, float x0, float y0, float x1, float y1, float z, String texture) {
        interiorQuad(faces, texture, x0,y0,z, x1,y0,z, x1,y1,z, x0,y1,z);
    }

    private static void interiorBox(List<Face> faces, float x0, float y0, float z0, float x1, float y1, float z1, String texture) {
        interiorQuad(faces, texture, x1,y0,z0, x0,y0,z0, x0,y1,z0, x1,y1,z0);
        interiorPlane(faces, x0, y0, x1, y1, z1, texture);
        interiorQuad(faces, texture, x0,y0,z0, x0,y0,z1, x0,y1,z1, x0,y1,z0);
        interiorQuad(faces, texture, x1,y0,z1, x1,y0,z0, x1,y1,z0, x1,y1,z1);
        interiorQuad(faces, texture, x0,y1,z1, x1,y1,z1, x1,y1,z0, x0,y1,z0);
        interiorQuad(faces, texture, x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1);
    }

    private static void interiorQuad(List<Face> faces, String texture, float... xyz) {
        faces.add(new Face(texture, List.of(new Vertex(xyz[0], xyz[1], xyz[2], 1, 15),
                new Vertex(xyz[3], xyz[4], xyz[5], 15, 15), new Vertex(xyz[6], xyz[7], xyz[8], 15, 1),
                new Vertex(xyz[9], xyz[10], xyz[11], 1, 1))));
    }

    public static @Nullable StorageLeaves storageLeaves(BlockStateModel model) {
        return model instanceof FurnitureBlockModel furniture ? furniture.storageLeaves : null;
    }

    public static @Nullable GateLeaf gateLeaf(BlockStateModel model) {
        return model instanceof FurnitureBlockModel furniture ? furniture.gateLeaf : null;
    }

    public static @Nullable CurtainMesh curtainMesh(BlockStateModel model) {
        return model instanceof FurnitureBlockModel furniture ? furniture.curtainMesh : null;
    }

    public static List<BlockStateModelPart> diningChairParts(BlockStateModel model) {
        return doorLeaf(model);
    }

    public static List<BlockStateModelPart> doorLeaf(BlockStateModel model) {
        return model instanceof FurnitureBlockModel furniture ? furniture.doorLeaf : List.of();
    }

    private static final class FurnitureBlockModel extends WrapperBlockStateModel implements MeshBackedModel {
        private final String id;
        private final BakedPart part;
        private final @Nullable List<List<BakedPart>> surfaceParts;
        private final BlockPos surfaceOffset;
        private final int snowGrid;
        private final List<BlockStateModelPart> doorLeaf;
        private final @Nullable CurtainMesh curtainMesh;
        private final @Nullable GateLeaf gateLeaf;
        private final @Nullable StorageLeaves storageLeaves;

        private FurnitureBlockModel(BlockStateModel wrapped, String id, BakedPart part) {
            this(wrapped, id, part, List.of());
        }
        private FurnitureBlockModel(BlockStateModel wrapped, String id, BakedPart part, List<BlockStateModelPart> doorLeaf) {
            this(wrapped, id, part, doorLeaf, null, null, null);
        }
        private FurnitureBlockModel(BlockStateModel wrapped, String id, BakedPart part, @Nullable CurtainMesh curtainMesh) {
            this(wrapped, id, part, List.of(), curtainMesh, null, null);
        }
        private FurnitureBlockModel(BlockStateModel wrapped, String id, BakedPart part, @Nullable GateLeaf gateLeaf) {
            this(wrapped, id, part, List.of(), null, gateLeaf, null);
        }
        private FurnitureBlockModel(BlockStateModel wrapped, String id, BakedPart part, @Nullable StorageLeaves storageLeaves) {
            this(wrapped, id, part, List.of(), null, null, storageLeaves);
        }
        private FurnitureBlockModel(BlockStateModel wrapped, String id, BakedPart part,
                                    List<BlockStateModelPart> doorLeaf, @Nullable CurtainMesh curtainMesh,
                                    @Nullable GateLeaf gateLeaf, @Nullable StorageLeaves storageLeaves) {
            super(wrapped); this.id = id; this.part = part;
            this.surfaceParts = null; this.surfaceOffset = BlockPos.ZERO; this.snowGrid = 1;
            this.doorLeaf = doorLeaf;
            this.curtainMesh = curtainMesh;
            this.gateLeaf = gateLeaf;
            this.storageLeaves = storageLeaves;
        }
        private FurnitureBlockModel(BlockStateModel wrapped, String id, List<List<BakedPart>> surfaceParts, BlockPos surfaceOffset, int snowGrid) {
            super(wrapped); this.id = id; this.part = surfaceParts.getFirst().getFirst();
            this.surfaceParts = surfaceParts; this.surfaceOffset = surfaceOffset; this.snowGrid = snowGrid;
            this.doorLeaf = List.of();
            this.curtainMesh = null;
            this.gateLeaf = null;
            this.storageLeaves = null;
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
        private void tracePart(int type,BlockAndTintGetter level,BlockPos pos,BlockState state,BakedPart selected) {
            if(com.dark.winter.client.diagnostics.vegetation.VegetationTrace.enabled()
                    &&com.dark.winter.client.diagnostics.vegetation.VegetationTrace.isVegetation(id))
                com.dark.winter.client.diagnostics.vegetation.VegetationTrace.model(type,id,level,pos,Block.getId(state),
                        selected.mesh(),selected.quads().size());
        }
        @Override public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state,
                                       RandomSource random, Predicate<@Nullable Direction> cullTest) {
            BakedPart selected=positionedPart(pos);
            tracePart(com.dark.winter.client.diagnostics.vegetation.VegetationTrace.MODEL_EMIT,level,pos,state,selected);
            double rotation = AnchorRuntime.rotation(level, pos);
            if (rotation == 0 || !(state.getBlock() instanceof FurnitureBlock furniture)) {
                selected.emitQuads(emitter, cullTest);
                return;
            }
            BlockPos offset = furniture.partOffset(state.getValue(FurnitureBlock.PART), state.getValue(FurnitureBlock.FACING));
            float cos = (float) Math.cos(rotation), sin = (float) Math.sin(rotation);
            emitter.pushTransform(quad -> {
                for (int i = 0; i < 4; i++) {
                    float x = quad.x(i) + offset.getX(), z = quad.z(i) + offset.getZ();
                    quad.pos(i, cos * x - sin * z - offset.getX(), quad.y(i), sin * x + cos * z - offset.getZ());
                    if (quad.hasNormal(i)) {
                        float nx = quad.normalX(i), nz = quad.normalZ(i);
                        quad.normal(i, cos * nx - sin * nz, quad.normalY(i), sin * nx + cos * nz);
                    }
                }
                quad.nominalFace(null);
                return true;
            });
            try {
                selected.emitQuads(emitter, cullTest);
            } finally {
                emitter.popTransform();
            }
        }
        @Override public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
            BakedPart selected=positionedPart(pos);
            tracePart(com.dark.winter.client.diagnostics.vegetation.VegetationTrace.MODEL_KEY,level,pos,state,selected);
            double rotation = AnchorRuntime.rotation(level, pos);
            return rotation == 0 ? selected.mesh() : new RotatedGeometryKey(selected.mesh(), state, rotation);
        }
    }

    private record RotatedGeometryKey(Mesh mesh, BlockState state, double rotation) {}

    private static final class FurnitureItemModel implements ItemModel, MeshBackedModel {
        private final String id;
        private final BakedPart geometry;
        private final @Nullable BakedPart snowGeometry;
        private final Map<PotFillFurnitureBlock.Fill, BakedPart> potGeometry;
        private final ModelRenderProperties properties;
        private final Matrix4fc transformation;

        private FurnitureItemModel(String id, BakedPart geometry, @Nullable BakedPart snowGeometry,
                                   Map<PotFillFurnitureBlock.Fill, BakedPart> potGeometry,
                                   ModelRenderProperties properties, Matrix4fc transformation) {
            this.id = id; this.geometry = geometry; this.snowGeometry = snowGeometry;
            this.potGeometry = potGeometry;
            this.properties = properties; this.transformation = transformation;
        }
        @Override public Mesh mesh() { return geometry.mesh(); }
        @Override public Mesh mesh(ItemStack stack) { return selectedGeometry(stack).mesh(); }
        private BakedPart selectedGeometry(ItemStack stack) {
            if (!potGeometry.isEmpty()) return potGeometry.get(PotFillFurnitureBlock.fill(stack));
            return snowGeometry != null && SnowVariantFurnitureBlock.isSnowy(stack) ? snowGeometry : geometry;
        }
        @Override public String furnitureId() { return id; }
        @Override public void update(ItemStackRenderState output, ItemStack stack, ItemModelResolver resolver,
                                     ItemDisplayContext displayContext, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
            output.appendModelIdentityElement(this);
            BakedPart selectedGeometry = selectedGeometry(stack);
            if (snowGeometry != null || !potGeometry.isEmpty()) output.appendModelIdentityElement(selectedGeometry.mesh());
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
