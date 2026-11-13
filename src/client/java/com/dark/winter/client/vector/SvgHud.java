// Loads vector HUD resources and submits texture-free distance-shaded GUI elements.
package com.dark.winter.client.vector;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.ArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

/** SVG is parsed on resource reload. Each GUI frame retains its own immutable geometry snapshot. */
public final class SvgHud {
    private static final String[] ASSETS={"health","stamina","satiety","temperature","hydration","rest",
        "wind-needle","settings","exit","signal","compass-face","dial","warning","trend-up","trend-down"};
    private static final int COMPASS=10,DIAL=11,WARNING=12,UP=13,DOWN=14;
    private static final RenderStateDataKey<Frame> FRAME=RenderStateDataKey.create(()->"DarkWinter SVG HUD geometry");
    private static final VertexFormat FORMAT=VertexFormat.builder(0)
        .addAttribute("Position",GpuFormat.RGB32_FLOAT)
        .addAttribute("Color",GpuFormat.RGBA8_UNORM)
        .addAttribute("UV0",GpuFormat.RG32_FLOAT)
        .addAttribute("UV2",GpuFormat.RG16_SINT)
        .addAttribute("UV3",GpuFormat.RG32_FLOAT).build();
    private static RenderPipeline pipeline(String name){return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
        .withLocation(id("pipeline/"+name))
        .withVertexShader(id("core/svg_hud"))
        .withFragmentShader(id("core/svg_hud"))
        .withBindGroupLayout(BindGroupLayout.builder().withUniform("SvgSegments",UniformType.UNIFORM_BUFFER)
            .withUniform("SvgShapes",UniformType.UNIFORM_BUFFER).build())
        .withVertexBinding(0,FORMAT).withCull(false).build());}
    private static final RenderPipeline PIPELINE=pipeline("svg_hud"),MENU_PIPELINE=pipeline("svg_menu"),INVENTORY_PIPELINE=pipeline("svg_inventory");
    private record Prepared(SvgGeometry.Atlas atlas,SvgGeometry.Atlas menu,SvgGeometry.Atlas inventory) {}
    private record GpuPage(GpuBuffer segments,GpuBuffer shapes) implements AutoCloseable {
        @Override public void close(){shapes.close();segments.close();}
    }
    private record Frame(Prepared prepared) {}
    private static volatile Prepared prepared;
    // GPU resources are exclusively accessed on the render thread, never during extraction/preparation.
    private static Prepared uploaded;
    private static GpuPage[] pages;
    private static boolean initialized;
    private SvgHud(){}
    private static Identifier id(String path){return Identifier.fromNamespaceAndPath("darkwinter",path);}

    public static void initialize(){
        if(initialized)return;initialized=true;
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(id("svg_hud"),new SimpleReloadListener<Prepared>(){
            @Override protected Prepared prepare(PreparableReloadListener.SharedState state){
                var assets=new ArrayList<SvgGeometry.Asset>();
                for(String name:ASSETS){
                    Identifier resource=id("svg/hud/"+name+".svg");
                    try(var stream=state.resourceManager().open(resource)){assets.add(SvgGeometry.read(stream));}
                    catch(Exception error){throw new IllegalStateException("Cannot read HUD SVG "+resource,error);}
                }
                // A partial/unsupported pack never replaces the currently working geometry.
                var menu=new ArrayList<SvgGeometry.Asset>();
                for(MenuGlyph glyph:MenuGlyph.values()){
                    Identifier resource=id("svg/menu/"+glyph.resource+".svg");
                    try(var stream=state.resourceManager().open(resource)){menu.add(SvgGeometry.read(stream));}
                    catch(Exception error){throw new IllegalStateException("Cannot read menu SVG "+resource,error);}
                }
                var inventory=new ArrayList<SvgGeometry.Asset>();
                for(InventoryGlyph glyph:InventoryGlyph.values()){
                    Identifier resource=id("svg/inventory/"+glyph.resource+".svg");
                    try(var stream=state.resourceManager().open(resource)){inventory.add(SvgGeometry.read(stream));}
                    catch(Exception error){throw new IllegalStateException("Cannot read inventory SVG "+resource,error);}
                }
                return new Prepared(SvgGeometry.pack(assets),SvgGeometry.pack(menu),SvgGeometry.pack(inventory));
            }
            @Override protected void apply(Prepared next,PreparableReloadListener.SharedState state){prepared=next;}
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->close());
    }
    private static Frame frame(GuiRenderState state){
        Frame snapshot=state.getData(FRAME);
        if(snapshot==null){snapshot=new Frame(prepared);state.setData(FRAME,snapshot);}
        return snapshot;
    }
    /** Before GuiRenderer opens a render pass. Geometry is uploaded once per resource generation. */
    public static void prepareFrame(GuiRenderState state){
        RenderSystem.assertOnRenderThread();
        Frame frame=state.getData(FRAME);if(frame==null||frame.prepared==null||frame.prepared==uploaded)return;
        GpuPage[] next=new GpuPage[3];
        try{next[0]=upload(frame.prepared.atlas);next[1]=upload(frame.prepared.menu);next[2]=upload(frame.prepared.inventory);}
        catch(RuntimeException error){for(GpuPage page:next)if(page!=null)page.close();throw error;}
        GpuPage[] old=pages;pages=next;uploaded=frame.prepared;retire(old);
    }
    private static GpuPage upload(SvgGeometry.Atlas atlas){
        GpuBuffer segments=RenderSystem.getDevice().createBuffer(()->"SVG contour page",GpuBuffer.USAGE_UNIFORM,atlas.segmentBlock());
        try{return new GpuPage(segments,RenderSystem.getDevice().createBuffer(()->"SVG paint page",GpuBuffer.USAGE_UNIFORM,atlas.shapeBlock()));}
        catch(RuntimeException error){segments.close();throw error;}
    }
    /** Called after the native pipeline is selected; no allocation/upload happens inside the pass. */
    public static void bind(GuiRenderState state,RenderPipeline pipeline,RenderPass pass){
        if(pipeline!=PIPELINE&&pipeline!=MENU_PIPELINE&&pipeline!=INVENTORY_PIPELINE)return;
        Frame frame=state.getData(FRAME);
        if(frame==null||frame.prepared==null||frame.prepared!=uploaded||pages==null)
            throw new IllegalStateException("SVG draw does not match its prepared GUI frame");
        var page=pages[pipeline==PIPELINE?0:pipeline==MENU_PIPELINE?1:2];
        pass.setUniform("SvgSegments",page.segments.slice());pass.setUniform("SvgShapes",page.shapes.slice());
    }
    public static void endFrame(GuiRenderState state){state.setData(FRAME,null);}
    private static void retire(GpuPage[] old){
        if(old!=null)RenderSystem.queueFencedTask(()->{for(GpuPage page:old)page.close();});
    }
    private static void close(){
        RenderSystem.assertOnRenderThread();retire(pages);pages=null;uploaded=null;prepared=null;
    }
    public static void icon(GuiGraphicsExtractor graphics,int index,int x,int y,int size,int color){
        if(index>=0&&index<10)asset(graphics,index,x,y,size,1,1,color);
    }
    public static void compass(GuiGraphicsExtractor graphics,int x,int y,int size,int color){asset(graphics,COMPASS,x,y,size,1,1,color);}
    public static void menuIcon(GuiGraphicsExtractor graphics,MenuGlyph glyph,int x,int y,int size,int color){asset(graphics,1,glyph.ordinal(),x,y,size,1,1,color);}
    public static void inventoryIcon(GuiGraphicsExtractor graphics,InventoryGlyph glyph,float x,float y,float size,int color){asset(graphics,2,glyph.ordinal(),x,y,size,1,1,color);}
    public static void warning(GuiGraphicsExtractor graphics,int x,int y,int size,int color){asset(graphics,WARNING,x,y,size,1,1,color);}
    public static void trend(GuiGraphicsExtractor graphics,int x,int y,int size,boolean up,int color){asset(graphics,up?UP:DOWN,x,y,size,1,1,color);}
    public static void arc(GuiGraphicsExtractor graphics,int x,int y,int size,float fraction,int color,boolean glow){
        if(Float.isFinite(fraction)&&fraction>0)asset(graphics,DIAL,x,y,size,Math.min(1,fraction),glow?2:1,color);
    }
    public static void shadow(GuiGraphicsExtractor graphics,int cx,int cy,int radius,int color){
        if(radius<=0)return;var snapshot=frame(graphics.guiRenderState);if(snapshot.prepared==null)return;
        submit(graphics,cx-radius,cy-radius,radius*2,radius*2,0,0,64,64,0,0,1,3,color,0);
    }
    public static void frameGlow(GuiGraphicsExtractor graphics,int x,int y,int w,int h,int color){
        if(w<=0||h<=0||w>32767||h>32767)return;var snapshot=frame(graphics.guiRenderState);if(snapshot.prepared==null)return;
        submit(graphics,x,y,w,h,0,0,w,h,w,h,1,4,color,6);
    }
    public static void surface(GuiGraphicsExtractor graphics,int x,int y,int w,int h,MenuSurface material,float light,int accent){
        if(w<=0||h<=0||w>32767||h>32767)return;
        var snapshot=frame(graphics.guiRenderState);if(snapshot.prepared==null)return;
        float state=material.ordinal()+Math.clamp(light,0,1)*.5f;
        submit(graphics,x,y,w,h,0,0,w,h,w,h,state,5,accent,1,MENU_PIPELINE);
    }
    private static void asset(GuiGraphicsExtractor graphics,int index,float x,float y,float size,float fraction,float effect,int color){
        asset(graphics,0,index,x,y,size,fraction,effect,color);
    }
    private static void asset(GuiGraphicsExtractor graphics,int page,int index,float x,float y,float size,float fraction,float effect,int color){
        if(size<=0||(color>>>24)==0)return;
        Frame frame=frame(graphics.guiRenderState);if(frame.prepared==null)return;
        var atlas=page==0?frame.prepared.atlas:page==1?frame.prepared.menu:frame.prepared.inventory;var asset=atlas.assets().get(index);
        float scale=Math.min(size/asset.width(),size/asset.height());
        float w=asset.width()*scale,h=asset.height()*scale;
        float padding=effect==2?Math.max(3,10*scale):1;
        submit(graphics,x+(size-w)/2,y+(size-h)/2,w,h,asset.minX(),asset.minY(),asset.width(),asset.height(),
            atlas.firstShapes().get(index),asset.shapes().size(),fraction,effect,color,padding,page==0?PIPELINE:page==1?MENU_PIPELINE:INVENTORY_PIPELINE);
    }
    private static void submit(GuiGraphicsExtractor graphics,float x,float y,float w,float h,float vx,float vy,float vw,float vh,
                               int first,int count,float fraction,float effect,int color,float pad){
        submit(graphics,x,y,w,h,vx,vy,vw,vh,first,count,fraction,effect,color,pad,PIPELINE);
    }
    private static void submit(GuiGraphicsExtractor graphics,float x,float y,float w,float h,float vx,float vy,float vw,float vh,
                               int first,int count,float fraction,float effect,int color,float pad,RenderPipeline pipeline){
        if((color>>>24)==0)return;
        var pose=new Matrix3x2f(graphics.pose());var clip=graphics.scissorStack.peek();
        int left=(int)Math.floor(x-pad),top=(int)Math.floor(y-pad),right=(int)Math.ceil(x+w+pad),bottom=(int)Math.ceil(y+h+pad);
        ScreenRectangle bounds=new ScreenRectangle(left,top,right-left,bottom-top).transformMaxBounds(pose);
        if(clip!=null)bounds=clip.intersection(bounds);
        if(bounds==null||bounds.width()<=0||bounds.height()<=0)return;
        graphics.guiRenderState.addGuiElement(new Element(pipeline,pose,x-pad,y-pad,w+pad*2,h+pad*2,vx-pad*vw/w,vy-pad*vh/h,
            vw*(1+pad*2/w),vh*(1+pad*2/h),first,count,fraction,effect,color,clip,bounds));
    }
    private record Element(RenderPipeline pipeline,Matrix3x2fc pose,float x,float y,float w,float h,float vx,float vy,float vw,float vh,
                           int first,int count,float fraction,float effect,int color,ScreenRectangle scissorArea,ScreenRectangle bounds)implements GuiElementRenderState{
        @Override public TextureSetup textureSetup(){return TextureSetup.noTexture();}
        @Override public void buildVertices(VertexConsumer out){
            vertex(out,x,y,vx,vy);vertex(out,x,y+h,vx,vy+vh);vertex(out,x+w,y+h,vx+vw,vy+vh);vertex(out,x+w,y,vx+vw,vy);
        }
        private void vertex(VertexConsumer out,float px,float py,float u,float v){
            out.addVertexWith2DPose(pose,px,py).setColor(color).setUv(u,v).setUv2(first,count).setUv3(fraction,effect);
        }
    }
}
