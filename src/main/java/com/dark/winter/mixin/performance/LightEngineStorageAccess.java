// Identifies the exact sky storage whose publication belongs to an admitted light batch.
package com.dark.winter.mixin.performance;

import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightEngine.class)
public interface LightEngineStorageAccess {
    @Accessor("storage") LayerLightSectionStorage<?> darkwinter$storage();
}
