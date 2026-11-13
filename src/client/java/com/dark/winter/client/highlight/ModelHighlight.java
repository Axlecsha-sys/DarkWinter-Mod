// Replaces only the native visual outline with bounded model-aligned client geometry.
package com.dark.winter.client.highlight;

import com.dark.winter.placement.AnchorRuntime;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.rendering.v1.FeatureRendererRegistry;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import com.dark.winter.worldgen.TestBiomeCompatibility;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Native hit testing, reach, collision, depth, OIT and accessibility settings are retained. */
public final class ModelHighlight {
    private static final int MAX_FACES=4096,MAX_EDGES=8192;
    private static volatile HighlightResources.Prepared prepared=new HighlightResources.Prepared(Map.of(),0);
    private static volatile Target cache;
    private static volatile long extractions,captures,submissions,reloadEpoch,lastCaptureNanos,maxCaptureNanos;
    private static volatile String lastSource="none";
    private static volatile BlockPos lastTarget;
    private static volatile BlockState lastState;
    private static volatile Frame lastFrame;
    private static volatile float lastWidth;
    private static volatile boolean lastHighContrast,lastAfterTerrain;
    private static boolean initialized;
    private static final ThreadLocal<RandomSource> RANDOM=ThreadLocal.withInitial(()->RandomSource.create(0));
    private ModelHighlight(){}

    public record Frame(float[] segments,Vec3 offset) {}
    /** The field is written once during extraction, before the native frame is published. */
    public interface OutlineState {
        @Nullable Frame darkwinter$modelOutline();
        void darkwinter$modelOutline(@Nullable Frame frame);
    }
    public record Snapshot(String source,@Nullable BlockPos target,String blockState,int lines,long extractions,
            long captures,long submissions,long reloadEpoch,int loadedDefinitions,float[] localBounds,
            float lastWidth,boolean lastHighContrast,boolean lastAfterTerrain,long lastCaptureNanos,long maxCaptureNanos) {}
    public static Snapshot snapshot(){
        Frame frame=lastFrame;float[] bounds=new float[0];
        if(frame!=null){bounds=new float[]{Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
            for(int i=0;i<frame.segments.length;i+=3)for(int axis=0;axis<3;axis++){
                float value=frame.segments[i+axis]+(float)(axis==0?frame.offset.x:axis==1?frame.offset.y:frame.offset.z);
                bounds[axis]=Math.min(bounds[axis],value);bounds[axis+3]=Math.max(bounds[axis+3],value);
            }}
        BlockState state=lastState;
        return new Snapshot(lastSource,lastTarget,state==null?"":state.toString(),frame==null?0:frame.segments.length/6,
                extractions,captures,submissions,reloadEpoch,prepared.definitions(),bounds,lastWidth,lastHighContrast,lastAfterTerrain,lastCaptureNanos,maxCaptureNanos);
    }
    public static synchronized void init(){
        if(initialized)return;initialized=true;
        FeatureRendererRegistry.register(HighlightFeature.TYPE,HighlightFeature::new);
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client,level)->clear());
        ClientPlayConnectionEvents.DISCONNECT.register((listener,client)->clear());
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                Identifier.fromNamespaceAndPath("darkwinter","model_highlights"),new SimpleReloadListener<HighlightResources.Prepared>(){
                    @Override protected HighlightResources.Prepared prepare(PreparableReloadListener.SharedState state){return HighlightResources.load(state.resourceManager());}
                    @Override protected void apply(HighlightResources.Prepared next,PreparableReloadListener.SharedState state){prepared=next;reloadEpoch++;clear();}
                });
    }
    private static void clear(){cache=null;lastSource="none";lastTarget=null;lastState=null;lastFrame=null;}
    public static void extract(Camera camera,LevelRenderState frameState){
        extractions++;BlockOutlineRenderState outline=frameState.blockOutlineRenderState;
        Minecraft client=Minecraft.getInstance();ClientLevel level=client.level;
        lastFrame=null;lastTarget=null;lastState=null;lastSource="none";
        if(outline!=null)((OutlineState)(Object)outline).darkwinter$modelOutline(null);
        // A miss is still a BlockHitResult. Only a current native BLOCK hit may own custom lines.
        // This also discards a former frame's attachment when another extractor retains its state.
        if(!(client.hitResult instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)return;
        if(outline==null||level==null)return;
        lastTarget=outline.pos();lastSource="fallback";
        if(SharedConstants.DEBUG_SHAPES)return;
        BlockPos pos=outline.pos();BlockState state=level.getBlockState(pos);
        if(state.isAir()){lastTarget=null;lastSource="none";return;}
        lastState=state;
        float[] lines=prepared.states().get(state);
        if(lines!=null){lastSource="json";attach(outline,new Frame(lines,modelOffset(level,pos,state)));return;}
        var model=client.getModelManager().getBlockStateModelSet().get(state);
        Identifier id=BuiltInRegistries.BLOCK.getKey(state.getBlock());
        var biome=level.getBiome(pos);
        if(!id.getNamespace().equals("darkwinter")
                &&!(model.getClass().getName().contains("TestSnowTerrainModels$")&&biome.is(TestBiomeCompatibility.BIOME)))return;
        var random=RANDOM.get();random.setSeed(state.getSeed(pos));
        Object geometryKey;try{geometryKey=model.createGeometryKey(level,pos,state,random);}catch(RuntimeException unsupported){return;}
        Target target=cache;
        if(target==null||!target.matches(level,pos,state,model,biome,geometryKey)){
            BlockState[] neighbors=neighbors(level,pos);random.setSeed(state.getSeed(pos));captures++;
            long started=System.nanoTime();var geometry=capture(level,pos,state,model,random);
            lastCaptureNanos=System.nanoTime()-started;maxCaptureNanos=Math.max(maxCaptureNanos,lastCaptureNanos);
            target=new Target(level,pos.immutable(),state,model,biome,geometryKey,neighbors,geometry);cache=target;
        }
        if(target.geometry==null)return;
        Vec3 eye=camera.position(),offset=modelOffset(level,pos,state);
        lines=target.geometry.lines((float)(eye.x-pos.getX()-offset.x),(float)(eye.y-pos.getY()-offset.y),(float)(eye.z-pos.getZ()-offset.z));
        if(lines.length==0)return;
        lastSource="mesh";attach(outline,new Frame(lines,offset));
    }
    private static Vec3 modelOffset(ClientLevel level,BlockPos pos,BlockState state){
        Vec3 nativeOffset=state.getOffset(pos),anchor=AnchorRuntime.offset(level,pos);
        return anchor.x==0&&anchor.y==0&&anchor.z==0?nativeOffset:nativeOffset.add(anchor);
    }
    private static void attach(BlockOutlineRenderState state,Frame frame){((OutlineState)(Object)state).darkwinter$modelOutline(frame);lastFrame=frame;}
    private static HighlightEdges.@Nullable Geometry capture(ClientLevel level,BlockPos pos,BlockState state,BlockStateModel model,RandomSource random){
        var builder=HighlightEdges.builder(MAX_FACES,MAX_EDGES);var scratch=Renderer.get().mutableMesh();var emitter=scratch.emitter();
        float[] xyz=new float[12];int[] faces={0};
        emitter.pushTransform(quad->{
            if(++faces[0]>MAX_FACES)throw Budget.INSTANCE;
            for(int v=0;v<4;v++){xyz[v*3]=quad.x(v);xyz[v*3+1]=quad.y(v);xyz[v*3+2]=quad.z(v);}builder.face(xyz);
            return false; // The selected model is streamed directly into the edge kernel, never retained as a second mesh.
        });
        try{model.emitQuads(emitter,level,pos,state,random,side->false);return builder.build();}
        catch(RuntimeException unsupported){return null;}
        finally{emitter.popTransform();}
    }
    private static BlockState[] neighbors(ClientLevel level,BlockPos pos){
        BlockState[] states=new BlockState[45];var cursor=new BlockPos.MutableBlockPos();int i=0;
        for(int y=-2;y<=2;y++)for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++)states[i++]=level.getBlockState(cursor.set(pos.getX()+x,pos.getY()+y,pos.getZ()+z));
        return states;
    }
    private record Target(ClientLevel level,BlockPos pos,BlockState state,BlockStateModel model,Object biome,
            @Nullable Object key,BlockState[] neighbors,HighlightEdges.@Nullable Geometry geometry){
        boolean matches(ClientLevel level,BlockPos pos,BlockState state,BlockStateModel model,Object biome,Object key){
            if(this.level!=level||!this.pos.equals(pos)||this.state!=state||this.model!=model||!this.biome.equals(biome)||!Objects.equals(this.key,key))return false;
            var cursor=new BlockPos.MutableBlockPos();int i=0;
            for(int y=-2;y<=2;y++)for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++)
                if(neighbors[i++]!=level.getBlockState(cursor.set(pos.getX()+x,pos.getY()+y,pos.getZ()+z)))return false;
            return true;
        }
    }
    private static final class Budget extends RuntimeException {
        static final Budget INSTANCE=new Budget();private Budget(){super(null,null,false,false);}
    }
    public static boolean submit(PoseStack poses,SubmitNodeCollector collector,RenderType type,BlockOutlineRenderState state,
            int color,float width,boolean afterTerrain){
        if(SharedConstants.DEBUG_SHAPES)return false;
        Frame frame=((OutlineState)(Object)state).darkwinter$modelOutline();if(frame==null)return false;
        HighlightFeature.submit(poses,collector,type,frame,color,width,afterTerrain);
        submissions++;lastWidth=width;lastHighContrast=state.highContrast();lastAfterTerrain=afterTerrain;return true;
    }
}
