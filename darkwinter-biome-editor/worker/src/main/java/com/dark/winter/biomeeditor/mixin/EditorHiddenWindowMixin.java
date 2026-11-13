// Creates the exact preview worker's SDL Vulkan surface hidden from its first native allocation.
package com.dark.winter.biomeeditor.mixin;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
@Mixin(Window.class)
public abstract class EditorHiddenWindowMixin {
    @ModifyArg(method="createWindow",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"),index=3,require=1)
    private long editor$hidden(long flags){return flags|org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN;}
}
