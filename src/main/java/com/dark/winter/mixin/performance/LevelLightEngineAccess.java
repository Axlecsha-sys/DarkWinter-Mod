// Exposes the two native light layers to the server batch coordinator.
package com.dark.winter.mixin.performance;

import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelLightEngine.class)
public interface LevelLightEngineAccess {
    @Accessor("blockEngine") LightEngine<?, ?> darkwinter$blockEngine();
    @Accessor("skyEngine") LightEngine<?, ?> darkwinter$skyEngine();
}
