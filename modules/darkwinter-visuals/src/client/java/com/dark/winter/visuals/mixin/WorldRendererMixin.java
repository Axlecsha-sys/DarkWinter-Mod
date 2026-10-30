// Schedules shadow capture and the visual postprocessing chain around native world rendering.
package com.dark.winter.visuals.mixin;

import com.dark.winter.visuals.VisualRenderer;
import com.dark.winter.visuals.SceneAccess;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.DynamicGpuData;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin implements SceneAccess {
    @Shadow @Final private GameRenderer gameRenderer;
    @Shadow @Final private ShaderManager shaderManager;
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow private ViewArea viewArea;
    @Shadow @Final private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
    @Shadow public abstract ChunkSectionsToRender prepareChunkRenders(Matrix4fc modelView,boolean sorted);
    @Unique private VisualRenderer darkwinter$visuals;
    @Unique private boolean darkwinter$selectCasters;
    @Unique private DynamicGpuData darkwinter$shadowGpuData;
    // Never depend on constructor field initializers being copied into the target class.
    @Unique private ObjectArrayList<SectionRenderDispatcher.RenderSection> darkwinter$casters;

    // IMPORTANT: perform shadow-preparation BEFORE the main terrain's GPU draw lists are built.
    // The old addMainPass(HEAD) hook ran too late and could invalidate the draw buffers
    // referenced by the vanilla main pass, leading to "Vertex buffer at slot 1 has been closed".
    // Only one of these two callsites is used for each frame.
    @Inject(method="render",at=@At(value="INVOKE",
        target="Lnet/minecraft/client/renderer/LevelRenderer;prepareChunkRendersIndirect(Lorg/joml/Matrix4fc;Z)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"),require=1)
    private void darkwinter$shadowBeforeIndirect(GraphicsResourceAllocator allocator,boolean outlines,
            CameraRenderState camera,GpuBufferSlice terrainFog,Vector4f fogColor,boolean sky,
            boolean consistentDepthRequired,CallbackInfo ci) {
        darkwinter$runShadowEarly(terrainFog);
    }

    @Inject(method="render",at=@At(value="INVOKE",
        target="Lnet/minecraft/client/renderer/LevelRenderer;prepareChunkRenders(Lorg/joml/Matrix4fc;Z)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"),require=1)
    private void darkwinter$shadowBeforeSeparate(GraphicsResourceAllocator allocator,boolean outlines,
            CameraRenderState camera,GpuBufferSlice terrainFog,Vector4f fogColor,boolean sky,
            boolean consistentDepthRequired,CallbackInfo ci) {
        darkwinter$runShadowEarly(terrainFog);
    }

    @Unique
    private void darkwinter$runShadowEarly(GpuBufferSlice terrainFog) {
        if(darkwinter$visuals==null) darkwinter$visuals=new VisualRenderer();
        // Capture compiled terrain only. Main-view entity handles are not borrowed.
        darkwinter$visuals.renderShadow(this,gameRenderer,levelRenderState,terrainFog);
    }
    @Inject(method="render",at=@At("RETURN"))
    private void darkwinter$resolve(GraphicsResourceAllocator allocator,boolean outlines,CameraRenderState camera,
             GpuBufferSlice fog,Vector4f fogColor,boolean sky,boolean consistentDepthRequired,CallbackInfo ci) {
        if(darkwinter$visuals!=null) darkwinter$visuals.resolve(gameRenderer,shaderManager,levelRenderState,allocator);
    }
    @Inject(method="close",at=@At("HEAD"))
    private void darkwinter$close(CallbackInfo ci) {
        if(darkwinter$visuals!=null) {darkwinter$visuals.close();darkwinter$visuals=null;}
        if(darkwinter$shadowGpuData!=null) {darkwinter$shadowGpuData.close();darkwinter$shadowGpuData=null;}
    }
    @Redirect(method="extractSectionDrawGroups",at=@At(value="FIELD",
        target="Lnet/minecraft/client/renderer/LevelRenderer;visibleSections:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",
        opcode=Opcodes.GETFIELD))
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> darkwinter$casterList(LevelRenderer owner) {
        return darkwinter$selectCasters?darkwinter$casters:visibleSections;
    }
    @Redirect(method="extractSectionDrawGroups",at=@At(value="INVOKE",
        target="Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;getVisibility(JJ)F"))
    private float darkwinter$shadowFade(
            SectionRenderDispatcher.RenderSection section,
            long now,
            long fadeDuration
    ) {
        // Shadow casters must remain opaque.
        if (darkwinter$selectCasters) return 1.0f;

        if (fadeDuration <= 0L
                || !com.dark.winter.visuals.VisualConfig.shared().enabled) {
            return section.getVisibility(now, fadeDuration);
        }

        long slower = fadeDuration <= Long.MAX_VALUE / 3L
                ? fadeDuration * 3L
                : fadeDuration;

        float visibility = Math.max(
                0.0f,
                Math.min(1.0f, section.getVisibility(now, slower))
        );

        return visibility * visibility * (3.0f - 2.0f * visibility);
    }
    @Override
    public ChunkSectionsToRender darkwinter$prepareShadowTerrain(Matrix4fc view,Matrix4f lightClip) {
        // Own dynamic terrain/section UBOs. Never switch vanilla's instance buffer to UBO usage.
        if (darkwinter$shadowGpuData == null) darkwinter$shadowGpuData = new DynamicGpuData();
        darkwinter$shadowGpuData.reset();
        if (darkwinter$casters == null) {
            darkwinter$casters = new ObjectArrayList<>();
        }
        darkwinter$casters.clear();
        if(viewArea!=null) {
            var pos=levelRenderState.cameraRenderState.pos;
            ((ViewAreaAccessor)(Object)viewArea).darkwinter$sections().forEach(section -> {
                if(section==null) return;
                BlockPos p=section.getRenderOrigin();
                float x=(float)(p.getX()+8-pos.x), y=(float)(p.getY()+8-pos.y), z=(float)(p.getZ()+8-pos.z);
                if(darkwinter$inLightVolume(lightClip,x,y,z)) darkwinter$casters.add(section);
            });
        }
        darkwinter$selectCasters=true;
        try { return prepareChunkRenders(view,false); }
        finally { darkwinter$selectCasters=false; }
    }
    @Redirect(method="prepareChunkRenders", at=@At(value="INVOKE",
        target="Lcom/mojang/blaze3d/systems/RenderSystem;getDynamicUniforms()Lnet/minecraft/client/renderer/DynamicGpuData;"), require=1)
    private DynamicGpuData darkwinter$isolatedShadowUniforms() {
        return darkwinter$selectCasters ? darkwinter$shadowGpuData : RenderSystem.getDynamicUniforms();
    }

    @Unique
    private static boolean darkwinter$inLightVolume(Matrix4f m,float x,float y,float z) {
        float cx=m.m00()*x+m.m10()*y+m.m20()*z+m.m30();
        float cy=m.m01()*x+m.m11()*y+m.m21()*z+m.m31();
        float cz=m.m02()*x+m.m12()*y+m.m22()*z+m.m32();
        float ex=8*(Math.abs(m.m00())+Math.abs(m.m10())+Math.abs(m.m20()));
        float ey=8*(Math.abs(m.m01())+Math.abs(m.m11())+Math.abs(m.m21()));
        float ez=8*(Math.abs(m.m02())+Math.abs(m.m12())+Math.abs(m.m22()));
        return cx+ex>=-1 && cx-ex<=1 && cy+ey>=-1 && cy-ey<=1 && cz+ez>=0 && cz-ez<=1;
    }
}
