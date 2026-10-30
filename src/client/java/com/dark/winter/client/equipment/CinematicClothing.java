// Assembles wearable geometry for the authored cinematic clothing catalog.
package com.dark.winter.client.equipment;

import com.dark.winter.equipment.GearSlot;
import java.util.List;
import net.minecraft.resources.Identifier;

/** Actual wearable geometry for the five approved catalogue sheets. */
final class CinematicClothing {
    static List<ClothingMeshes.Piece> pieces(GearSlot slot,int design) {
        return switch(slot) {
            case JACKET -> CinematicJackets.pieces(design);
            case PANTS -> CinematicTrousers.pieces(design);
            case GLOVES -> CinematicGloves.pieces(design);
            case FOOTWEAR -> CinematicBoots.pieces(design);
            case HAT -> CinematicHats.pieces(design);
            default -> throw new IllegalArgumentException("No catalogue for "+slot);
        };
    }
    static Identifier texture(GearSlot slot,int design,int color) {
        return switch(slot) {
            case JACKET -> CinematicJackets.texture(design,color);
            case PANTS -> CinematicTrousers.texture(design,color);
            case GLOVES -> CinematicGloves.texture(design,color);
            case FOOTWEAR -> CinematicBoots.texture(design,color);
            case HAT -> CinematicHats.texture(design,color);
            default -> throw new IllegalArgumentException("No catalogue for "+slot);
        };
    }
    private CinematicClothing() { }
}
