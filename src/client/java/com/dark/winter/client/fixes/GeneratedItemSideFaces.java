// Merges generated-item edge pixels into continuous side quads for each animation frame.
package com.dark.winter.client.fixes;

import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class GeneratedItemSideFaces {
    private static final float UV_INSET = 0.1F;

    private GeneratedItemSideFaces() {}

    public enum Side {
        UP(true, 0, -1), DOWN(true, 0, 1), LEFT(false, -1, 0), RIGHT(false, 1, 0);

        private final boolean horizontal;
        private final int neighborX;
        private final int neighborY;

        Side(boolean horizontal, int neighborX, int neighborY) {
            this.horizontal = horizontal;
            this.neighborX = neighborX;
            this.neighborY = neighborY;
        }
    }

    @FunctionalInterface
    public interface PixelOpacity {
        boolean opaque(int frame, int x, int y);
    }

    public record Face(Side side, int startX, int startY, int endX, int endY) {
        public Quad quad(int width, int height) {
            float xScale = 16.0F / width;
            float yScale = 16.0F / height;
            float u0 = (startX + UV_INSET) * xScale;
            float u1 = (endX + 1.0F - UV_INSET) * xScale;
            float v0 = (side.horizontal ? startY + UV_INSET : endY + 1.0F - UV_INSET) * yScale;
            float v1 = (side.horizontal ? endY + 1.0F - UV_INSET : startY + UV_INSET) * yScale;
            float x0 = startX;
            float y0 = startY;
            float x1 = endX;
            float y1 = endY;
            switch (side) {
                case UP -> x1++;
                case DOWN -> { x1++; y0++; y1++; }
                case LEFT -> y1++;
                case RIGHT -> { x0++; x1++; y1++; }
            }
            return new Quad(side, x0 * xScale, 16.0F - y0 * yScale,
                    x1 * xScale, 16.0F - y1 * yScale, u0, v0, u1, v1);
        }
    }

    public record Quad(Side side, float x0, float y0, float x1, float y1,
                       float u0, float v0, float u1, float v1) {}

    public static List<Face> collect(int width, int height, int[] frames, PixelOpacity pixels) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid sprite size");
        int pixelCount = Math.multiplyExact(width, height);
        BitSet opaque = new BitSet(pixelCount);
        Set<Face> faces = new LinkedHashSet<>();
        for (int frame : frames) {
            opaque.clear();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (pixels.opaque(frame, x, y)) opaque.set(x + y * width);
                }
            }
            for (Side side : Side.values()) {
                int lines = side.horizontal ? height : width;
                int lineLength = side.horizontal ? width : height;
                for (int line = 0; line < lines; line++) {
                    int start = -1;
                    for (int pixel = 0; pixel <= lineLength; pixel++) {
                        int x = side.horizontal ? pixel : line;
                        int y = side.horizontal ? line : pixel;
                        boolean edge = pixel < lineLength && at(opaque, width, height, x, y)
                                && !at(opaque, width, height, x + side.neighborX, y + side.neighborY);
                        if (edge && start == -1) start = pixel;
                        if (!edge && start != -1) {
                            int startX = side.horizontal ? start : line;
                            int startY = side.horizontal ? line : start;
                            int endX = side.horizontal ? pixel - 1 : line;
                            int endY = side.horizontal ? line : pixel - 1;
                            faces.add(new Face(side, startX, startY, endX, endY));
                            start = -1;
                        }
                    }
                }
            }
        }
        return List.copyOf(faces);
    }

    private static boolean at(BitSet opaque, int width, int height, int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height && opaque.get(x + y * width);
    }
}
