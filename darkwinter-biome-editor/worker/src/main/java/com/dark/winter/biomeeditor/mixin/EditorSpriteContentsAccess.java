// Exposes only reload-owned CPU sprite images for an immutable editor atlas copy.
package com.dark.winter.biomeeditor.mixin;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(SpriteContents.class)
public interface EditorSpriteContentsAccess {
    @Accessor("byMipLevel") NativeImage[] editor$mipImages();
}
