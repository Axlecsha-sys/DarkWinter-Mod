// Adds a native-lit snow floor beneath existing ground plants in the dedicated snowy test biome.
package com.dark.winter.client;

import com.winterkore.engine.surface.client.SnowSurfaceClient;

import com.dark.winter.worldgen.TestBiomeCompatibility;
import com.dark.winter.worldgen.TestSpruceBlocks;
import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.SnowVariantFurnitureBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.SnowyBlock;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.client.renderer.v1.model.MeshQuadCollection;
import net.fabricmc.fabric.api.util.TriState;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** No plant placement, block state, collision, weather tick or ordinary-biome geometry changes. */
public final class TestGroundSnowModels {
    public static final int FERN_SNOW_TAG=0x445758;
    private static final Direction[] SIDES={Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST};
    private static volatile Biome testBiome;
    private static boolean initialized;
    // Register once before ClientLevel constructs its Fabric custom tint caches.
    private static final ColorResolver TEST_BIOME=SnowBiomePresentation.TERRAIN_MEMBERSHIP;

    private TestGroundSnowModels() {}

    public static synchronized void init() {
        if(initialized)return;
        initialized=true;
        SnowBiomePresentation.registerTerrainResolver();
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client,level)->
                testBiome=level.registryAccess().lookupOrThrow(Registries.BIOME)
                        .get(TestBiomeCompatibility.BIOME).map(reference->reference.value()).orElse(null));
        ClientPlayConnectionEvents.DISCONNECT.register((listener,client)->testBiome=null);
        ModelLoadingPlugin.register(plugin->{
            var event=plugin.modifyBlockModelAfterBake();
            Identifier phase=Identifier.fromNamespaceAndPath("darkwinter","snow_ground_after_furniture");
            event.addPhaseOrdering(Event.DEFAULT_PHASE,phase);
            event.register(phase,(original,context)->{
            if(!groundPlant(context.state()))return original;
            Material.Baked snow=context.baker().materials().get(new Material(Identifier.fromNamespaceAndPath("darkwinter","block/test_ground_snow")),
                    ()->"DarkWinter test plant ground snow");
            return new GroundPlantModel(original,new Planes(snow));
            });
        });
    }

    static boolean groundPlant(BlockState state) {
        if(state.is(TestSpruceBlocks.BOUGH)||state.is(Blocks.FERN)||state.is(Blocks.SHORT_GRASS)
                ||state.is(Blocks.LARGE_FERN)&&state.getValue(DoublePlantBlock.HALF)==DoubleBlockHalf.LOWER)return true;
        return com.dark.winter.block.SnowyPlantFloorRestore.floorPart(state);
    }

    /** Conservative biome-blend boundaries retain the original plant without extra geometry. */
    public static int overlayLayers(BlockAndTintGetter level,BlockPos pos) {
        if(testBiome==null||!groundPlant(level.getBlockState(pos))||!level.getFluidState(pos).isEmpty())return 0;
        BlockPos below=pos.below();
        if(!level.getBlockState(below).isFaceSturdy(level,below,Direction.UP))return 0;
        int layers=9;
        for(Direction side:SIDES) {
            BlockState neighbor=level.getBlockState(pos.relative(side));
            int snow=neighbor.is(Blocks.SNOW)?neighbor.getValue(SnowLayerBlock.LAYERS):neighbor.is(Blocks.SNOW_BLOCK)?8:0;
            if(snow>0)layers=Math.min(layers,snow);
        }
        // The registered resolver uses the native, cached, compile-worker tint path.
        // White requires every blended sample to be the exact dedicated biome instance.
        BlockState support=level.getBlockState(below);
        if(layers==9&&TestSnowTerrainModels.soil(support))layers=1;
        return layers<=8&&SnowBiomePresentation.matchesTerrainTint(level.getBlockTint(pos,TEST_BIOME))?layers:0;
    }

    private record Plane(Mesh mesh,int flags) {}
    private static final class Planes {
        final Material.Baked material;
        final java.util.concurrent.atomic.AtomicReferenceArray<Plane> variants=new java.util.concurrent.atomic.AtomicReferenceArray<>(128);
        Planes(Material.Baked material){this.material=material;}
        Plane at(int layers,int walls){
            int key=(layers-1)*16+walls;Plane known=variants.get(key);if(known!=null)return known;
            float y=layers/8f;var mutable=Renderer.get().mutableMesh();QuadEmitter e=mutable.emitter();
            face(e,new float[]{0,0,1,1},new float[]{y,y,y,y},new float[]{0,1,1,0},Direction.UP);
            for(int side=0;side<4;side++)if((walls&(1<<side))!=0){
                switch(SIDES[side]){
                    case NORTH->face(e,new float[]{0,0,1,1},new float[]{0,y,y,0},new float[]{0,0,0,0},Direction.NORTH);
                    case EAST->face(e,new float[]{1,1,1,1},new float[]{0,y,y,0},new float[]{0,0,1,1},Direction.EAST);
                    case SOUTH->face(e,new float[]{1,1,0,0},new float[]{0,y,y,0},new float[]{1,1,1,1},Direction.SOUTH);
                    case WEST->face(e,new float[]{0,0,0,0},new float[]{0,y,y,0},new float[]{1,1,0,0},Direction.WEST);
                    default->throw new AssertionError();
                }
            }
            Mesh mesh=mutable.immutableCopy();Plane built=new Plane(mesh,new MeshQuadCollection(mesh).materialFlags());
            variants.compareAndSet(key,null,built);return variants.get(key);
        }
        void face(QuadEmitter e,float[] x,float[] y,float[] z,Direction normal){
            for(int v=0;v<4;v++)e.pos(v,x[v],y[v],z[v]).normal(v,normal.getStepX(),normal.getStepY(),normal.getStepZ())
                    .uv(v,normal==Direction.UP?x[v]:v<2?0:1,normal==Direction.UP?z[v]:1-y[v]).color(v,-1);
            e.materialBake(material,MutableQuadView.BAKE_NORMALIZED);
            e.tag(FERN_SNOW_TAG).cullFace(null).nominalFace(normal).tintIndex(-1)
                    .ambientOcclusion(TriState.TRUE).shadeMode(ShadeMode.ENHANCED).chunkLayer(ChunkSectionLayer.SOLID).emit();
        }
    }

    private static int openWalls(BlockAndTintGetter level,BlockPos pos,int layers){
        int walls=0;
        for(int i=0;i<4;i++){
            BlockPos neighbor=pos.relative(SIDES[i]);BlockState state=level.getBlockState(neighbor);
            int cover=state.is(Blocks.SNOW)?state.getValue(SnowLayerBlock.LAYERS):state.is(Blocks.SNOW_BLOCK)?8:
                    groundPlant(state)?overlayLayers(level,neighbor):
                    state.is(com.dark.winter.worldgen.TestTerrainBlocks.SNOWY_GRASS_SLAB)
                    &&state.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)==net.minecraft.world.level.block.state.properties.SlabType.BOTTOM
                    &&state.getValue(com.dark.winter.block.TestSnowyGrassSlabBlock.SNOWY)
                    &&!state.getValue(net.minecraft.world.level.block.SlabBlock.WATERLOGGED)?5:0;
            if(cover<layers&&!state.isFaceSturdy(level,neighbor,SIDES[i].getOpposite()))walls|=1<<i;
        }
        return walls;
    }

    private record GeometryKey(Object original,int layers,int crust,int walls,double offsetX,double offsetY,double offsetZ) {}
    private static final class GroundPlantModel extends WrapperBlockStateModel {
        final Planes planes;
        GroundPlantModel(BlockStateModel original,Planes planes){super(original);this.planes=planes;}
        @Override public void emitQuads(QuadEmitter emitter,BlockAndTintGetter level,BlockPos pos,BlockState state,
                                       RandomSource random,Predicate<@Nullable Direction> cullTest) {
            wrapped.emitQuads(emitter,level,pos,state,random,cullTest);
            int layers=overlayLayers(level,pos);
            if(layers==0||cullTest.test(null))return;
            Vec3 offset=state.getOffset(pos);
            // Native Indigo applies the plant's positional offset after model emission.
            // Cancel it only for this new floor; the existing plant is never transformed.
            emitter.pushTransform(quad->{
                // This transform wraps only the added snow floor; the plant was emitted above.
                if(SnowSurfaceClient.suppressTop(pos))return false;
                if(quad.tag()==TestSnowTerrainModels.SNOW_BEVEL_TAG)quad.tag(FERN_SNOW_TAG);
                quad.translate((float)-offset.x,(float)-offset.y,(float)-offset.z);return true;
            });
            try{if(!TestSnowTerrainModels.emitPlantFloor(emitter,level,pos,layers,cullTest))planes.at(layers,openWalls(level,pos,layers)).mesh().outputTo(emitter);}
            finally{emitter.popTransform();}
        }
        @Override public @Nullable Object createGeometryKey(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random) {
            if(SnowSurfaceClient.ownsTop(pos))return null;
            Object original=wrapped.createGeometryKey(level,pos,state,random);
            int layers=overlayLayers(level,pos);
            if(original==null||layers==0)return original;
            if((TestSnowTerrainModels.surfaceKey(level,pos)&255)!=0)return null;
            Vec3 offset=state.getOffset(pos);
            return new GeometryKey(original,layers,TestSnowTerrainModels.crustKey(level,pos,layers),openWalls(level,pos,layers),offset.x,offset.y,offset.z);
        }
        @Override public int materialFlags(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random) {
            int flags=wrapped.materialFlags(level,pos,state,random),layers=overlayLayers(level,pos);
            return layers==0?flags:flags|planes.at(layers,0).flags();
        }
        @Override public boolean hasMaterialFlag(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random,int flag) {
            return (materialFlags(level,pos,state,random)&flag)!=0;
        }
    }
}
