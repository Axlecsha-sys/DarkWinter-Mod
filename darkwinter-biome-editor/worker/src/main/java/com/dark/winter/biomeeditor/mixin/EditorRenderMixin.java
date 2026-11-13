// Publishes only completed native preview frames and declares their orthographic projection.
package com.dark.winter.biomeeditor.mixin;
import com.dark.winter.biomeeditor.BiomeEditorWorker;
import com.mojang.blaze3d.ProjectionType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(GameRenderer.class)
public abstract class EditorRenderMixin {
    @Inject(method="extract",at=@At("HEAD"),require=1)
    private void editor$cameraBeforeExtract(CallbackInfo ci){if(BiomeEditorWorker.INSTANCE!=null)BiomeEditorWorker.INSTANCE.beforeExtract(Minecraft.getInstance());}
    @Redirect(method="extract",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/extract/LevelExtractor;extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V"),require=1)
    private void editor$localScene(net.minecraft.client.renderer.extract.LevelExtractor extractor,net.minecraft.client.DeltaTracker delta,net.minecraft.client.Camera camera,float partial){
        if(!BiomeEditorWorker.retainedActive())extractor.extract(delta,camera,partial);
    }
    @Inject(method="renderLevel",at=@At("HEAD"),cancellable=true,require=1)
    private void editor$skipDuplicateWorld(CallbackInfo ci){if(BiomeEditorWorker.retainedActive())ci.cancel();}
    @Inject(method="render",at=@At("TAIL"),require=1)
    private void editor$frame(CallbackInfo ci){if(BiomeEditorWorker.INSTANCE!=null)BiomeEditorWorker.INSTANCE.afterFrame(Minecraft.getInstance());}
    @ModifyArg(method="renderLevel",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V"),index=1,require=1)
    private ProjectionType editor$projection(ProjectionType original){return BiomeEditorWorker.orbitActive()?ProjectionType.ORTHOGRAPHIC:original;}
}
