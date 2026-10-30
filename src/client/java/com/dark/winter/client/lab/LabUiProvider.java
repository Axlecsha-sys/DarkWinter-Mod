// Semantic regions for hand-drawn controls, using the same bounds as real input.
package com.dark.winter.client.lab;

import java.util.List;

/** Semantic regions for hand-drawn controls, using the same bounds as real input. */
public interface LabUiProvider {
    record Region(String id, String label, String effect, int x, int y, int width, int height, boolean selected) { }
    default List<Region> darkwinter$labRegions() { return List.of(); }
    default java.util.Map<String,String> darkwinter$labState() { return java.util.Map.of(); }
}
