// R8 particle-reactive coverage on the existing graphics host. No color copy, MRT or generic color-change detector.
package com.winterkore.engine.render.effects;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.winterkore.engine.graphics.WkeReactiveRects;
import com.winterkore.engine.render.WkeParticleReactiveRects;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public final class WkeParticleReactiveMask implements AutoCloseable {
    public static final Identifier CURRENT=id("temporal_reactive");
    public static final Identifier HISTORY=id("temporal_reactive_history");
    private static final VertexFormat INSTANCES=VertexFormat.builder(1).addAttribute("ClipBounds",GpuFormat.RGBA32_FLOAT).build();
    private static final RenderPipeline PIPELINE=RenderPipelines.register(RenderPipeline.builder()
        .withLocation(id("pipeline/temporal_reactive")).withVertexShader(id("core/reactive_mask")).withFragmentShader(id("core/reactive_mask"))
        .withVertexBinding(0,DefaultVertexFormat.POSITION).withVertexBinding(1,INSTANCES)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
        .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.R8_UNORM,ColorTargetState.WRITE_RED)).build());
    private final WkeReactiveRects rectangles=new WkeReactiveRects();
    private final Matrix4f currentViewProjection=new Matrix4f();
    private TextureTarget current,history;
    private RenderTarget lastRendered,lastUsedHistory;
    private GpuBuffer corners;
    private MappableRingBuffer instances;
    private int resourceEpoch=Integer.MIN_VALUE,sourceCount,drawnCount;
    private long sourceFrame=-1,lastSourceFrame=-1,preparedFrames,captures,historyClears,submittedDraws,resets;
    private boolean clearHistory=true,prepared,sourceValid,requested;
    private String reason="initial";
    private static Identifier id(String name){return Identifier.fromNamespaceAndPath("wke",name);}

    /** Uploads before opening its pass; source bounds are the actual current frame's prepared particle quads. */
    public boolean prepare(RenderTarget main,Matrix4fc inverseViewProjection,double cameraX,double cameraY,double cameraZ,
            int epoch,boolean enabled) {
        if(resourceEpoch!=epoch) {close();resourceEpoch=epoch;}
        ensureTargets(main);
        requested=enabled;prepared=false;sourceValid=false;sourceCount=drawnCount=0;
        if(clearHistory)clearHistory();
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(current.getColorTexture(),new Vector4f(0));
        lastRendered=current;lastUsedHistory=history;
        rectangles.reset(main.width,main.height);
        boolean valid=true;
        if(enabled) {
            currentViewProjection.set(inverseViewProjection).invert();
            var snapshot=WkeParticleReactiveRects.snapshot(currentViewProjection,cameraX,cameraY,cameraZ,main.width,main.height);
            sourceFrame=snapshot.frameId();sourceCount=snapshot.count();
            float[] data=snapshot.bounds();
            valid=snapshot.valid() && sourceFrame>lastSourceFrame && sourceCount>=0
                && sourceCount<=WkeReactiveRects.MAX_RECTS && data.length>=(long)sourceCount*4;
            if(valid) {
                for(int i=0;i<sourceCount;i++){int p=i*4;rectangles.include(data[p],data[p+1],data[p+2],data[p+3]);}
                valid=rectangles.rejected()==0;
                lastSourceFrame=sourceFrame;
            }
            reason=valid?"":"Missing, stale or invalid particle render frame";
        } else {
            sourceFrame=WkeParticleReactiveRects.frameId();
            reason="disabled";
        }
        sourceValid=valid;drawnCount=rectangles.count();
        if(drawnCount>0) {
            ensureBuffers();
            instances.rotate();
            GpuBuffer buffer=instances.currentBuffer();
            try(var mapped=buffer.map(0,(long)drawnCount*WkeReactiveRects.RECT_BYTES,false,true)){
                rectangles.write(mapped.data());
            }
            var pipeline=RenderSystem.getCompiledPipeline(PIPELINE);
            try(var pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    ()->"WKE particle reactive coverage",current.getColorTextureView(),Optional.empty())) {
                pass.setPipeline(pipeline);
                pass.setVertexBuffer(0,corners.slice());
                pass.setVertexBuffer(1,buffer.slice(0,(long)drawnCount*WkeReactiveRects.RECT_BYTES));
                // Constant 1 writes form a union even when rectangles overlap. No MAX blend capability is needed.
                pass.draw(6,drawnCount,0,0);
            }
            submittedDraws++;
        }
        prepared=true;preparedFrames++;
        return valid;
    }

    public void bindTargets(Map<Identifier,RenderTarget> targets) {
        if(!prepared||current==null||history==null)throw new IllegalStateException("Reactive mask was not prepared");
        targets.put(CURRENT,current);targets.put(HISTORY,history);
    }
    /** Same successful color/depth capture epoch as TAA, including its first warm-up frame. */
    public void capture() {
        if(!prepared||!sourceValid||!requested)return;
        TextureTarget reusable=history;history=current;current=reusable;captures++;
    }
    /** A TAA cut invalidates the old contribution, while preserving this frame's just-rendered current mask. */
    public void clearHistory() {
        if(history!=null)RenderSystem.getDevice().createCommandEncoder().clearColorTexture(history.getColorTexture(),new Vector4f(0));
        clearHistory=false;historyClears++;
    }
    public void invalidate() {
        clearHistory=true;prepared=sourceValid=false;lastRendered=lastUsedHistory=null;resets++;
    }
    /** Read-only diagnostic targets: exactly the images bound during the last completed graph, even after swapping. */
    public RenderTarget target(){return lastRendered;}
    public RenderTarget historyTarget(){return lastUsedHistory;}
    public Map<String,Object> statistics() {
        Map<String,Object> values=new LinkedHashMap<>();
        values.put("prepared",prepared);values.put("requested",requested);values.put("sourceValid",sourceValid);
        values.put("sourceFrameId",sourceFrame);values.put("sourceRectangles",sourceCount);values.put("renderedRectangles",drawnCount);
        values.put("maxRectangles",WkeReactiveRects.MAX_RECTS);values.put("overflowUnions",rectangles.overflow());
        values.put("rejectedNonFinite",rectangles.rejected());values.put("submittedMaskDraws",submittedDraws);
        values.put("preparedFrames",preparedFrames);values.put("captures",captures);values.put("historyClears",historyClears);values.put("resets",resets);
        values.put("format","R8_UNORM");values.put("width",current==null?0:current.width);values.put("height",current==null?0:current.height);
        values.put("reason",reason);return Map.copyOf(values);
    }
    private void ensureTargets(RenderTarget main) {
        if(current!=null&&current.width==main.width&&current.height==main.height)return;
        if(current!=null)current.destroyBuffers();if(history!=null)history.destroyBuffers();
        current=new TextureTarget("WKE current reactive particles",main.width,main.height,GpuFormat.R8_UNORM,null);
        history=new TextureTarget("WKE previous reactive particles",main.width,main.height,GpuFormat.R8_UNORM,null);
        lastRendered=lastUsedHistory=null;clearHistory=true;prepared=false;
    }
    private void ensureBuffers() {
        if(corners!=null)return;
        ByteBuffer data=ByteBuffer.allocateDirect(6*12).order(ByteOrder.nativeOrder());
        for(float[] p:new float[][]{{0,0},{1,0},{1,1},{0,0},{1,1},{0,1}})data.putFloat(p[0]).putFloat(p[1]).putFloat(0);
        data.flip();
        corners=RenderSystem.getDevice().createBuffer(()->"WKE reactive quad corners",GpuBuffer.USAGE_VERTEX,data);
        instances=new MappableRingBuffer(()->"WKE reactive particle bounds",
            GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_MAP_WRITE,WkeReactiveRects.MAX_RECTS*WkeReactiveRects.RECT_BYTES);
    }
    @Override public void close() {
        if(current!=null)current.destroyBuffers();if(history!=null)history.destroyBuffers();
        if(corners!=null)corners.close();if(instances!=null)instances.close();
        current=history=null;corners=null;instances=null;lastSourceFrame=-1;sourceFrame=-1;
        invalidate();
    }
}
