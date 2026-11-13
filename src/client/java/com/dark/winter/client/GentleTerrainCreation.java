// Applies the explicit single-biome terrain choice before a new world's dimensions are saved.
package com.dark.winter.client;

import com.dark.winter.worldgen.GentleTestTerrain;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

public final class GentleTerrainCreation {
    private GentleTerrainCreation() {}

    public static void prepareCreation(CreateWorldScreen screen) {
        var state = screen.getUiState();
        if (!GentleTestTerrain.eligible(state.getSettings().selectedDimensions().overworld())) return;
        state.updateDimensions((access, dimensions) -> dimensions.replaceOverworldGenerator(access,
                GentleTestTerrain.forExplicitNewWorld(access, dimensions.overworld())));
    }
}
