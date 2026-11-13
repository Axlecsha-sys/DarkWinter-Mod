// Original provision meshes baked once into the normal item renderer per resource reload.
package com.dark.winter.client;

import com.dark.winter.DarkWinter;
import com.dark.winter.registry.ModProvisionItems;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Transformation;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.model.MeshQuadCollection;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/** Resource-pack-aware provision art; registration, consumption and balance remain unchanged. */
public final class ProvisionItemModels {
    public static final Identifier RESOURCE = Identifier.fromNamespaceAndPath("darkwinter", "provisions/meshes.json");
    public static final int PROVISION_QUAD_TAG = 0x445750;
    private static final int FACE_BUDGET = 600;
    private static boolean registered;

    /** Actual native mesh and resolved materials for client validation. */
    public interface ProvisionMeshModel extends FurnitureMeshModels.MeshBackedModel {
        int sourceFaceCount();
        List<Material.Baked> materials();
    }

    private record Vertex(float x, float y, float z, float u, float v) {
        Vector3f position() { return new Vector3f(x, y, z); }
    }
    private record Face(String texture, List<Vertex> vertices, Vector3fc normal) { }
    private record NormalKey(String texture, long x, long y, long z) { }
    private record WeightedNormal(Vector3fc normal, float angle) { }
    private record Geometry(Mesh mesh, List<Material.Baked> materials, int flags, Vector3fc[] extents, int faceCount) { }

    private static final class Data {
        private final Map<String, Identifier> palette;
        private final Map<String, List<Face>> entries;
        private final Map<String, Geometry> baked = new ConcurrentHashMap<>();
        private Data(Map<String, Identifier> palette, Map<String, List<Face>> entries) {
            this.palette = Map.copyOf(palette); this.entries = Map.copyOf(entries);
        }
        private Geometry bake(String id, ModelBaker baker) {
            return baked.computeIfAbsent(id, ignored -> bakeGeometry(id, entries.get(id), palette, baker));
        }
    }

    public static synchronized void init() {
        if (registered) return;
        registered = true;
        PreparableModelLoadingPlugin.register(
                (shared, executor) -> CompletableFuture.supplyAsync(() -> read(shared.resourceManager()), executor),
                (data, plugin) -> plugin.modifyItemModelAfterBake().register((model, context) -> {
                    if (!context.itemId().getNamespace().equals(DarkWinter.MOD_ID)) return model;
                    String id = context.itemId().getPath();
                    if (!data.entries.containsKey(id)) return model;
                    ModelBaker baker = context.bakingContext().blockModelBaker();
                    Geometry geometry = data.bake(id, baker);
                    ModelRenderProperties properties;
                    Matrix4fc transform = context.transformation();
                    if (context.sourceModel() instanceof CuboidItemModelWrapper.Unbaked source) {
                        var resolved = baker.getModel(source.model());
                        properties = ModelRenderProperties.fromResolvedModel(baker, resolved, resolved.getTopTextureSlots());
                        transform = Transformation.compose(transform, source.transformation());
                        properties = new ModelRenderProperties(properties.usesBlockLight(), geometry.materials().getFirst(), properties.transforms());
                    } else {
                        properties = new ModelRenderProperties(true, geometry.materials().getFirst(), ItemTransforms.NO_TRANSFORMS);
                    }
                    return new ProvisionModel(id, geometry, properties, new Matrix4f(transform));
                }));
    }

    private static Data read(ResourceManager resources) {
        try (Reader reader = resources.openAsReader(RESOURCE)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            require(root.get("schema").getAsInt() == 1, "Expected provision mesh schema 1");
            Map<String, Identifier> palette = new LinkedHashMap<>();
            root.getAsJsonObject("palette").entrySet().forEach(entry -> {
                require(entry.getKey().matches("[a-z0-9_]+"), "Invalid provision material alias");
                palette.put(entry.getKey(), Identifier.parse(entry.getValue().getAsString()));
            });
            require(!palette.isEmpty() && palette.size() <= 128, "Invalid provision material count");
            Set<String> expected = BuiltInRegistries.ITEM.stream().filter(ModProvisionItems::contains)
                    .map(item -> BuiltInRegistries.ITEM.getKey(item).getPath()).collect(Collectors.toSet());
            require(!expected.isEmpty(), "Provision registry is not initialized");
            Map<String, List<Face>> entries = new LinkedHashMap<>();
            for (JsonElement rawEntry : root.getAsJsonArray("entries")) {
                JsonObject entry = rawEntry.getAsJsonObject();
                String id = entry.get("id").getAsString();
                require(expected.contains(id) && !entries.containsKey(id), "Unknown or duplicate provision mesh " + id);
                JsonArray rawFaces = entry.getAsJsonArray("quads");
                require(!rawFaces.isEmpty() && rawFaces.size() <= FACE_BUDGET, id + ": expected 1..600 faces");
                List<Face> faces = new ArrayList<>(rawFaces.size());
                for (JsonElement rawFace : rawFaces) {
                    JsonObject face = rawFace.getAsJsonObject();
                    String texture = face.get("texture").getAsString();
                    require(palette.containsKey(texture), id + ": unknown material " + texture);
                    JsonArray points = face.getAsJsonArray("p"), uv = face.getAsJsonArray("uv");
                    require(points.size() == 4 && uv.size() == 4, id + ": expected four vertices and UVs");
                    List<Vertex> vertices = new ArrayList<>(4);
                    for (int i = 0; i < 4; i++) {
                        JsonArray p = points.get(i).getAsJsonArray(), t = uv.get(i).getAsJsonArray();
                        require(p.size() == 3 && t.size() == 2, id + ": invalid vertex dimensions");
                        vertices.add(new Vertex(coordinate(p.get(0), id) / 16, coordinate(p.get(1), id) / 16,
                                coordinate(p.get(2), id) / 16, coordinate(t.get(0), id), coordinate(t.get(1), id)));
                    }
                    Vector3f n = vertices.get(1).position().sub(vertices.getFirst().position())
                            .cross(vertices.get(2).position().sub(vertices.getFirst().position()));
                    require(n.lengthSquared() > 1e-18f, id + ": degenerate face");
                    n.normalize();
                    require(Math.abs(n.dot(vertices.get(3).position().sub(vertices.getFirst().position()))) <= 2e-6f,
                            id + ": non-planar face must be triangulated by its author");
                    faces.add(new Face(texture, List.copyOf(vertices), n));
                }
                entries.put(id, List.copyOf(faces));
            }
            require(entries.keySet().equals(expected), "Provision meshes must cover the exact registered assortment");
            DarkWinter.LOGGER.info("Prepared {} provision item meshes ({} faces) from resource packs",
                    entries.size(), entries.values().stream().mapToInt(List::size).sum());
            return new Data(palette, entries);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot load " + RESOURCE + ": " + exception.getMessage(), exception);
        }
    }

    private static float coordinate(JsonElement raw, String id) {
        float value = raw.getAsFloat();
        require(Float.isFinite(value) && value >= 0 && value <= 16, id + ": coordinate outside finite 0..16");
        return value;
    }

    private static NormalKey key(Face face, Vertex vertex) {
        return new NormalKey(face.texture(), Math.round(vertex.x() * 1000000d),
                Math.round(vertex.y() * 1000000d), Math.round(vertex.z() * 1000000d));
    }

    private static Geometry bakeGeometry(String id, List<Face> faces, Map<String, Identifier> palette, ModelBaker baker) {
        Map<String, Material.Baked> materials = new LinkedHashMap<>();
        Map<NormalKey, List<WeightedNormal>> adjacency = new HashMap<>();
        for (Face face : faces) {
            materials.computeIfAbsent(face.texture(), alias -> baker.materials().get(new Material(palette.get(alias)),
                    () -> "DarkWinter provision item " + id));
            int count = face.vertices().get(2).position().distanceSquared(face.vertices().get(3).position()) < 1e-16f ? 3 : 4;
            for (int i = 0; i < count; i++) {
                Vertex point = face.vertices().get(i);
                Vector3f a = face.vertices().get((i + count - 1) % count).position().sub(point.position());
                Vector3f b = face.vertices().get((i + 1) % count).position().sub(point.position());
                if (a.lengthSquared() < 1e-16f || b.lengthSquared() < 1e-16f) continue;
                float angle = (float) Math.acos(Math.clamp(a.normalize().dot(b.normalize()), -1, 1));
                adjacency.computeIfAbsent(key(face, point), ignored -> new ArrayList<>()).add(new WeightedNormal(face.normal(), angle));
            }
        }
        var mutable = Renderer.get().mutableMesh();
        var emitter = mutable.emitter();
        List<BakedQuad> vanilla = new ArrayList<>(faces.size());
        for (Face face : faces) {
            for (int i = 0; i < 4; i++) {
                Vertex v = face.vertices().get(i);
                Vector3f smooth = new Vector3f();
                for (WeightedNormal candidate : adjacency.getOrDefault(key(face, v), List.of())) {
                    if (candidate.normal().dot(face.normal()) >= .7660444f) smooth.fma(candidate.angle(), candidate.normal());
                }
                if (smooth.lengthSquared() < 1e-16f) smooth.set(face.normal()); else smooth.normalize();
                emitter.pos(i, v.x(), v.y(), v.z()).normal(i, smooth).uv(i, v.u(), v.v()).color(i, 0xFFFFFFFF);
            }
            Material.Baked material = materials.get(face.texture());
            emitter.materialBake(material, 0).tag(PROVISION_QUAD_TAG).cullFace(null).tintIndex(-1).ambientOcclusion(TriState.FALSE);
            vanilla.add(emitter.toBakedQuad(material.sprite()));
            emitter.emit();
        }
        Mesh mesh = mutable.immutableCopy();
        return new Geometry(mesh, List.copyOf(materials.values()), new MeshQuadCollection(mesh).materialFlags(),
                CuboidItemModelWrapper.computeExtents(vanilla), faces.size());
    }

    private record ProvisionModel(String furnitureId, Geometry geometry, ModelRenderProperties properties,
                                  Matrix4fc transformation) implements ItemModel, ProvisionMeshModel {
        @Override public Mesh mesh() { return geometry.mesh(); }
        @Override public int sourceFaceCount() { return geometry.faceCount(); }
        @Override public List<Material.Baked> materials() { return geometry.materials(); }
        @Override public void update(ItemStackRenderState output, ItemStack stack, ItemModelResolver resolver,
                                     ItemDisplayContext context, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
            output.appendModelIdentityElement(this);
            var layer = output.newLayer();
            layer.setLocalTransform(transformation);
            layer.setExtents(geometry::extents);
            properties.applyToLayer(layer, context);
            if (stack.hasFoil()) {
                layer.setFoilType(ItemStackRenderState.FoilType.STANDARD);
                output.appendModelIdentityElement(ItemStackRenderState.FoilType.STANDARD);
                output.setAnimated();
            }
            geometry.mesh().outputTo(layer.emitter());
            if ((geometry.flags() & BakedQuad.FLAG_ANIMATED) != 0) output.setAnimated();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private ProvisionItemModels() { }
}
