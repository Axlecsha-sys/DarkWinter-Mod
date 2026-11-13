package com.dark.winter.client.mixin.client;

import com.dark.winter.client.text.SmoothUiText;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.util.function.Supplier;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Transparent gutters are required for filtering; newly allocated texture memory is not initialized. */
@Mixin(FontTexture.class)
public abstract class SmoothFontAtlasMixin extends AbstractTexture {
    @Shadow @Final private boolean colored;

    @Inject(method="<init>",at=@At("TAIL"),require=1,allow=1)
    private void darkwinter$prepareCoverageAtlas(Supplier<String> label,GlyphRenderTypes renderTypes,boolean colored,CallbackInfo ci) {
        if(colored)return;
        sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        var texture=getTexture();
        try(var clear=new NativeImage(NativeImage.Format.LUMINANCE,texture.getWidth(0),texture.getHeight(0),true)) {
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture,clear,0,0,0,0);
        }
    }

    @ModifyArg(method="add",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/texture/DynamicAtlasTree;insert(III)Lnet/minecraft/client/renderer/texture/DynamicAtlasTreeSlot;"),index=2,require=1,allow=1)
    private int darkwinter$coverageGutter(int original) {
        return colored?original:SmoothUiText.ATLAS_GUTTER;
    }
}
