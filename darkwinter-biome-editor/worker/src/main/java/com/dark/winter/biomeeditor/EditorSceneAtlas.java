// Retains immutable CPU copies of native stitched sprite inputs for local editor rendering.
package com.dark.winter.biomeeditor;

import com.dark.winter.biomeeditor.mixin.EditorSpriteContentsAccess;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;

public final class EditorSceneAtlas {
    private static final AtomicLong EPOCH = new AtomicLong();
    private static volatile Snapshot snapshot;
    private static volatile Throwable failure;
    private EditorSceneAtlas() {}

    record Sprite(int x, int y, int width, int height, int padding, byte[] rgba) {}
    record Snapshot(long epoch, int width, int height, List<Sprite> sprites) {
        Snapshot { sprites = List.copyOf(sprites); }
        byte[] stitch() {
            byte[] atlas = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
            for (Sprite sprite : sprites) {
                int paddedWidth = sprite.width + 2 * sprite.padding;
                int paddedHeight = sprite.height + 2 * sprite.padding;
                for (int y = 0; y < paddedHeight; y++) {
                    int sourceY = Math.clamp(y - sprite.padding, 0, sprite.height - 1);
                    for (int x = 0; x < paddedWidth; x++) {
                        int sourceX = Math.clamp(x - sprite.padding, 0, sprite.width - 1);
                        int source = (sourceY * sprite.width + sourceX) * 4;
                        int target = ((sprite.y + y) * width + sprite.x + x) * 4;
                        System.arraycopy(sprite.rgba, source, atlas, target, 4);
                    }
                }
            }
            return atlas;
        }
    }

    /** Called while the native reload still owns all sprite CPU images; no image escapes this method. */
    public static void capture(TextureAtlas atlas, SpriteLoader.Preparations preparations) {
        if (!atlas.location().equals(TextureAtlas.LOCATION_BLOCKS)) return;
        long epoch = EPOCH.incrementAndGet();
        snapshot = null;
        failure = null;
        try {
            if (!preparations.readyForUpload().isDone()
                    || preparations.readyForUpload().isCompletedExceptionally()
                    || preparations.readyForUpload().isCancelled()) {
                throw new IllegalStateException("Native atlas CPU preparations are not ready");
            }
            int width = preparations.width(), height = preparations.height();
            if (width < 1 || height < 1 || width > 8192 || height > 8192
                    || (long) width * height * 4 > 256L * 1024 * 1024) {
                throw new IllegalStateException("Native block atlas exceeds the scene protocol");
            }
            List<Sprite> copies = new ArrayList<>();
            long copied = 0;
            for (var sprite : preparations.regions().values()) {
                var contents = sprite.contents();
                NativeImage image = ((EditorSpriteContentsAccess) contents).editor$mipImages()[0];
                if (image.format() != NativeImage.Format.RGBA) throw new IllegalStateException("Non-RGBA native sprite");
                int w = contents.width(), h = contents.height();
                int padding = Math.round(sprite.getU0() * width) - sprite.getX();
                if (w < 1 || h < 1 || padding < 0 || sprite.getX() < 0 || sprite.getY() < 0
                        || sprite.getX() + w + padding * 2 > width || sprite.getY() + h + padding * 2 > height) {
                    throw new IllegalStateException("Invalid native sprite bounds: " + contents.name());
                }
                int frame = contents.isAnimated() ? contents.getUniqueFrames().getInt(0) : 0;
                int columns = image.getWidth() / w;
                if (columns < 1 || frame < 0) throw new IllegalStateException("Invalid native sprite sheet");
                int sx = (frame % columns) * w, sy = (frame / columns) * h;
                if (sx + w > image.getWidth() || sy + h > image.getHeight()) throw new IllegalStateException("Invalid initial animation frame");
                copied += (long) w * h * 4;
                if (copied > 256L * 1024 * 1024) throw new IllegalStateException("Excessive native sprite CPU copies");
                byte[] pixels = new byte[Math.multiplyExact(Math.multiplyExact(w, h), 4)];
                ByteBuffer source = image.getPixelBytes();
                for (int y = 0; y < h; y++) source.get(((sy + y) * image.getWidth() + sx) * 4, pixels, y * w * 4, w * 4);
                copies.add(new Sprite(sprite.getX(), sprite.getY(), w, h, padding, pixels));
            }
            snapshot = new Snapshot(epoch, width, height, copies);
        } catch (Throwable error) {
            failure = error;
        }
    }

    static Snapshot snapshot() {
        Throwable error = failure;
        if (error != null) throw new IllegalStateException("Native block atlas capture failed", error);
        Snapshot value = snapshot;
        if (value == null) throw new IllegalStateException("Native block atlas has not been captured");
        return value;
    }
    public static long resourceEpoch() { Snapshot value = snapshot; return value == null ? EPOCH.get() : value.epoch; }
}
