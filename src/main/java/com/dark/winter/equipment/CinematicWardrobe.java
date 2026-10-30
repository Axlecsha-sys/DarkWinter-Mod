// Fixed catalogue order matches the user's approved numbered sheets, without changing legacy IDs.
package com.dark.winter.equipment;

import java.util.List;

/** Fixed catalogue order matches the user's approved numbered sheets, without changing legacy IDs. */
public final class CinematicWardrobe {
    public record Design(String id, GearSlot slot, int number) {
        public int variant() { return (slot == GearSlot.JACKET ? 7 : 5) + number; }
    }
    public static final List<Design> DESIGNS = List.of(
            new Design("cinematic_expedition_parka", GearSlot.JACKET, 1),
            new Design("cinematic_quilted_field_jacket", GearSlot.JACKET, 2),
            new Design("cinematic_fur_collar_cold_weather_jacket", GearSlot.JACKET, 3),
            new Design("cinematic_military_winter_coat", GearSlot.JACKET, 4),
            new Design("cinematic_workwear_insulated_jacket", GearSlot.JACKET, 5),
            new Design("cinematic_long_anorak", GearSlot.JACKET, 6),
            new Design("cinematic_padded_bomber", GearSlot.JACKET, 7),
            new Design("cinematic_survival_shell_jacket", GearSlot.JACKET, 8),
            new Design("cinematic_cargo_winter_trousers", GearSlot.PANTS, 1),
            new Design("cinematic_insulated_field_pants", GearSlot.PANTS, 2),
            new Design("cinematic_padded_snow_pants", GearSlot.PANTS, 3),
            new Design("cinematic_technical_shell_pants", GearSlot.PANTS, 4),
            new Design("cinematic_reinforced_work_pants", GearSlot.PANTS, 5),
            new Design("cinematic_bib_overalls", GearSlot.PANTS, 6),
            new Design("cinematic_quilted_expedition_trousers", GearSlot.PANTS, 7),
            new Design("cinematic_rugged_scout_pants", GearSlot.PANTS, 8),
            new Design("cinematic_knit_liner_gloves", GearSlot.GLOVES, 1),
            new Design("cinematic_leather_work_gloves", GearSlot.GLOVES, 2),
            new Design("cinematic_insulated_winter_gloves", GearSlot.GLOVES, 3),
            new Design("cinematic_heavy_mittens", GearSlot.GLOVES, 4),
            new Design("cinematic_trigger_mitts", GearSlot.GLOVES, 5),
            new Design("cinematic_tactical_cold_weather_gloves", GearSlot.GLOVES, 6),
            new Design("cinematic_canvas_gauntlet_gloves", GearSlot.GLOVES, 7),
            new Design("cinematic_waterproof_shell_gloves", GearSlot.GLOVES, 8),
            new Design("cinematic_expedition_snow_boots", GearSlot.FOOTWEAR, 1),
            new Design("cinematic_lace_up_work_boots", GearSlot.FOOTWEAR, 2),
            new Design("cinematic_insulated_rubber_winter_boots", GearSlot.FOOTWEAR, 3),
            new Design("cinematic_tactical_winter_boots", GearSlot.FOOTWEAR, 4),
            new Design("cinematic_heavy_mountaineering_boots", GearSlot.FOOTWEAR, 5),
            new Design("cinematic_felt_lined_field_boots", GearSlot.FOOTWEAR, 6),
            new Design("cinematic_reinforced_survival_boots", GearSlot.FOOTWEAR, 7),
            new Design("cinematic_fur_lined_cold_weather_boots", GearSlot.FOOTWEAR, 8),
            new Design("cinematic_ribbed_beanie", GearSlot.HAT, 1),
            new Design("cinematic_fold_over_watch_cap", GearSlot.HAT, 2),
            new Design("cinematic_ushanka", GearSlot.HAT, 3),
            new Design("cinematic_trapper_hat", GearSlot.HAT, 4),
            new Design("cinematic_thick_knit_ear_cap", GearSlot.HAT, 5),
            new Design("cinematic_balaclava_and_cap", GearSlot.HAT, 6),
            new Design("cinematic_fur_lined_storm_cap", GearSlot.HAT, 7),
            new Design("cinematic_low_profile_winter_field_cap", GearSlot.HAT, 8)
    );
    public static int design(GearSlot slot, int variant) {
        int number=variant-(slot==GearSlot.JACKET?7:5);
        return switch(slot) {
            case JACKET,PANTS,GLOVES,FOOTWEAR,HAT -> number>=1&&number<=8?number:0;
            default -> 0;
        };
    }
    private CinematicWardrobe() { }
}
