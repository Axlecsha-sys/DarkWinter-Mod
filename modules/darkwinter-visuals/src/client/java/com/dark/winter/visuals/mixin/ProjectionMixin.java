// Captures the exact camera projection without modifying the Minecraft camera.
package com.dark.winter.visuals.mixin;

import com.dark.winter.visuals.SceneFrame;
import com.dark.winter.visuals.VisualRenderer;
import com.dark.winter.visuals.ProjectionAccess;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import java.util.List;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the exact camera projection without modifying the Minecraft camera. */
@Mixin(GameRenderer.class)
public abstract class ProjectionMixin implements ProjectionAccess {

    // Mixin field initializers are not assumed to run in the target class constructor.
    // The Matrix4f is created at the first actual capture instead.
    @Unique
    private Matrix4f darkwinter$actualProjection;

    @ModifyArg(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
        ),
        index = 0
    )
    private Matrix4f darkwinter$captureProjection(Matrix4f original) {
        if (this.darkwinter$actualProjection == null) {
            this.darkwinter$actualProjection = new Matrix4f();
        }
        this.darkwinter$actualProjection.set(original);
        return original;
    }

    @Override
    public Matrix4f darkwinter$renderProjection() {
        // Do not silently use an identity matrix: that would produce invalid shadows.
        if (this.darkwinter$actualProjection == null) {
            throw new IllegalStateException(
                "DarkWinter Visuals: camera projection was requested before capture"
            );
        }
        return this.darkwinter$actualProjection;
    }

    @ModifyArg(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"
        ),
        index = 6
    )
    private boolean darkwinter$preserveWorldDepth(boolean original) {
        return original || VisualRenderer.needsStableDepth();
    }

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void darkwinter$resourceReload(CallbackInfo ci) {
        SceneFrame.resourceEpoch++;
    }

    // Avoid static @Unique field initializers for another Mixin initialization pitfall.
    @Unique
    private static boolean darkwinter$isRetiredEffect(Identifier id) {
        String key = id.toString();
        return "darkwinter:atmosphere".equals(key)
            || "darkwinter:northern_light".equals(key)
            || "darkwinter:analog_preview".equals(key);
    }

    @ModifyVariable(method = "preparePostEffects", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private List<Identifier> darkwinter$ignoreRetiredEffects(List<Identifier> effects) {
        return effects.stream()
            .filter(id -> !darkwinter$isRetiredEffect(id))
            .toList();
    }
}
