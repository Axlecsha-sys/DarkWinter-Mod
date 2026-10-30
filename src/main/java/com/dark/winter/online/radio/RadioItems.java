// Keeps the retired radio-item prototype inert for compatibility.
package com.dark.winter.online.radio;

/**
 * Retired prototype. The Testing Online phase has no radio/battery items,
 * registry IDs, creative entries, recipes, use handlers or inventory mechanics.
 */
@Deprecated(forRemoval=true)
public final class RadioItems {
    private RadioItems(){}
    /** Kept only for source compatibility with an old initialization call; it cannot register content. */
    @Deprecated(forRemoval=true)
    public static void init(){}
}
