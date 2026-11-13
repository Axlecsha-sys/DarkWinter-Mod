package com.dark.winter.client.inventory;

import com.dark.winter.DarkWinter;
import com.dark.winter.client.FurnitureMeshModels;
import com.dark.winter.client.ProvisionItemModels;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Inventory-only artwork. Every non-GUI render is delegated to the existing native item model. */
public final class ProvisionInventoryArt {
    public static final Identifier RESOURCE = Identifier.fromNamespaceAndPath("darkwinter", "provisions/inventory_art.json");
    public static final int BITMAP_QUAD_TAG = 0x445749;
    private static final String TEXTURE_PREFIX = "textures/gui/provisions_inventory/";
    private static final String SPRITE_PREFIX = "item/provisions_inventory/";
    private static volatile Map<Identifier, ImageInfo> images = Map.of();
    private static boolean initialized;

    /** Bounds are pixels in the high-resolution canvas; the slot sprite keeps that canvas aspect. */
    public record ImageInfo(Identifier item, Identifier texture, Identifier sprite, int width, int height,
                            int x, int y, int contentWidth, int contentHeight,
                            int spriteWidth, int spriteHeight) {}

    /** Exposes the unchanged delegate for native integration checks, without altering its mesh interfaces. */
    public interface BitmapGuiModel extends ItemModel {
        ItemModel nonGuiModel();
        ImageInfo inventoryImage();
    }

    private ProvisionInventoryArt() {}

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        // ProvisionGuiPreview is initialized after both native mesh plugins. Fabric retains this
        // registration order, so the delegate here is their complete, already-baked native model.
        PreparableModelLoadingPlugin.register(
                (shared, executor) -> CompletableFuture.supplyAsync(() -> read(shared.resourceManager()), executor),
                (data, plugin) -> {
                    images = data;
                    plugin.modifyItemModelAfterBake().register((model, context) -> {
                        ImageInfo image = data.get(context.itemId());
                        if (image == null || !(model instanceof FurnitureMeshModels.MeshBackedModel)) return model;
                        var material = context.bakingContext().blockModelBaker().materials().get(
                                new Material(image.sprite()), () -> "DarkWinter inventory artwork " + image.item());
                        if (material.sprite().contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
                            DarkWinter.LOGGER.warn("Inventory sprite was not stitched for {}; keeping its native GUI fallback", image.item());
                            return model;
                        }
                        var bitmap = bake(image, material);
                        if (model instanceof ProvisionItemModels.ProvisionMeshModel provision)
                            return new ProvisionBitmapModel(provision, image, material, bitmap);
                        return new PantryBitmapModel(model, (FurnitureMeshModels.MeshBackedModel)model, image, material, bitmap);
                    });
                });
    }

    public static Map<Identifier, ImageInfo> images() { return images; }

    public static @Nullable ImageInfo image(ItemStack stack) {
        return stack.isEmpty() ? null : images.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** Full-resolution, untinted PNG drawing for the custom inventory grid and large detail view. */
    public static boolean extract(GuiGraphicsExtractor graphics, ItemStack stack,
                                  float x, float y, float width, float height) {
        ImageInfo image = image(stack);
        if (image == null || !(width > 0) || !(height > 0)) return false;
        float fit = .94f * Math.min(width / image.contentWidth(), height / image.contentHeight());
        float left = x + (width - image.contentWidth() * fit) / 2;
        float top = y + (height - image.contentHeight() * fit) / 2;
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(left, top);
        pose.scale(fit, fit);
        graphics.blit(RenderPipelines.GUI_TEXTURED, image.texture(), 0, 0, image.x(), image.y(),
                image.contentWidth(), image.contentHeight(), image.contentWidth(), image.contentHeight(),
                image.width(), image.height(), 0xFFFFFFFF);
        pose.popMatrix();
        return true;
    }

    private static Map<Identifier, ImageInfo> read(ResourceManager resources) {
        var resource = resources.getResource(RESOURCE);
        if (resource.isEmpty()) return Map.of();
        Map<Identifier, ImageInfo> result = new LinkedHashMap<>();
        try (var reader = resource.get().openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            require(root.get("schema").getAsInt() == 1, "Expected inventory artwork schema1");
            var entries = root.getAsJsonObject("entries");
            require(entries != null, "Inventory artwork requires entries keyed by item id");
            for (var entry : entries.entrySet()) {
                Identifier itemId = entry.getKey().contains(":") ? Identifier.parse(entry.getKey())
                        : Identifier.fromNamespaceAndPath("darkwinter", entry.getKey());
                var item = BuiltInRegistries.ITEM.getValue(itemId);
                require(itemId.getNamespace().equals("darkwinter") && item != null
                        && ProvisionGuiPreview.supports(new ItemStack(item)), "Artwork entry is outside the47 provision items: " + itemId);
                var value = entry.getValue().getAsJsonObject();
                Identifier texture = Identifier.parse(value.get("texture").getAsString());
                Identifier sprite = Identifier.parse(value.get("sprite").getAsString());
                require(texture.equals(Identifier.fromNamespaceAndPath("darkwinter", TEXTURE_PREFIX + itemId.getPath() + ".png")),
                        "Unexpected high-resolution artwork path for " + itemId);
                require(sprite.equals(Identifier.fromNamespaceAndPath("darkwinter", SPRITE_PREFIX + itemId.getPath())),
                        "Unexpected slot artwork path for " + itemId);
                int width = value.get("width").getAsInt(), height = value.get("height").getAsInt();
                var bounds = value.getAsJsonArray("bounds");
                require(bounds != null && bounds.size() == 4, "Expected pixel bounds[x,y,w,h] for " + itemId);
                int x = bounds.get(0).getAsInt(), y = bounds.get(1).getAsInt();
                int contentWidth = bounds.get(2).getAsInt(), contentHeight = bounds.get(3).getAsInt();
                int longest = Math.max(width, height);
                int spriteWidth = value.has("spriteWidth") ? value.get("spriteWidth").getAsInt()
                        : Math.max(1, Math.round(width * 512f / longest));
                int spriteHeight = value.has("spriteHeight") ? value.get("spriteHeight").getAsInt()
                        : Math.max(1, Math.round(height * 512f / longest));
                require(width > 0 && height > 0 && width <= 8192 && height <= 8192
                        && x >= 0 && y >= 0 && contentWidth > 0 && contentHeight > 0
                        && x + contentWidth <= width && y + contentHeight <= height,
                        "Inventory artwork bounds leave their canvas for " + itemId);
                require(spriteWidth > 0 && spriteHeight > 0 && spriteWidth <= 1024 && spriteHeight <= 1024
                        && Math.abs(width / (double)height - spriteWidth / (double)spriteHeight) < .005,
                        "Slot sprite must preserve high-resolution canvas aspect for " + itemId);
                Identifier spritePng = sprite.withPath("textures/" + sprite.getPath() + ".png");
                // Partial delivery is useful while authoring. Missing images keep the old native GUI
                // per item; the final native fixture separately requires all47 ready image pairs.
                if (resources.getResource(texture).isEmpty() || resources.getResource(spritePng).isEmpty()) continue;
                result.put(itemId, new ImageInfo(itemId, texture, sprite, width, height, x, y,
                        contentWidth, contentHeight, spriteWidth, spriteHeight));
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot load " + RESOURCE + ": " + exception.getMessage(), exception);
        }
        return Map.copyOf(result);
    }

    private static Mesh bake(ImageInfo image, Material.Baked material) {
        float largest = Math.max(image.contentWidth(), image.contentHeight());
        float width = image.contentWidth() / largest, height = image.contentHeight() / largest;
        float x0 = .5f - width / 2, x1 = .5f + width / 2, y0 = .5f - height / 2, y1 = .5f + height / 2;
        float u0 = image.x() / (float)image.width(), u1 = (image.x() + image.contentWidth()) / (float)image.width();
        float v0 = image.y() / (float)image.height(), v1 = (image.y() + image.contentHeight()) / (float)image.height();
        var mutable = Renderer.get().mutableMesh();
        var quad = mutable.emitter();
        quad.pos(0, x0, y1, .5f).uv(0, u0, v0);
        quad.pos(1, x0, y0, .5f).uv(1, u0, v1);
        quad.pos(2, x1, y0, .5f).uv(2, u1, v1);
        quad.pos(3, x1, y1, .5f).uv(3, u1, v0);
        for (int vertex = 0; vertex < 4; vertex++) quad.color(vertex, 0xFFFFFFFF).normal(vertex, 0, 0, 1);
        // The PNG already contains its authored color/shading. This flat GUI quad must not add
        // directional or ambient shading; it is never submitted in a world or held context.
        quad.materialBake(material, MutableQuadView.BAKE_NORMALIZED).tag(BITMAP_QUAD_TAG)
                .cullFace(null).tintIndex(-1).ambientOcclusion(TriState.FALSE).emissive(true).emit();
        return mutable.immutableCopy();
    }

    private abstract static class GuiModel implements BitmapGuiModel {
        protected final ItemModel delegate;
        private final ImageInfo image;
        private final Material.Baked material;
        private final Mesh bitmap;
        GuiModel(ItemModel delegate, ImageInfo image, Material.Baked material, Mesh bitmap) {
            this.delegate = delegate; this.image = image; this.material = material; this.bitmap = bitmap;
        }
        @Override public final ItemModel nonGuiModel() { return delegate; }
        @Override public final ImageInfo inventoryImage() { return image; }
        @Override public final void update(ItemStackRenderState output, ItemStack stack, ItemModelResolver resolver,
                                           ItemDisplayContext display, @Nullable ClientLevel level,
                                           @Nullable ItemOwner owner, int seed) {
            if (display != ItemDisplayContext.GUI) {
                delegate.update(output, stack, resolver, display, level, owner, seed);
                return;
            }
            output.appendModelIdentityElement(this);
            var layer = output.newLayer();
            layer.setUsesBlockLight(false);
            layer.setParticleMaterial(material);
            if (stack.hasFoil()) {
                layer.setFoilType(ItemStackRenderState.FoilType.STANDARD);
                output.setAnimated();
                output.appendModelIdentityElement(ItemStackRenderState.FoilType.STANDARD);
            }
            bitmap.outputTo(layer.emitter());
        }
    }

    private static final class ProvisionBitmapModel extends GuiModel implements ProvisionItemModels.ProvisionMeshModel {
        private final ProvisionItemModels.ProvisionMeshModel original;
        ProvisionBitmapModel(ProvisionItemModels.ProvisionMeshModel original, ImageInfo image, Material.Baked material, Mesh bitmap) {
            super((ItemModel)original, image, material, bitmap); this.original = original;
        }
        @Override public Mesh mesh() { return original.mesh(); }
        @Override public Mesh mesh(ItemStack stack) { return original.mesh(stack); }
        @Override public Mesh mesh(BlockPos pos) { return original.mesh(pos); }
        @Override public String furnitureId() { return original.furnitureId(); }
        @Override public int sourceFaceCount() { return original.sourceFaceCount(); }
        @Override public List<Material.Baked> materials() { return original.materials(); }
    }

    private static final class PantryBitmapModel extends GuiModel implements FurnitureMeshModels.MeshBackedModel {
        private final FurnitureMeshModels.MeshBackedModel original;
        PantryBitmapModel(ItemModel delegate, FurnitureMeshModels.MeshBackedModel original, ImageInfo image, Material.Baked material, Mesh bitmap) {
            super(delegate, image, material, bitmap); this.original = original;
        }
        @Override public Mesh mesh() { return original.mesh(); }
        @Override public Mesh mesh(ItemStack stack) { return original.mesh(stack); }
        @Override public Mesh mesh(BlockPos pos) { return original.mesh(pos); }
        @Override public String furnitureId() { return original.furnitureId(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
