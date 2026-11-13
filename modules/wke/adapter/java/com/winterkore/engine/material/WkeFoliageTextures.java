// Dedicated full mip chains for the two authored foliage materials, using ordinary host 2D textures.
package com.winterkore.engine.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.WeakHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Uploads only at startup/tick/reload boundaries, never from an active render pass. */
public final class WkeFoliageTextures {
    public static final String NEEDLES_SAMPLER="WkeNeedlesSampler",BRANCH_SAMPLER="WkeBranchSampler",UNIFORM="WkeFoliage";
    private static final String[] MATERIALS={WkeMaterialMips.NEEDLES,WkeMaterialMips.BRANCH};
    private static final int[] DIMENSIONS={512,64},LEVELS={10,7};
    private static final Logger LOG=LoggerFactory.getLogger("WinterKore/FoliageTextures");
    private static final GpuTexture[] textures=new GpuTexture[2];
    private static final GpuTextureView[] views=new GpuTextureView[2];
    private static final GpuSampler[] samplers=new GpuSampler[2];
    private static final TextureViewAndSampler[] boundTextures=new TextureViewAndSampler[2];
    private static final boolean[] materialReady=new boolean[2];
    private static final float[] rects=new float[8];
    // Pipelines may be evicted or replaced without an atlas reload; this cache owns no GPU lifetime.
    private static final WeakHashMap<VulkanRenderPipeline,int[]> bindingIndices=new WeakHashMap<>();
    private static GpuDevice device;
    private static GpuBuffer uniform;
    private static GpuBufferSlice uniformSlice;
    private static TextureAtlas atlas;
    private static GpuTexture atlasTexture;
    private static ByteBuffer uniformBytes;
    private static long uploadedRevision=-1,uploads,uploadedBytes,bindings,recreations;
    private static long mainBindings,shadowBindings,mainNativeDispatches,shadowNativeDispatches;
    private static final ThreadLocal<int[]> ACTIVE_MATERIAL=ThreadLocal.withInitial(()->new int[1]);
    private static int requestedMip=-1,requestedAnisotropy=-1;
    private static TextureFilteringMethod requestedFiltering;
    private static boolean initialized,enabled=true,dirty=true,uploadedEnabled,gpuEnabled;
    private static Map<String,WkeMaterialMips.Snapshot> publishedSnapshots=Map.of();
    private static long publishedRevision=-1;
    private static String reason="not initialized";
    private WkeFoliageTextures() { }

    public static void initialize() {
        if(initialized)return;
        initialized=true;
        ClientLifecycleEvents.CLIENT_STARTED.register(client->prepare());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->close());
        ClientTickEvents.END_CLIENT_TICK.register(client->{ if(client.level!=null)prepare(); });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->close());
    }

    /** Called by the real block atlas upload after its sprite rectangles and texture are installed. */
    public static void atlasUpdated(TextureAtlas value) {
        atlas=value;publishedSnapshots=WkeMaterialMips.snapshots();publishedRevision=WkeMaterialMips.revision();dirty=true;
        // TextureAtlas.upload has closed all of its passes before its TAIL publication.
        prepare();
    }

    public static boolean descriptorsReady() { return uniform!=null&&views[0]!=null&&views[1]!=null; }

    private static void prepare() {
        RenderSystem.assertOnRenderThread();
        GpuDevice current=RenderSystem.getDevice();
        if(!"Vulkan".equalsIgnoreCase(current.getDeviceInfo().backendName()))return;
        try {
            if(device!=current||!descriptorsReady()) { close();device=current;createResources();dirty=true; }
            long revision=publishedRevision;
            if(revision!=uploadedRevision)uploadMaterials(revision);
            GpuTexture currentAtlas=atlas==null?null:atlas.getTexture();
            if(currentAtlas!=atlasTexture) { atlasTexture=currentAtlas;bindingIndices.clear();dirty=true; }
            var options=Minecraft.getInstance().options;
            int mip=Math.clamp(options.mipmapLevels().get(),0,4);
            TextureFilteringMethod filtering=options.textureFiltering().get();
            int anisotropy=filtering==TextureFilteringMethod.ANISOTROPIC?options.maxAnisotropyValue():1;
            if(mip!=requestedMip||filtering!=requestedFiltering||anisotropy!=requestedAnisotropy) {
                replaceSamplers(mip,anisotropy);requestedMip=mip;requestedFiltering=filtering;
                requestedAnisotropy=anisotropy;dirty=true;
            }
            if(enabled!=uploadedEnabled)dirty=true;
            if(dirty)writeUniform();
            reason=materialReady[0]&&materialReady[1]&&atlasTexture!=null?"":"waiting for authored material snapshots/atlas rectangles";
        } catch(RuntimeException|LinkageError failure) {
            reason=failure.toString();LOG.error("WKE foliage GPU preparation failed",failure);
            close();
        }
    }

    private static void createResources() {
        CommandEncoder encoder=device.createCommandEncoder();
        ByteBuffer zeros=MemoryUtil.memCalloc(DIMENSIONS[0]*DIMENSIONS[0]*4);
        try {
            for(int i=0;i<2;i++) {
                textures[i]=device.createTexture("wke/foliage/"+i,
                        GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_TEXTURE_BINDING,
                        GpuFormat.RGBA8_UNORM,DIMENSIONS[i],DIMENSIONS[i],1,LEVELS[i]);
                views[i]=device.createTextureView(textures[i]);
                for(int level=0;level<LEVELS[i];level++) {
                    int size=Math.max(1,DIMENSIONS[i]>>level);
                    zeros.clear().limit(size*size*4);
                    encoder.writeToTexture(textures[i],zeros,level,0,0,0,size,size);
                }
            }
        }finally { MemoryUtil.memFree(zeros); }
        uniform=device.createBuffer(()->"WKE foliage UV mapping",
                GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,48);
        uniformSlice=uniform.slice();
        uniformBytes=MemoryUtil.memAlloc(48).order(ByteOrder.nativeOrder());
        recreations++;
    }

    private static void uploadMaterials(long revision) {
        Map<String,WkeMaterialMips.Snapshot> snapshots=publishedSnapshots;
        CommandEncoder encoder=device.createCommandEncoder();
        for(int i=0;i<2;i++) {
            WkeMaterialMips.Snapshot snapshot=snapshots.get(MATERIALS[i]);
            materialReady[i]=snapshot!=null&&snapshot.width()==DIMENSIONS[i]
                    &&snapshot.height()==DIMENSIONS[i]&&snapshot.levels()==LEVELS[i];
            if(!materialReady[i])continue;
            int[] words=snapshot.rgbaWords();
            ByteBuffer pixels=MemoryUtil.memAlloc(words.length*4).order(ByteOrder.nativeOrder());
            try {
                pixels.asIntBuffer().put(words);
                for(int level=0;level<snapshot.levels();level++) {
                    int size=Math.max(1,DIMENSIONS[i]>>level);
                    pixels.position(snapshot.offset(level)*4).limit(snapshot.offset(level)*4+size*size*4);
                    encoder.writeToTexture(textures[i],pixels,level,0,0,0,size,size);
                    pixels.clear();uploads++;uploadedBytes+=(long)size*size*4;
                }
            }finally { MemoryUtil.memFree(pixels); }
        }
        uploadedRevision=revision;dirty=true;
    }

    private static void replaceSamplers(int mip,int anisotropy) {
        for(int i=0;i<2;i++) {
            if(samplers[i]!=null)samplers[i].close();
            double maxLod=mip==0?0:(LEVELS[i]-1)*mip/4.0;
            samplers[i]=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR,FilterMode.LINEAR,anisotropy,OptionalDouble.of(maxLod));
            boundTextures[i]=new TextureViewAndSampler(views[i],samplers[i]);
        }
    }

    private static void writeUniform() {
        boolean complete=materialReady[0]&&materialReady[1]&&atlas!=null&&atlasTexture!=null&&!atlasTexture.isClosed();
        for(int i=0;i<2;i++) {
            if(!complete)break;
            var sprite=atlas.getSprite(Identifier.parse(MATERIALS[i]));
            if(!sprite.contents().name().toString().equals(MATERIALS[i])) { complete=false;break; }
            rects[i*4]=sprite.getU0();rects[i*4+1]=sprite.getV0();
            rects[i*4+2]=sprite.getU1();rects[i*4+3]=sprite.getV1();
        }
        uniformBytes.clear();
        for(float value:rects)uniformBytes.putFloat(value);
        gpuEnabled=enabled&&complete;
        uniformBytes.putFloat(gpuEnabled?1:0).putFloat(requestedMip).putFloat(2).putFloat(9).flip();
        device.createCommandEncoder().writeToBuffer(uniformSlice,uniformBytes);
        uploadedEnabled=enabled;dirty=false;
    }

    /** Metadata names are emitted only for full WKE terrain shader pairs; custom shadows retain host materials. */
    public static void bind(VulkanRenderPass pass,VulkanRenderPipeline pipeline) {
        ACTIVE_MATERIAL.get()[0]=WkeFoliagePipeline.NONE;
        int[] indices=bindingIndices.get(pipeline);
        if(indices==null) {
            indices=new int[]{-1,-1,-1};
            var uniforms=pipeline.uniforms();
            for(int i=0;i<uniforms.size();i++) {
                String name=uniforms.get(i).name();
                if(NEEDLES_SAMPLER.equals(name))indices[0]=i;
                else if(BRANCH_SAMPLER.equals(name))indices[1]=i;
                else if(UNIFORM.equals(name))indices[2]=i;
            }
            bindingIndices.put(pipeline,indices);
        }
        if(indices[2]<0)return;
        if(indices[0]<0||indices[1]<0||!descriptorsReady())
            throw new IllegalStateException("Incomplete WKE foliage descriptor state");
        pass.setUniform(indices[0],boundTextures[0]);pass.setUniform(indices[1],boundTextures[1]);
        pass.setUniform(indices[2],uniformSlice);bindings++;
        int kind=((WkeFoliagePipeline)(Object)pipeline).wke$materialKind();
        ACTIVE_MATERIAL.get()[0]=kind;
        if(kind==WkeFoliagePipeline.MAIN)mainBindings++;
        else if(kind==WkeFoliagePipeline.SHADOW)shadowBindings++;
    }

    /** Called only after the bridge accepted a real native world draw dispatch. */
    public static void onNativeDispatchAccepted() {
        int kind=ACTIVE_MATERIAL.get()[0];
        if(kind==WkeFoliagePipeline.MAIN)mainNativeDispatches++;
        else if(kind==WkeFoliagePipeline.SHADOW)shadowNativeDispatches++;
    }
    /** Allocation-free observation of already recorded material state; never reads the GPU or changes a binding. */
    public static void copyFrameCounters(long[] target,int offset) {
        target[offset]=uploadedRevision;target[offset+1]=mainBindings;target[offset+2]=shadowBindings;
        target[offset+3]=mainNativeDispatches;target[offset+4]=shadowNativeDispatches;
    }
    public static void setEnabledForTesting(boolean value) { enabled=value;dirty=true; }
    public static GpuTexture textureForTesting(int material) { return textures[material]; }
    public static GpuSampler samplerForTesting(int material) { return samplers[material]; }
    public static Map<String,Object> statusSnapshot() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("enabled",enabled);result.put("gpuEnabled",gpuEnabled);
        result.put("ready",descriptorsReady());result.put("reason",reason);result.put("revision",uploadedRevision);
        result.put("needleMipLevels",textures[0]==null?0:textures[0].getMipLevels());
        result.put("branchMipLevels",textures[1]==null?0:textures[1].getMipLevels());
        result.put("requestedGlobalMip",requestedMip);result.put("anisotropy",requestedAnisotropy);
        result.put("textureFiltering",String.valueOf(requestedFiltering));result.put("uploads",uploads);result.put("uploadedBytes",uploadedBytes);
        result.put("bindings",bindings);result.put("mainBindings",mainBindings);result.put("shadowBindings",shadowBindings);
        result.put("mainNativeDispatches",mainNativeDispatches);result.put("shadowNativeDispatches",shadowNativeDispatches);result.put("recreations",recreations);result.put("rectangles",rects.clone());
        return result;
    }

    /** Host GPU close() defers destruction until in-flight commands finish. */
    public static void close() {
        ACTIVE_MATERIAL.get()[0]=WkeFoliagePipeline.NONE;
        bindingIndices.clear();
        if(uniform!=null)uniform.close();
        uniform=null;uniformSlice=null;
        if(uniformBytes!=null)MemoryUtil.memFree(uniformBytes);
        uniformBytes=null;
        for(int i=0;i<2;i++) {
            if(views[i]!=null)views[i].close();if(samplers[i]!=null)samplers[i].close();
            if(textures[i]!=null)textures[i].close();
            views[i]=null;samplers[i]=null;textures[i]=null;boundTextures[i]=null;materialReady[i]=false;
        }
        device=null;atlasTexture=null;uploadedRevision=-1;requestedMip=-1;requestedAnisotropy=-1;requestedFiltering=null;dirty=true;uploadedEnabled=false;gpuEnabled=false;
    }
}
