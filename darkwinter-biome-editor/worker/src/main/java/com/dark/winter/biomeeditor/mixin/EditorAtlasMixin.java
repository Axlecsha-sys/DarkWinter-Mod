// Captures native atlas inputs once per completed resource reload without GPU readback.
package com.dark.winter.biomeeditor.mixin;
import com.dark.winter.biomeeditor.EditorSceneAtlas;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(TextureAtlas.class)
public abstract class EditorAtlasMixin {
    @Inject(method="upload",at=@At("HEAD"),require=1)
    private void editor$copyNativeAtlas(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        EditorSceneAtlas.capture((TextureAtlas)(Object)this, preparations);
    }
}
