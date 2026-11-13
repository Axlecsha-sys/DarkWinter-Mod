// Bounds only the isolated preview server's streamed and simulated chunk rings.
package com.dark.winter.biomeeditor.mixin;
import com.dark.winter.biomeeditor.BiomeEditorWorker;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(IntegratedServer.class)
public abstract class EditorServerWorkloadMixin {
    @ModifyExpressionValue(method="tickServer",at=@At(value="INVOKE",target="Ljava/lang/Math;max(II)I",ordinal=0),require=1)
    private int editor$streamingRing(int original){return BiomeEditorWorker.active()?BiomeEditorWorker.serverViewDistance():original;}
    @ModifyExpressionValue(method="tickServer",at=@At(value="INVOKE",target="Ljava/lang/Math;max(II)I",ordinal=1),require=1)
    private int editor$simulationRing(int original){return BiomeEditorWorker.active()?2:original;}
}
