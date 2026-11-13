// Host-independent rendering settings contract shared by WKE frontends.
package com.winterkore.engine.graphics;

/** Policy only: hosts own persistence, texture reloads and graphics menus. */
public final class WkeGraphicsPolicy {
    public static final int MIN_MIP_LEVELS = 0;
    public static final int MAX_MIP_LEVELS = 4;
    public static final int DEFAULT_MIP_LEVELS = 4;
    // Preserve existing authored profiles; film is now an independent user choice.
    public static final boolean DEFAULT_FILM_ENABLED = true;
    // Preserve the scene's existing volume, weather-sky and background module bits.
    public static final int FILM_SCENE_FLAG = 1 << 3;

    private WkeGraphicsPolicy() { }

    public static int clampMipLevels(int levels) {
        return Math.clamp(levels, MIN_MIP_LEVELS, MAX_MIP_LEVELS);
    }

    public static int filmSceneFlag(boolean enabled) {
        return enabled ? FILM_SCENE_FLAG : 0;
    }
}
