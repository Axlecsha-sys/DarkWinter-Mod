package com.winterkore.engine.material.mixin;

import com.winterkore.engine.material.WkeFoliageTextures;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publishes CPU snapshots together with the actual block atlas rectangle generation. */
@Mixin(TextureAtlas.class)
public abstract class WkeFoliageAtlasMixin {
    @Shadow @Final private Identifier location;
    @Inject(method="upload",at=@At("TAIL"))
    private void wke$publishBlockMapping(net.minecraft.client.renderer.texture.SpriteLoader.Preparations preparations,CallbackInfo ci) {
        if(TextureAtlas.LOCATION_BLOCKS.equals(location))
            WkeFoliageTextures.atlasUpdated((TextureAtlas)(Object)this);
    }
}
