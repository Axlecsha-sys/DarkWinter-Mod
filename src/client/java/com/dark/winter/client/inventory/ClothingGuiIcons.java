package com.dark.winter.client.inventory;

import com.dark.winter.equipment.GearItem;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Illustrated equipment art for Tab, keyed by item ID. */
public final class ClothingGuiIcons {
    private static final int ATLAS_SIZE = 1280;
    private static final Identifier[] ATLASES = {
            atlas(0), atlas(1), atlas(2), atlas(3), atlas(4), atlas(5)
    };
    private static final Map<String, Icon> ICONS = loadIcons();

    private ClothingGuiIcons() {}

    public static boolean extract(GuiGraphicsExtractor graphics, ItemStack stack,
                                  int x, int y, int width, int height, boolean zoom) {
        if (!(stack.getItem() instanceof GearItem) || width <= 0 || height <= 0) return false;
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!id.getNamespace().equals("darkwinter")) return false;
        Icon icon = ICONS.get(id.getPath());
        if (icon == null) return false;

        float fit = Math.min(width / (float)icon.width(), height / (float)icon.height());
        float scale = fit * (zoom ? .98f : .88f);
        int drawWidth = Math.max(1, Math.round(icon.width() * scale));
        int drawHeight = Math.max(1, Math.round(icon.height() * scale));
        graphics.blit(RenderPipelines.GUI_TEXTURED, icon.atlas(),
                x + (width - drawWidth) / 2, y + (height - drawHeight) / 2,
                icon.u(), icon.v(), drawWidth, drawHeight, icon.width(), icon.height(),
                ATLAS_SIZE, ATLAS_SIZE, -1);
        return true;
    }

    private static Identifier atlas(int index) {
        return Identifier.fromNamespaceAndPath("darkwinter", "textures/gui/clothing/wardrobe_" + index + ".png");
    }

    private record Icon(Identifier atlas, int u, int v, int width, int height) {}

    private static void add(Map<String, Icon> icons, String id, int atlas, int u, int v, int width, int height) {
        icons.put(id, new Icon(ATLASES[atlas], u, v, width, height));
    }

    private static Map<String, Icon> loadIcons() {
        Map<String, Icon> icons = new HashMap<>();
        add(icons, "jacket", 0, 3, 3, 267, 267);
        add(icons, "winter_parka", 0, 323, 3, 243, 299);
        add(icons, "red_expedition_parka", 0, 643, 3, 253, 301);
        add(icons, "navy_down_parka", 0, 963, 3, 260, 289);
        add(icons, "ochre_anorak", 0, 3, 323, 265, 287);
        add(icons, "quilted_work_jacket", 0, 323, 323, 259, 266);
        add(icons, "cinematic_northern_wanderer", 0, 643, 323, 250, 274);
        add(icons, "cinematic_expedition_parka", 0, 963, 323, 250, 298);
        add(icons, "cinematic_quilted_field_jacket", 0, 3, 643, 259, 272);
        add(icons, "cinematic_fur_collar_cold_weather_jacket", 0, 323, 643, 268, 284);
        add(icons, "cinematic_military_winter_coat", 0, 643, 643, 201, 305);
        add(icons, "cinematic_workwear_insulated_jacket", 0, 963, 643, 265, 278);
        add(icons, "cinematic_long_anorak", 0, 3, 963, 227, 302);
        add(icons, "cinematic_padded_bomber", 0, 323, 963, 265, 270);
        add(icons, "cinematic_survival_shell_jacket", 0, 643, 963, 251, 297);
        add(icons, "work_pants", 1, 3, 3, 146, 278);
        add(icons, "winter_pants", 1, 323, 3, 157, 282);
        add(icons, "olive_cargo_pants", 1, 643, 3, 163, 281);
        add(icons, "navy_thermal_pants", 1, 963, 3, 135, 278);
        add(icons, "cinematic_northern_wanderer_pants", 1, 3, 323, 137, 280);
        add(icons, "cinematic_cargo_winter_trousers", 1, 323, 323, 181, 282);
        add(icons, "cinematic_insulated_field_pants", 1, 643, 323, 159, 280);
        add(icons, "cinematic_padded_snow_pants", 1, 963, 323, 171, 279);
        add(icons, "cinematic_technical_shell_pants", 1, 3, 643, 150, 281);
        add(icons, "cinematic_reinforced_work_pants", 1, 323, 643, 155, 279);
        add(icons, "cinematic_bib_overalls", 1, 643, 643, 133, 284);
        add(icons, "cinematic_quilted_expedition_trousers", 1, 963, 643, 157, 279);
        add(icons, "cinematic_rugged_scout_pants", 1, 3, 963, 144, 278);
        add(icons, "wool_gloves", 2, 3, 3, 261, 290);
        add(icons, "winter_mittens", 2, 323, 3, 243, 284);
        add(icons, "leather_work_gloves", 2, 643, 3, 266, 285);
        add(icons, "fleece_gloves", 2, 963, 3, 267, 286);
        add(icons, "cinematic_northern_wanderer_gloves", 2, 3, 323, 265, 285);
        add(icons, "cinematic_knit_liner_gloves", 2, 323, 323, 264, 291);
        add(icons, "cinematic_leather_work_gloves", 2, 643, 323, 270, 286);
        add(icons, "cinematic_insulated_winter_gloves", 2, 963, 323, 272, 293);
        add(icons, "cinematic_heavy_mittens", 2, 3, 643, 260, 294);
        add(icons, "cinematic_trigger_mitts", 2, 323, 643, 258, 294);
        add(icons, "cinematic_tactical_cold_weather_gloves", 2, 643, 643, 270, 294);
        add(icons, "cinematic_canvas_gauntlet_gloves", 2, 963, 643, 261, 293);
        add(icons, "cinematic_waterproof_shell_gloves", 2, 3, 963, 260, 291);
        add(icons, "knit_cap", 3, 3, 3, 251, 208);
        add(icons, "fur_hat", 3, 323, 3, 256, 249);
        add(icons, "red_watch_cap", 3, 643, 3, 268, 231);
        add(icons, "navy_earflap_hat", 3, 963, 3, 242, 271);
        add(icons, "cinematic_northern_wanderer_hat", 3, 3, 323, 255, 243);
        add(icons, "cinematic_ribbed_beanie", 3, 323, 323, 260, 239);
        add(icons, "cinematic_fold_over_watch_cap", 3, 643, 323, 275, 260);
        add(icons, "cinematic_ushanka", 3, 963, 323, 298, 288);
        add(icons, "cinematic_trapper_hat", 3, 3, 643, 244, 301);
        add(icons, "cinematic_thick_knit_ear_cap", 3, 323, 643, 247, 265);
        add(icons, "cinematic_balaclava_and_cap", 3, 643, 643, 204, 292);
        add(icons, "cinematic_fur_lined_storm_cap", 3, 963, 643, 271, 226);
        add(icons, "cinematic_low_profile_winter_field_cap", 3, 3, 963, 275, 167);
        add(icons, "work_boots", 4, 3, 3, 282, 238);
        add(icons, "winter_boots", 4, 323, 3, 268, 270);
        add(icons, "insulated_work_boots", 4, 643, 3, 273, 278);
        add(icons, "leather_hiking_boots", 4, 963, 3, 289, 246);
        add(icons, "cinematic_northern_wanderer_boots", 4, 3, 323, 273, 293);
        add(icons, "cinematic_expedition_snow_boots", 4, 323, 323, 279, 287);
        add(icons, "cinematic_lace_up_work_boots", 4, 643, 323, 288, 263);
        add(icons, "cinematic_insulated_rubber_winter_boots", 4, 963, 323, 277, 287);
        add(icons, "cinematic_tactical_winter_boots", 4, 3, 643, 274, 297);
        add(icons, "cinematic_heavy_mountaineering_boots", 4, 323, 643, 287, 291);
        add(icons, "cinematic_felt_lined_field_boots", 4, 643, 643, 280, 288);
        add(icons, "cinematic_reinforced_survival_boots", 4, 963, 643, 285, 284);
        add(icons, "cinematic_fur_lined_cold_weather_boots", 4, 3, 963, 276, 291);
        add(icons, "wool_socks", 5, 3, 3, 241, 265);
        add(icons, "insulated_socks", 5, 323, 3, 233, 265);
        add(icons, "thick_hiking_socks", 5, 643, 3, 232, 266);
        add(icons, "hiking_backpack", 5, 963, 3, 232, 290);
        add(icons, "cinematic_northern_wanderer_backpack", 5, 3, 323, 227, 287);
        add(icons, "cotton_tshirt", 5, 323, 323, 271, 275);
        add(icons, "navy_tshirt", 5, 643, 323, 274, 275);
        add(icons, "red_tshirt", 5, 963, 323, 272, 271);
        add(icons, "olive_wool_sweater", 5, 3, 643, 276, 282);
        add(icons, "navy_wool_sweater", 5, 323, 643, 282, 280);
        add(icons, "ochre_fleece", 5, 643, 643, 287, 284);
        add(icons, "red_flannel_shirt", 5, 963, 643, 282, 289);
        return Map.copyOf(icons);
    }
}
