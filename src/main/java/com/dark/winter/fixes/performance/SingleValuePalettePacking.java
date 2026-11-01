// Avoids a discarded re-encoding scratch array for a native single-value palette.
package com.dark.winter.fixes.performance;

import net.minecraft.util.BitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;
import org.jspecify.annotations.Nullable;

public final class SingleValuePalettePacking {
    private static final int[] EMPTY_CONTENTS = new int[0];

    private SingleValuePalettePacking() {}

    /** Used only by native pack, whose single-entry strategy writes no contents array. */
    public static <T> int @Nullable [] tryReencode(BitStorage storage, Palette<T> oldPalette, Palette<T> newPalette) {
        // A subclass may override unpack; only the native implementation guarantees every index is zero.
        if (storage.getClass() != ZeroBitStorage.class || storage.getSize() <= 0
                || oldPalette.getSize() != 1 || newPalette.getSize() != 0) {
            return null;
        }
        newPalette.idFor(oldPalette.valueFor(0), PaletteResize.noResizeExpected());
        return EMPTY_CONTENTS;
    }
}
