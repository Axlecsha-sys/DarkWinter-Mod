// The actual worn garment, assembled on its neutral skeleton and baked once per resource reload.
package com.dark.winter.client.equipment;

import com.dark.winter.equipment.GearItem;
import com.dark.winter.equipment.GearSlot;
import com.dark.winter.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.model.MeshQuadCollection;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.color.item.Constant;
import net.minecraft.client.model.geom.ModelPart;
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
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/** The actual worn garment, assembled on its neutral skeleton and baked once per resource reload. */
public final class ClothingItemModels {
    public static final int CLOTHING_QUAD_TAG = 0x445743;
    private static boolean registered;

    /** Actual baked data for integration checks; no source-file approximation is involved. */
    public interface MeshBackedItem {
        Mesh mesh();
        String clothingId();
        int sourcePieceCount();
        int sourceTintCount();
        List<Material.Baked> materials();
    }

    private record Vertex(float x, float y, float z, float u, float v) {
        Vector3f position() { return new Vector3f(x, y, z); }
    }
    private record Face(Identifier texture, int color, List<Vertex> vertices) { }
    private record Garment(List<Face> faces, int pieces) { }
    private record TintReferences(int[] colors, boolean[] ready) { }
    private record NormalKey(Identifier texture, int color, long x, long y, long z) { }
    private record WeightedNormal(Vector3f direction, float weight) { }
    private record Geometry(Mesh mesh, List<Material.Baked> materials, int flags, Vector3fc[] extents) { }

    public static synchronized void init() {
        if (registered) return;
        registered = true;
        PreparableModelLoadingPlugin.register(
                (shared, executor) -> CompletableFuture.supplyAsync(ClothingItemModels::prepare, executor),
                (garments, plugin) -> plugin.modifyItemModelAfterBake().register((model, context) -> {
                    if (!context.itemId().getNamespace().equals("darkwinter")) return model;
                    String id = context.itemId().getPath();
                    Garment garment = garments.get(id);
                    if (garment == null) return model;
                    ModelBaker baker = context.bakingContext().blockModelBaker();
                    List<ItemTintSource> tints = List.of();
                    Matrix4fc transform = context.transformation();
                    ModelRenderProperties properties;
                    if (context.sourceModel() instanceof CuboidItemModelWrapper.Unbaked source) {
                        var resolved = baker.getModel(source.model());
                        properties = ModelRenderProperties.fromResolvedModel(baker, resolved, resolved.getTopTextureSlots());
                        transform = Transformation.compose(transform, source.transformation());
                        tints = source.tints();
                    } else properties = new ModelRenderProperties(false, null, ItemTransforms.NO_TRANSFORMS);
                    TintReferences references = referenceTints(tints);
                    Geometry geometry = bake(id, garment, baker, references.colors());
                    properties = new ModelRenderProperties(properties.usesBlockLight(), geometry.materials().getFirst(), properties.transforms());
                    return new GarmentItem(id, garment.pieces(), geometry, properties, new Matrix4f(transform), List.copyOf(tints), references);
                }));
    }

    private static Map<String, Garment> prepare() {
        ModelPart skeleton = HumanBodyModel.createRoot(false);
        Map<String, Garment> result = new LinkedHashMap<>();
        Map<GearSlot, Integer> variants = new HashMap<>();
        ModItems.all().forEach((id, item) -> {
            if (!(item instanceof GearItem gear)) return;
            int variant = variants.merge(gear.slot(), 1, Integer::sum);
            List<Face> faces = new ArrayList<>();
            int pieces;
            if (gear.slot() == GearSlot.BACKPACK) {
                boolean wanderer=item==ModItems.CINEMATIC_NORTHERN_WANDERER_BACKPACK;
                var parts = wanderer?NorthernWandererPack.itemParts():BackpackLayer.itemParts();
                pieces = parts.size();
                for (var part : parts) collect(faces, bonePose(skeleton, "body"), part.geometry(), wanderer?NorthernWandererPack.texture(part.color()):BackpackLayer.texture(part.color()), part.color());
                // Show the pack's flap and pouch from the same item-view direction as a garment's front.
                // This proper half-turn affects only the inventory model, preserving UVs and winding.
                faces.replaceAll(face -> new Face(face.texture(), face.color(), face.vertices().stream()
                        .map(v -> new Vertex(-v.x(), v.y(), -v.z(), v.u(), v.v())).toList()));
            } else {
                boolean expedition=id.equals("cinematic_expedition_parka");
                ModelPart garmentSkeleton=expedition?ThirdPersonBodyModel.createRoot(false):skeleton;
                var parts = itemParts(id,gear.slot(),variant);
                if (parts == null) throw new IllegalStateException("Missing actual clothing geometry for " + id);
                pieces = parts.size();
                for (var part : parts) {
                    String bone = switch (part.bone().name()) {
                        case "HEAD" -> "head"; case "BODY" -> "body";
                        case "LEFT_ARM" -> "left_arm"; case "RIGHT_ARM" -> "right_arm";
                        case "LEFT_LEG" -> "left_leg"; case "RIGHT_LEG" -> "right_leg";
                        case "LEFT_FOREARM" -> "left_arm/forearm"; case "RIGHT_FOREARM" -> "right_arm/forearm";
                        case "LEFT_SHIN" -> "left_leg/shin"; case "RIGHT_SHIN" -> "right_leg/shin";
                        case "LEFT_FOOT" -> "left_leg/shin/foot"; case "RIGHT_FOOT" -> "right_leg/shin/foot";
                        default -> throw new IllegalStateException("Unknown garment bone " + part.bone());
                    };
                    PoseStack itemPose=bonePose(garmentSkeleton,bone);
                    if(expedition)ThirdPersonBodyModel.fit(part.bone(),itemPose);
                    // Resource preparation runs off-thread: apply the same contour without touching the render cache.
                    collect(faces,itemPose,expedition?ThirdPersonContours.garment(part):part.geometry(),
                            itemTexture(id,gear.slot(),variant,part.color()),part.color());
                }
            }
            int faceBudget=id.equals("cinematic_expedition_parka")?12000:8000;
            if (faces.isEmpty() || faces.size() > faceBudget) throw new IllegalStateException(id + ": invalid garment face budget " + faces.size());
            result.put(id, new Garment(normalize(faces), pieces));
        });
        return Map.copyOf(result);
    }

    /** Inventory follows the new worn parka while the shared wardrobe remains the FP source. */
    static List<ClothingMeshes.Piece> itemParts(String id,GearSlot slot,int variant) {
        return id.equals("cinematic_expedition_parka")?CinematicExpeditionParka.pieces()
                :ClothingMeshes.WARDROBE.get(ClothingMeshes.key(slot,variant));
    }

    static Identifier itemTexture(String id,GearSlot slot,int variant,int color) {
        return id.equals("cinematic_expedition_parka")?CinematicExpeditionParka.texture(color)
                :ClothingLayer.texture(slot,variant,color);
    }

    private static PoseStack bonePose(ModelPart root, String path) {
        PoseStack poses = new PoseStack();
        ModelPart bone = root;
        for (String child : path.split("/")) { bone = bone.getChild(child); bone.translateAndRotate(poses); }
        return poses;
    }

    private static void collect(List<Face> faces, PoseStack poses, ModelPart geometry, Identifier texture, int color) {
        String path = texture.getPath();
        Identifier sprite = texture.withPath(path.substring("textures/".length(), path.length() - ".png".length()));
        geometry.visit(poses, (pose, child, index, cube) -> {
            for (var polygon : cube.polygons) {
                List<Vertex> vertices = new ArrayList<>(4);
                // Entity coordinates have Y down. Reflection plus reversed winding keeps the original front and UVs.
                for (int i : new int[]{0, 3, 2, 1}) {
                    var v = polygon.vertices()[i];
                    Vector3f p = new Vector3f(v.x() / 16, v.y() / 16, v.z() / 16).mulPosition(pose.pose());
                    vertices.add(new Vertex(p.x, -p.y, p.z, v.u(), v.v()));
                }
                Vector3f n = normal(vertices);
                if (n.lengthSquared() < 1e-16f) {
                    // Reversed encoded triangles have duplicate vertices 1/2; restore the ordinary 0/1/2/2 order.
                    List<Vertex> unique = new ArrayList<>();
                    for (Vertex v : vertices) if (unique.stream().noneMatch(p -> p.position().distanceSquared(v.position()) < 1e-16f)) unique.add(v);
                    if (unique.size() < 3) continue;
                    unique.add(unique.getLast()); vertices = unique;
                    n = normal(vertices);
                }
                if (Math.abs(n.normalize().dot(vertices.get(3).position().sub(vertices.getFirst().position()))) > 1e-7f) {
                    faces.add(new Face(sprite, color, List.of(vertices.get(0), vertices.get(1), vertices.get(2), vertices.get(2))));
                    faces.add(new Face(sprite, color, List.of(vertices.get(0), vertices.get(2), vertices.get(3), vertices.get(3))));
                } else faces.add(new Face(sprite, color, List.copyOf(vertices)));
            }
        });
    }

    private static List<Face> normalize(List<Face> faces) {
        Vector3f lo = new Vector3f(Float.POSITIVE_INFINITY), hi = new Vector3f(Float.NEGATIVE_INFINITY);
        for (Face f : faces) for (Vertex v : f.vertices()) { lo.min(v.position()); hi.max(v.position()); }
        Vector3f size = new Vector3f(hi).sub(lo), center = new Vector3f(lo).add(hi).mul(.5f);
        float scale = .875f / Math.max(size.x, Math.max(size.y, size.z));
        List<Face> result = new ArrayList<>(faces.size());
        for (Face f : faces) result.add(new Face(f.texture(), f.color(), f.vertices().stream().map(v -> {
            Vector3f p = v.position().sub(center).mul(scale).add(.5f, .5f, .5f);
            return new Vertex(p.x, p.y, p.z, v.u(), v.v());
        }).toList()));
        return List.copyOf(result);
    }

    private static Vector3f normal(List<Vertex> p) {
        return p.get(1).position().sub(p.getFirst().position()).cross(p.get(2).position().sub(p.getFirst().position()));
    }

    private static NormalKey key(Face face, Vertex v) {
        return new NormalKey(face.texture(), face.color(), Math.round(v.x() * 1000000d), Math.round(v.y() * 1000000d), Math.round(v.z() * 1000000d));
    }

    private static Geometry bake(String id, Garment garment, ModelBaker baker, int[] referenceTints) {
        Map<Identifier, Material.Baked> materials = new LinkedHashMap<>();
        Map<NormalKey, List<WeightedNormal>> adjacency = new HashMap<>();
        for (Face face : garment.faces()) {
            materials.computeIfAbsent(face.texture(), sprite -> baker.materials().get(new Material(sprite), () -> "DarkWinter clothing item " + id));
            Vector3f n = normal(face.vertices()).normalize();
            int count = face.vertices().get(2).position().distanceSquared(face.vertices().get(3).position()) < 1e-16f ? 3 : 4;
            for (int i = 0; i < count; i++) {
                Vertex v = face.vertices().get(i);
                Vector3f a = face.vertices().get((i + count - 1) % count).position().sub(v.position()).normalize();
                Vector3f b = face.vertices().get((i + 1) % count).position().sub(v.position()).normalize();
                float angle = (float) Math.acos(Math.clamp(a.dot(b), -1, 1));
                adjacency.computeIfAbsent(key(face, v), ignored -> new ArrayList<>()).add(new WeightedNormal(n, angle));
            }
        }
        var mutable = Renderer.get().mutableMesh();
        var emitter = mutable.emitter();
        List<BakedQuad> vanilla = new ArrayList<>(garment.faces().size());
        for (Face face : garment.faces()) {
            Vector3f normal = normal(face.vertices()).normalize();
            for (int i = 0; i < 4; i++) {
                Vertex v = face.vertices().get(i);
                Vector3f smooth = new Vector3f();
                for (var candidate : adjacency.getOrDefault(key(face, v), List.of())) if (candidate.direction().dot(normal) >= .7660444f)
                    smooth.fma(candidate.weight(), candidate.direction());
                if (smooth.lengthSquared() < 1e-12f) smooth.set(normal); else smooth.normalize();
                emitter.pos(i, v.x(), v.y(), v.z()).normal(i, smooth).uv(i, v.u() * 16, v.v() * 16).color(i, face.color());
            }
            Material.Baked material = materials.get(face.texture());
            emitter.materialBake(material, 0).tag(CLOTHING_QUAD_TAG).cullFace(null).tintIndex(closestTint(face.color(), referenceTints)).ambientOcclusion(TriState.FALSE);
            vanilla.add(emitter.toBakedQuad(material.sprite()));
            emitter.emit();
        }
        Mesh mesh = mutable.immutableCopy();
        return new Geometry(mesh, List.copyOf(materials.values()), new MeshQuadCollection(mesh).materialFlags(), CuboidItemModelWrapper.computeExtents(vanilla));
    }

    private static int closestTint(int color, int[] tints) {
        int best = -1, distance = Integer.MAX_VALUE;
        for (int i = 0; i < tints.length; i++) {
            int d = 0;
            for (int shift : new int[]{0, 8, 16}) { int delta = ((color >>> shift) & 255) - ((tints[i] >>> shift) & 255); d += delta * delta; }
            if (d < distance) { best = i; distance = d; }
        }
        return best;
    }

    private static TintReferences referenceTints(List<ItemTintSource> tints) {
        int[] colors = new int[tints.size()];
        boolean[] ready = new boolean[tints.size()];
        for (int i = 0; i < tints.size(); i++) {
            // Initial item model baking precedes registry component binding. Constants already
            // contain their opaque value; stack-dependent sources wait for a real bound stack.
            if (tints.get(i) instanceof Constant constant) { colors[i] = constant.value(); ready[i] = true; }
            else colors[i] = 0xFFFFFFFF;
        }
        return new TintReferences(colors, ready);
    }

    private static int tintRelativeToReference(int color, int current, int reference) {
        if (current == reference) return color;
        int result = color & 0xFF000000;
        for (int shift : new int[]{0, 8, 16}) {
            int base = (reference >>> shift) & 255, target = (current >>> shift) & 255;
            int component = Math.clamp(Math.round(((color >>> shift) & 255) * target / (float) Math.max(1, base)), 0, 255);
            result |= component << shift;
        }
        return result;
    }

    private record GarmentItem(String clothingId, int sourcePieceCount, Geometry geometry, ModelRenderProperties properties,
                               Matrix4fc transformation, List<ItemTintSource> tints, TintReferences references) implements ItemModel, MeshBackedItem {
        @Override public Mesh mesh() { return geometry.mesh(); }
        @Override public List<Material.Baked> materials() { return geometry.materials(); }
        @Override public int sourceTintCount() { return tints.size(); }
        @Override public void update(ItemStackRenderState output, ItemStack stack, ItemModelResolver resolver, ItemDisplayContext context,
                                     @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
            output.appendModelIdentityElement(this);
            var layer = output.newLayer();
            int[] current = new int[tints.size()];
            boolean changed = false;
            for (int i = 0; i < current.length; i++) {
                current[i] = tints.get(i).calculate(stack, level, owner == null ? null : owner.asLivingEntity());
                if (!references.ready()[i]) { references.colors()[i] = current[i]; references.ready()[i] = true; }
                layer.tintLayers().add(current[i]); output.appendModelIdentityElement(current[i]);
                changed |= current[i] != references.colors()[i];
            }
            layer.setLocalTransform(transformation);
            layer.setExtents(geometry::extents);
            properties.applyToLayer(layer, context);
            if (stack.hasFoil()) {
                layer.setFoilType(ItemStackRenderState.FoilType.STANDARD);
                output.appendModelIdentityElement(ItemStackRenderState.FoilType.STANDARD); output.setAnimated();
            }
            var emitter = layer.emitter();
            final boolean tintChanged = changed;
            emitter.pushTransform(quad -> {
                int index = quad.tintIndex();
                if (tintChanged && index >= 0 && index < current.length) for (int v = 0; v < 4; v++)
                    quad.color(v, tintRelativeToReference(quad.color(v), current[index], references.colors()[index]));
                quad.tintIndex(-1); // Piece colors already contain the original tint; apply only subsequent tint changes.
                return true;
            });
            try { geometry.mesh().outputTo(emitter); } finally { emitter.popTransform(); }
            if ((geometry.flags() & BakedQuad.FLAG_ANIMATED) != 0) output.setAnimated();
        }
    }

    private ClothingItemModels() { }
}
