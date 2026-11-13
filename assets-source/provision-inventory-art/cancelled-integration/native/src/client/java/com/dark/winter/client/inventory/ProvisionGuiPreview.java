package com.dark.winter.client.inventory;

import com.dark.winter.registry.ModProvisionItems;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Native provision models at the actual preview resolution, rather than enlarged 16-pixel icons. */
public final class ProvisionGuiPreview {
    private static final int MIN_PREVIEW_PIXELS = 48;
    private static final Set<String> PANTRY_PROPS = Set.of("canned_meat", "canned_fish", "canned_beans",
            "oat_carton", "cracker_box", "ration_bundle", "water_bottle");
    private static boolean initialized;

    private ProvisionGuiPreview() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ProvisionInventoryArt.initialize();
        PictureInPictureRendererRegistry.register(context -> new PreviewRenderer());
    }

    /** Returns false for other items and small native icons, which retain the usual GUI atlas path. */
    public static boolean extract(GuiGraphicsExtractor graphics, ItemStack stack, float x, float y, float size) {
        return extractInRectangle(graphics, stack, x, y, size, size);
    }

    /** The same 47 native food models; other item rendering remains outside this preview path. */
    public static boolean supports(ItemStack stack) {
        if (stack.isEmpty()) return false;
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return ModProvisionItems.contains(stack.getItem())
                || id.getNamespace().equals("darkwinter") && PANTRY_PROPS.contains(id.getPath());
    }

    /** Fits the actual transformed mesh to a rectangular inventory area with one uniform model scale. */
    public static boolean extractInRectangle(GuiGraphicsExtractor graphics, ItemStack stack,
                                              float x, float y, float width, float height) {
        if (!supports(stack) || !(width > 0) || !(height > 0)) return false;
        if (ProvisionInventoryArt.extract(graphics, stack, x, y, width, height)) return true;
        var pose = graphics.pose();
        // The inventory fits its composition uniformly. Other transforms retain their native rendering.
        if (Math.abs(pose.m01()) > 1e-6f || Math.abs(pose.m10()) > 1e-6f
                || pose.m00() <= 0 || Math.abs(pose.m00() - pose.m11()) > 1e-6f) return false;
        var client = Minecraft.getInstance();
        float screenWidth = width * pose.m00(), screenHeight = height * pose.m11();
        float screenSize = Math.max(screenWidth, screenHeight);
        int guiScale = client.getWindow().getGuiScale();
        if (screenSize * guiScale <= Math.max(MIN_PREVIEW_PIXELS, 16 * guiScale)) return false;

        float left = pose.m00() * x + pose.m20();
        float top = pose.m11() * y + pose.m21();
        int x0 = Mth.floor(left), y0 = Mth.floor(top);
        int x1 = Mth.ceil(left + screenWidth), y1 = Mth.ceil(top + screenHeight);
        ScreenRectangle scissor = graphics.scissorStack.peek();
        ScreenRectangle bounds = PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissor);
        if (bounds == null) return true;

        var item = new TrackingItemStackRenderState();
        client.getItemModelResolver().updateForTopItem(item, stack, ItemDisplayContext.GUI, client.level, client.player, 0);
        if (item.isEmpty()) return false;
        var modelBounds = item.getModelBoundingBox();
        float modelWidth = (float)modelBounds.getXsize(), modelHeight = (float)modelBounds.getYsize();
        // Separate available width/height, then use the smaller fit for both model axes. Square previews
        // therefore retain their previous scale; tall grid cells no longer become empty square icons.
        float fit = modelWidth > 1e-5f && modelHeight > 1e-5f
                ? Math.min(8f, .94f * Math.min(screenWidth / screenSize / modelWidth,
                                             screenHeight / screenSize / modelHeight)) : 1f;
        // Bounds are already in screen GUI coordinates: no outer pose may enlarge the PIP texture later.
        graphics.guiRenderState.addPicturesInPictureState(new PreviewState(item, x0, y0, x1, y1, screenSize,
                left + screenWidth / 2 - (x0 + x1) / 2f,
                top + screenHeight / 2 - (y0 + y1) / 2f, fit,
                (float)modelBounds.getCenter().x, (float)modelBounds.getCenter().y, scissor, bounds));
        return true;
    }

    private record PreviewState(TrackingItemStackRenderState item, int x0, int y0, int x1, int y1,
                                float scale, float centerOffsetX, float centerOffsetY,
                                float modelFit, float modelCenterX, float modelCenterY,
                                @Nullable ScreenRectangle scissorArea,
                                @Nullable ScreenRectangle bounds) implements PictureInPictureRenderState {}

    private static final class PreviewRenderer extends PictureInPictureRenderer<PreviewState> {
        private @Nullable Object modelIdentity;
        private float modelScale, centerOffsetX, centerOffsetY, modelFit;

        @Override public Class<PreviewState> getRenderStateClass() { return PreviewState.class; }

        @Override protected float getTranslateY(int height, int guiScale) { return height / 2f; }

        @Override protected String getTextureLabel() { return "darkwinter_provision_preview"; }

        @Override protected boolean textureIsReadyToBlit(PreviewState state) {
            return !state.item().isAnimated() && Objects.equals(modelIdentity, state.item().getModelIdentity())
                    && modelScale == state.scale() && centerOffsetX == state.centerOffsetX()
                    && centerOffsetY == state.centerOffsetY() && modelFit == state.modelFit();
        }

        @Override protected void renderToTexture(PreviewState state, PoseStack pose, SubmitNodeCollector nodes) {
            // Match GuiItemAtlas's orientation, lighting and full-bright item submission exactly.
            pose.scale(1, -1, -1);
            pose.translate(state.centerOffsetX() / state.scale(), -state.centerOffsetY() / state.scale(), 0);
            pose.scale(state.modelFit(), state.modelFit(), state.modelFit());
            pose.translate(-state.modelCenterX(), -state.modelCenterY(), 0);
            Minecraft.getInstance().gameRenderer.lighting().setupFor(
                    state.item().usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);
            state.item().submit(pose, nodes, 15728880, OverlayTexture.NO_OVERLAY, 0);
            modelIdentity = state.item().getModelIdentity();
            modelScale = state.scale();
            centerOffsetX = state.centerOffsetX();
            centerOffsetY = state.centerOffsetY();
            modelFit = state.modelFit();
        }
    }
}
