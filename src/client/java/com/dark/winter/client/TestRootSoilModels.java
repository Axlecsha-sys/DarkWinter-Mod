// Retextures only natural grass and podzol pedestals under upright test-tree roots without changing world state.
package com.dark.winter.client;

import com.dark.winter.worldgen.TestBiomeCompatibility;
import com.dark.winter.worldgen.TestSpruceBlocks;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.model.MeshQuadCollection;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
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
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public final class TestRootSoilModels {
    public static final int ROOT_SOIL_TAG=0x44575E;
    private static final float EPS=.00001f;
    private static volatile Biome testBiome;
    private static boolean initialized;
    private static final ColorResolver TEST_BIOME=SnowBiomePresentation.TERRAIN_MEMBERSHIP;
    private TestRootSoilModels(){}

    public static synchronized void init() {
        if(initialized)return;
        initialized=true;SnowBiomePresentation.registerTerrainResolver();
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client,level)->{
            testBiome=level.registryAccess().lookupOrThrow(Registries.BIOME).get(TestBiomeCompatibility.BIOME).map(h->h.value()).orElse(null);
            // This listener follows the other test-model biome bindings. Earlier compile
            // queries must not retain negative tint samples from an unbound registry.
            level.clearTintCaches();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((listener,client)->testBiome=null);
        ModelLoadingPlugin.register(plugin->{
            Map<BlockState,Alias> aliases=new ConcurrentHashMap<>();
            plugin.modifyBlockModelAfterBake().register((original,context)->{
                BlockState state=context.state();
                if(!grass(state))return original;
                if(state.getValue(SnowyBlock.SNOWY)) {
                    Material.Baked side=context.baker().materials().get(new Material(Identifier.fromNamespaceAndPath("darkwinter",state.is(Blocks.GRASS_BLOCK)?"block/terrain/slab_side_snow_z5":"block/test_snowy_grass_side")),
                            ()->"DarkWinter root pedestal snow");
                    Alias alias=capture(original,side);
                    if(alias!=null)aliases.put(state,alias);
                    return original;
                }
                return new RootSoilModel(original,state,aliases);
            });
        });
    }

    /** Mirrors the lowest upright root gate used by the tree's private ground collar. */
    public static boolean rootContext(BlockAndTintGetter level,BlockPos pos,BlockState state) {
        if(!grass(state)||state.getValue(SnowyBlock.SNOWY)||!state.getFluidState().isEmpty()
                ||testBiome==null||!state.isFaceSturdy(level,pos,Direction.UP))return false;
        BlockPos above=pos.above();BlockState root=level.getBlockState(above);
        if(!root.is(TestSpruceBlocks.BOUGH)&&(!root.is(TestSpruceBlocks.LOG)||root.getValue(RotatedPillarBlock.AXIS)!=Direction.Axis.Y))return false;
        return SnowBiomePresentation.matchesTerrainTint(level.getBlockTint(above,TEST_BIOME));
    }

    private static boolean grass(BlockState state){return state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.PODZOL);}
    private record Alias(Mesh mesh,int flags){}

    private static @Nullable Alias capture(BlockStateModel original,Material.Baked side) {
        var parts=new ArrayList<BlockStateModelPart>();original.collectParts(RandomSource.create(0),parts);
        if(parts.size()!=1)return null;
        var nativeMesh=Renderer.get().mutableMesh();parts.getFirst().emitQuads(nativeMesh.emitter(),face->false);
        var result=Renderer.get().mutableMesh();QuadEmitter emitter=result.emitter();
        boolean[] valid={true};int[] faces={0},count={0};
        nativeMesh.forEach(q->{
            Direction face=q.nominalFace();count[0]++;
            if(face==null||q.chunkLayer()!=ChunkSectionLayer.SOLID||q.emissive()||q.cullFace()!=face
                    ||!rectangle(q,face)||(faces[0]&(1<<face.ordinal()))!=0){valid[0]=false;return;}
            for(int v=1;v<4;v++)if(q.color(v)!=q.color(0)||q.lightmap(v)!=q.lightmap(0))valid[0]=false;
            faces[0]|=1<<face.ordinal();emitter.copyFrom(q).tag(ROOT_SOIL_TAG);
            if(face.getAxis().isHorizontal()) {
                for(int v=0;v<4;v++)emitter.uv(v,face.getAxis()==Direction.Axis.X?q.z(v):q.x(v),1-q.y(v));
                emitter.materialBake(side,MutableQuadView.BAKE_NORMALIZED);
            }
            emitter.emit();
        });
        if(!valid[0]||count[0]!=6||faces[0]!=63)return null;
        Mesh mesh=result.immutableCopy();return new Alias(mesh,new MeshQuadCollection(mesh).materialFlags());
    }

    private static boolean rectangle(QuadView q,Direction face) {
        int corners=0;
        for(int v=0;v<4;v++) {
            float x=q.x(v),y=q.y(v),z=q.z(v);
            if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))return false;
            boolean plane=switch(face){case DOWN->near(y,0);case UP->near(y,1);case NORTH->near(z,0);
                case SOUTH->near(z,1);case WEST->near(x,0);case EAST->near(x,1);};
            float a=face.getAxis()==Direction.Axis.X?z:x,b=face.getAxis()==Direction.Axis.Y?z:y;
            if(!plane||!(near(a,0)||near(a,1))||!(near(b,0)||near(b,1)))return false;
            corners|=1<<((near(a,1)?1:0)|(near(b,1)?2:0));
        }return corners==15;
    }
    private static boolean near(float a,float b){return Math.abs(a-b)<EPS;}

    private static final class RootSoilModel extends WrapperBlockStateModel {
        final BlockState state;final Map<BlockState,Alias> aliases;
        RootSoilModel(BlockStateModel original,BlockState state,Map<BlockState,Alias> aliases){super(original);this.state=state;this.aliases=aliases;}
        private @Nullable Alias at(BlockAndTintGetter level,BlockPos pos,BlockState actual) {
            return actual==state&&rootContext(level,pos,actual)?aliases.get(state.setValue(SnowyBlock.SNOWY,true)):null;
        }
        @Override public void emitQuads(QuadEmitter emitter,BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random,Predicate<@Nullable Direction> cullTest) {
            Alias alias=at(level,pos,state);
            if(alias==null){wrapped.emitQuads(emitter,level,pos,state,random,cullTest);return;}
            alias.mesh.forEach(q->{if(!cullTest.test(q.cullFace()))emitter.copyFrom(q).emit();});
        }
        @Override public @Nullable Object createGeometryKey(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random) {
            Alias alias=at(level,pos,state);return alias==null?wrapped.createGeometryKey(level,pos,state,random):alias.mesh;
        }
        @Override public int materialFlags(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random) {
            Alias alias=at(level,pos,state);return alias==null?wrapped.materialFlags(level,pos,state,random):alias.flags;
        }
        @Override public boolean hasMaterialFlag(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random,int flag) {
            return (materialFlags(level,pos,state,random)&flag)!=0;
        }
    }
}
