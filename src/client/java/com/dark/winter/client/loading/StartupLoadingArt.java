// Bootstrap presentation: no font, sprite atlas, world state or reload lifecycle dependency.
package com.dark.winter.client.loading;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/** Bootstrap presentation: no font, sprite atlas, world state or reload lifecycle dependency. */
public final class StartupLoadingArt {
    public static final int BACKGROUND = 0xFF08131E;
    private static final Identifier WORDMARK = Identifier.fromNamespaceAndPath(
            "darkwinter", "textures/gui/cinematic/wordmark.png");

    private StartupLoadingArt() { }

    public static void register(TextureManager textures) {
        // The same packaged wordmark as the menus, loaded before resource preparation.
        textures.registerAndLoad(WORDMARK, new BootstrapWordmark());
    }

    public static void brand(GuiGraphicsExtractor g, RenderPipeline pipeline, int color) {
        int width = g.guiWidth(), height = g.guiHeight();
        int alpha = color >>> 24;
        g.fillGradient(0, 0, width, height, tint(0x1A2A36, alpha / 3), tint(0x030A12, alpha / 2));
        g.fill(18, 18, width - 18, 19, tint(0x537384, alpha / 3));
        g.fill(18, height - 20, width - 18, height - 19, tint(0x537384, alpha / 4));
        int wordWidth = Math.max(1, Math.min(420, Math.round(width * .64f)));
        int wordHeight = Math.max(1, wordWidth / 8);
        int x = (width - wordWidth) / 2, y = Math.round(height * .43f) - wordHeight / 2;
        // Retain the original, already available bootstrap pipeline; only its texture changes.
        g.blit(pipeline, WORDMARK, x, y, 0, 0, wordWidth, wordHeight,
                1536, 192, 1536, 192, tint(0xDCE8EB, alpha));
        int lineWidth = Math.min(60, wordWidth / 4);
        g.fill((width - lineWidth) / 2, y + wordHeight + 16,
                (width + lineWidth) / 2, y + wordHeight + 17, tint(0x93AEBB, alpha / 2));
    }

    public static void progress(GuiGraphicsExtractor g, float actualProgress, float fade) {
        int width = Math.max(1, Math.min(320, g.guiWidth() - 48));
        int x = (g.guiWidth() - width) / 2, y = Math.round(g.guiHeight() * .74f);
        int alpha = Math.round(255 * Math.clamp(fade, 0, 1));
        g.fill(x, y, x + width, y + 3, tint(0x1A2D3B, alpha));
        g.fill(x, y + 3, x + width, y + 4, tint(0x537384, alpha / 2));
        int complete = Math.round(width * Math.clamp(actualProgress, 0, 1));
        if (complete > 0) {
            g.fillGradient(x, y, x + complete, y + 3, tint(0xDCE8EB, alpha), tint(0x668EA4, alpha));
            g.fill(x + complete - 1, y - 1, x + complete, y + 4, tint(0xDCE8EB, alpha));
        }
    }

    private static int tint(int rgb, int alpha) { return Math.clamp(alpha, 0, 255) << 24 | rgb; }

    private static final class BootstrapWordmark extends ReloadableTexture {
        private BootstrapWordmark() { super(WORDMARK); }

        @Override
        public TextureContents loadContents(ResourceManager resources) throws IOException {
            try (InputStream image = StartupLoadingArt.class.getResourceAsStream(
                    "/assets/darkwinter/textures/gui/cinematic/wordmark.png")) {
                if (image == null) throw new IOException("DarkWinter bootstrap wordmark is missing");
                return new TextureContents(NativeImage.read(image),
                        new TextureMetadataSection(true, true, MipmapStrategy.MEAN, 0.0f));
            }
        }
    }
}
