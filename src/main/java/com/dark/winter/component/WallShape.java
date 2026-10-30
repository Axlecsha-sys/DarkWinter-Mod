// Represents a wall module shape relative to its shared master origin.
package com.dark.winter.component;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

import java.util.ArrayList;
import java.util.List;

public enum WallShape implements StringRepresentable {
    WALL_1X1("1x1", 1, 1, "Сегмент 1x1"),
    WALL_1X3("1x3", 1, 3, "Колонна 1x3"),
    WALL_2X2("2x2", 2, 2, "Стена 2x2"),
    WALL_2X3("2x3", 2, 3, "Стена 2x3"),
    WALL_3X3("3x3", 3, 3, "Стена 3x3");

    public static final Codec<WallShape> CODEC = StringRepresentable.fromEnum(WallShape::values);
    public static final StreamCodec<ByteBuf, WallShape> STREAM_CODEC = ByteBufCodecs.idMapper(
            id -> values()[Math.clamp(id, 0, values().length - 1)],
            WallShape::ordinal
    );

    private final String name;
    private final int width;
    private final int height;
    private final String displayName;

    WallShape(String name, int width, int height, String displayName) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.displayName = displayName;
    }

    public int width() { return width; }
    public int height() { return height; }
    public String displayName() { return displayName; }

    @Override
    public String getSerializedName() { return name; }

    /**
     * Генерирует все координаты блоков стены относительно центральной нижней точки (origin).
     */
    public List<BlockPos> getPositions(BlockPos origin, Direction facing) {
        List<BlockPos> positions = new ArrayList<>(width * height);
        Direction rightDir = facing.getClockWise();
        int startW = -(width - 1) / 2; // Для 3: -1..1, для 2: 0..1, для 1: 0..0

        for (int h = 0; h < height; h++) {
            for (int w = 0; w < width; w++) {
                positions.add(origin.relative(rightDir, startW + w).above(h));
            }
        }
        return positions;
    }
}