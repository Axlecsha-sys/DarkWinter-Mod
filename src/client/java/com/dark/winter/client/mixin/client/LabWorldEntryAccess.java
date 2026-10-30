// Exposes native save summaries to laboratory world-selection inspection.
package com.dark.winter.client.mixin.client;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(WorldSelectionList.WorldListEntry.class)
public interface LabWorldEntryAccess {
    @Accessor("summary") LevelSummary darkwinter$summary();
}
