package com.dark.winter.export.mixin;
import com.dark.winter.export.ExportAtlas;
import net.minecraft.client.renderer.texture.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(TextureAtlas.class)
public abstract class AtlasCapture {
 @Inject(method="upload",at=@At("HEAD"),require=1)
 private void world$capture(SpriteLoader.Preparations p,CallbackInfo ci){ExportAtlas.capture((TextureAtlas)(Object)this,p);}
}
