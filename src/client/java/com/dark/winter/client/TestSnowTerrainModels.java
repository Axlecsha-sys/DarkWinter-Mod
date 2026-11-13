// Bevels only genuine snowy ground in the dedicated test biome; world data and native collisions remain unchanged.
package com.dark.winter.client;

import com.dark.winter.worldgen.TestBiomeCompatibility;
import com.winterkore.engine.surface.client.SnowSurfaceClient;
import com.dark.winter.worldgen.TestTerrainBlocks;
import com.dark.winter.block.TestSnowyGrassSlabBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.*;
import net.fabricmc.fabric.api.client.renderer.v1.model.MeshQuadCollection;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.*;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Shared shallow snow shoulders; physical half-steps are generated separately as native slabs. */
public final class TestSnowTerrainModels {
    public static final int SNOW_BEVEL_TAG=0x445759,SOIL_FRINGE_TAG=0x44575A,SOIL_BEVEL_TAG=0x44575B;
    private static final int UNKNOWN=99;
    private static final float EPS=.00001f;
    private static final int[] DX={-1,0,1,-1,0,1,-1,0,1},DZ={-1,-1,-1,0,0,0,1,1,1};
    private static final int[][] CORNERS={{0,1,3,4},{1,2,4,5},{4,5,7,8},{3,4,6,7}};
    private static final int[] EDGES={1,5,7,3};
    private static final ThreadLocal<Scratch> SCRATCH=ThreadLocal.withInitial(Scratch::new);
    private static volatile Biome testBiome;
    private static volatile Session latestSession;
    private static boolean initialized;
    private static final ColorResolver TEST_BIOME=SnowBiomePresentation.TERRAIN_MEMBERSHIP;
    private TestSnowTerrainModels(){}
    public static synchronized void init() {
        if(initialized)return;initialized=true;SnowBiomePresentation.registerTerrainResolver();
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client,level)->testBiome=level.registryAccess().lookupOrThrow(Registries.BIOME).get(TestBiomeCompatibility.BIOME).map(h->h.value()).orElse(null));
        ClientPlayConnectionEvents.DISCONNECT.register((listener,client)->testBiome=null);
        ModelLoadingPlugin.register(plugin->{
            Session session=new Session();latestSession=session;
            plugin.modifyBlockModelAfterBake().register((original,context)->{
                BlockState state=context.state();boolean snow=snowLayers(state)>0;
                if(!snow&&!soil(state))return original;
                try {
                    var templates=new IdentityHashMap<Object,Template>();
                    if(!collect(original,snow?snowLayers(state)/8f:1f,templates))return original;
                    if(snow) {
                        Material.Baked material=context.baker().materials().get(new Material(Identifier.fromNamespaceAndPath("darkwinter","block/test_ground_snow")),
                                ()->"DarkWinter test terrain snow");
                        templates.replaceAll((key,template)->template.retexture(material));
                    }
                    if(!snow&&state.hasProperty(SnowyBlock.SNOWY)&&state.getValue(SnowyBlock.SNOWY)){
                        Material.Baked side=context.baker().materials().get(new Material(Identifier.fromNamespaceAndPath("darkwinter",state.is(Blocks.GRASS_BLOCK)?"block/terrain/slab_side_snow_z5":"block/test_snowy_grass_side")),
                                ()->"DarkWinter snowy grass side");
                        templates.replaceAll((key,template)->template.snowyBody(side));
                    }
                    session.models.put(state,new Source(original,templates));return new TerrainModel(original,session,state,snow);
                }catch(ReflectiveOperationException|IllegalArgumentException unsupported){return original;}
            });
        });
    }
    /** Exposes the same bounded effective mask used by both real owner models; zero means original geometry. */
    public static int surfaceKey(BlockAndTintGetter level,BlockPos pos) {
        Session session=latestSession;BlockState state=level.getBlockState(pos);return session==null?0:key(session,level,pos,state,snowLayers(state)>0||TestGroundSnowModels.groundPlant(state));
    }
    public static boolean supportsDynamic(BlockState state){Session session=latestSession;return session!=null&&session.models.containsKey(state);}
    static boolean soil(BlockState s){return s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.DIRT)||s.is(Blocks.COARSE_DIRT)||s.is(Blocks.PODZOL)||s.is(Blocks.ROOTED_DIRT);}
    private static int snowLayers(BlockState s){return s.is(Blocks.SNOW)?s.getValue(SnowLayerBlock.LAYERS):s.is(Blocks.SNOW_BLOCK)?8:0;}
    private static boolean test(BlockAndTintGetter level,BlockPos pos){return testBiome!=null&&SnowBiomePresentation.matchesTerrainTint(level.getBlockTint(pos,TEST_BIOME));}
    private static int key(Session session,BlockAndTintGetter level,BlockPos pos,BlockState state,boolean snowOwner) {
        if(testBiome==null||!session.models.containsKey(state)&&!(snowOwner&&TestGroundSnowModels.groundPlant(state)))return 0;
        Scratch scratch=SCRATCH.get();int x=pos.getX(),y=pos.getY()-(snowOwner?1:0),z=pos.getZ();
        BlockPos.MutableBlockPos p=scratch.pos;
        BlockState support=level.getBlockState(p.set(x,y,z)),cover=level.getBlockState(p.set(x,y+1,z));
        int layers=coverLayers(level,p,cover);
        if(!soil(support)||layers==0||!session.models.containsKey(support)||!session.models.containsKey(snowState(cover,layers))||!test(level,p))return 0;
        if(!support.getFluidState().isEmpty()||!cover.getFluidState().isEmpty())return 0;
        for(int i=0;i<9;i++)scratch.columns[i]=column(session,level,p,x+DX[i],y,z+DZ[i],layers);
        if(scratch.columns[4]!=0)return layers<<8;
        int mask=0;
        for(int side=0;side<4;side++)if(scratch.columns[EDGES[side]]==-1)mask|=1<<side;
        for(int corner=0;corner<4;corner++){
            boolean known=true,lower=false;
            for(int i:CORNERS[corner]){known&=scratch.columns[i]!=UNKNOWN;lower|=scratch.columns[i]==-1;}
            if(known&&lower)mask|=1<<(corner+4);
        }
        return (layers<<8)|mask;
    }
    private static int column(Session session,BlockAndTintGetter level,BlockPos.MutableBlockPos p,int x,int y,int z,int layers) {
        BlockState here=level.getBlockState(p.set(x,y,z)),above=level.getBlockState(p.set(x,y+1,z));
        if(!test(level,p)||!here.getFluidState().isEmpty()||!above.getFluidState().isEmpty())return UNKNOWN;
        if(soil(here)&&session.models.containsKey(here)&&coverLayers(level,p,above)==layers&&session.models.containsKey(snowState(above,layers))) {
            // Contacts above the cap retain a flat shared boundary, including stacked snow and furniture.
            return TestGroundSnowModels.groundPlant(above)||level.getBlockState(p.set(x,y+2,z)).isAir()?0:UNKNOWN;
        }
        if(coverLayers(level,p.set(x,y,z),here)==layers&&session.models.containsKey(snowState(here,layers))&&(above.isAir()||TestGroundSnowModels.groundPlant(here))){
            BlockState below=level.getBlockState(p.set(x,y-1,z));
            return soil(below)&&session.models.containsKey(below)&&below.getFluidState().isEmpty()?-1:UNKNOWN;
        }
        if(here.is(TestTerrainBlocks.SNOWY_GRASS_SLAB)&&here.getValue(SlabBlock.TYPE)==SlabType.BOTTOM
                &&here.getValue(TestSnowyGrassSlabBlock.SNOWY)&&!here.getValue(SlabBlock.WATERLOGGED)&&above.isAir())return -1;
        // Higher terraces, walls, trunks, mixed layers and unknown models veto shared corners.
        return UNKNOWN;
    }
    private static int coverLayers(BlockAndTintGetter level,BlockPos pos,BlockState state) {
        int nativeLayers=snowLayers(state);
        return nativeLayers!=0?nativeLayers:TestGroundSnowModels.groundPlant(state)?TestGroundSnowModels.overlayLayers(level,pos):0;
    }
    private static BlockState snowState(BlockState actual,int layers) {
        return snowLayers(actual)>0?actual:Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS,layers);
    }
    /** Finite crust class; all incident stock owners must support the same paired material path. */
    public static int crustKey(BlockAndTintGetter level,BlockPos coverPos,int layers) {
        Session session=latestSession;
        if(session==null||layers<1||layers>7
                ||!session.models.containsKey(Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS,layers))
                ||!session.models.containsKey(Blocks.GRASS_BLOCK.defaultBlockState().setValue(SnowyBlock.SNOWY,true))
                ||!session.models.containsKey(Blocks.PODZOL.defaultBlockState().setValue(SnowyBlock.SNOWY,true))
                ||!session.models.containsKey(Blocks.DIRT.defaultBlockState())
                ||!session.models.containsKey(Blocks.COARSE_DIRT.defaultBlockState())
                ||!session.models.containsKey(Blocks.ROOTED_DIRT.defaultBlockState()))return 0;
        return TestSnowCrust.forCap(level,coverPos,layers,TEST_BIOME);
    }
    /** Emits the same cap over a real plant cell, after that model cancels its native offset. */
    static boolean emitPlantFloor(QuadEmitter emitter,BlockAndTintGetter level,BlockPos pos,int layers,Predicate<@Nullable Direction> cullTest) {
        Session session=latestSession;if(session==null)return false;
        BlockState snow=Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS,layers);
        int key=key(session,level,pos,level.getBlockState(pos),true);if(key==0)return false;
        Source source=session.models.get(snow);if(source==null)return false;
        RandomSource random=SCRATCH.get().snowRandom;random.setSeed(snow.getSeed(pos));
        Template template=source.templates.get(source.original.createGeometryKey(level,pos,snow,random));if(template==null)return false;
        output(template.part(layers,key&255,true,template),emitter,level,pos,true,key&255,layers,cullTest);return true;
    }
    private static void output(Part part,QuadEmitter emitter,BlockAndTintGetter level,BlockPos pos,boolean snowOwner,int mask,int layers,Predicate<@Nullable Direction> cullTest) {
        int light=0;
        if(mask!=0)light=LightCoordsUtil.getLightCoords(level,SCRATCH.get().pos.set(pos.getX(),pos.getY()+(snowOwner?1:2),pos.getZ()));
        final int surfaceLight=light,crust=snowOwner&&mask==0?crustKey(level,pos,layers):0;
        part.mesh.forEach(q->{
            if(cullTest.test(q.cullFace()))return;
            emitter.copyFrom(q);
            if(q.tag()==SNOW_BEVEL_TAG||q.tag()==SOIL_FRINGE_TAG)emitter.shadeMode(ShadeMode.ENHANCED);
            if(crust!=0)TestSnowCrust.apply(emitter,crust,layers/8f);
            if(mask!=0&&(q.tag()==SNOW_BEVEL_TAG||q.tag()==SOIL_FRINGE_TAG))emitter.minLightmap(surfaceLight);
            emitter.emit();
        });
    }
    private record FlatKey(Object original,int surface,int crust) {}
    private static final class Scratch {
        final BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
        final int[] columns=new int[9];
        final RandomSource snowRandom=RandomSource.create(0);
    }
    private static final class Session {final Map<BlockState,Source> models=new ConcurrentHashMap<>();}
    private record Source(BlockStateModel original,Map<Object,Template> templates) {}
    private record Part(Mesh mesh,int flags) {}
    private static final class Template {
        final Mesh[] faces;
        final float height;
        final boolean flatUvAffine;
        // Native source templates are finite baked model identities, never positions or world references.
        final Map<Template,AtomicReferenceArray<Part>> variants=new ConcurrentHashMap<>();
        Template(Mesh[] faces,float height){
            this.faces=faces;this.height=height;
            boolean[] affine={true};
            for(Direction side:new Direction[]{Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST})
                faces[side.ordinal()].forEach(q->affine[0]&=affineUv(q));
            flatUvAffine=affine[0];
        }
        Template snowyBody(Material.Baked material) {
            Mesh[] changed=faces.clone();
            for(Direction face:new Direction[]{Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST}){
                var mesh=Renderer.get().mutableMesh();var e=mesh.emitter();
                faces[face.ordinal()].forEach(q->{
                    e.copyFrom(q);
                    for(int v=0;v<4;v++)e.uv(v,face.getAxis()==Direction.Axis.X?q.z(v):q.x(v),1-q.y(v));
                    e.materialBake(material,MutableQuadView.BAKE_NORMALIZED).emit();
                });changed[face.ordinal()]=mesh.immutableCopy();
            }return new Template(changed,height);
        }
        Template retexture(Material.Baked material) {
            Mesh[] changed=new Mesh[faces.length];
            for(int side=0;side<faces.length;side++){
                var mesh=Renderer.get().mutableMesh();var e=mesh.emitter();Direction face=Direction.values()[side];
                faces[side].forEach(q->{
                    e.copyFrom(q);
                    for(int v=0;v<4;v++)e.uv(v,face.getAxis()==Direction.Axis.X?q.z(v):q.x(v),face.getAxis()==Direction.Axis.Y?q.z(v):q.y(v));
                    e.materialBake(material,MutableQuadView.BAKE_NORMALIZED).emit();
                });changed[side]=mesh.immutableCopy();
            }return new Template(changed,height);
        }
        Part part(int layers,int mask,boolean snowOwner,Template snowSource) {
            var cache=variants.computeIfAbsent(snowSource,ignored->new AtomicReferenceArray<>(2048));
            int index=(layers-1)*256+mask;Part known=cache.get(index);if(known!=null)return known;
            var mutable=Renderer.get().mutableMesh();QuadEmitter emitter=mutable.emitter();
            var shape=TestSnowTerrainGeometry.shape(layers,mask);
            if(!snowOwner)faces[Direction.DOWN.ordinal()].outputTo(emitter);
            List<TestSnowTerrainGeometry.Piece> pieces=snowOwner?shape.snowOwner():shape.soilOwner();
            if(mask==0&&flatUvAffine)pieces=flatPieces(pieces);
            for(var piece:pieces){
                Template source=piece.snow()?snowSource:this;
                Direction face=direction(piece.face());Mesh prototype=source.faces[face.ordinal()];
                prototype.forEach(q->emitPiece(emitter,q,piece,layers,mask,snowOwner,source.height));
            }
            Mesh mesh=mutable.immutableCopy();Part made=new Part(mesh,new MeshQuadCollection(mesh).materialFlags());
            return cache.compareAndSet(index,null,made)?made:cache.get(index);
        }
    }
    /** Native soil/body faces keep their owner; tagged white fringe follows the cap above. */
    static boolean dynamicSnowQuad(boolean snowOwner,int tag,@Nullable Direction face){
        return face!=Direction.DOWN&&(snowOwner||tag==SOIL_FRINGE_TAG);
    }
    private static final class TerrainModel extends WrapperBlockStateModel {
        final Session session;final BlockState bakedState;final boolean snowOwner;
        TerrainModel(BlockStateModel original,Session session,BlockState state,boolean snowOwner){super(original);this.session=session;this.bakedState=state;this.snowOwner=snowOwner;}
        private @Nullable Part part(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random,int key) {
            Source source=session.models.get(bakedState);if(source==null)return null;
            Object nativeKey=wrapped.createGeometryKey(level,pos,state,random);Template own=source.templates.get(nativeKey);if(own==null)return null;
            int layers=key>>8,mask=key&255;Template snow=own;
            if(!snowOwner){
                Scratch scratch=SCRATCH.get();BlockPos.MutableBlockPos p=scratch.pos.set(pos.getX(),pos.getY()+1,pos.getZ());
                BlockState cover=snowState(level.getBlockState(p),layers);Source snowModel=session.models.get(cover);if(snowModel==null)return null;
                scratch.snowRandom.setSeed(cover.getSeed(p));Object snowKey=snowModel.original.createGeometryKey(level,p,cover,scratch.snowRandom);
                snow=snowModel.templates.get(snowKey);if(snow==null)return null;
            }
            return own.part(layers,mask,snowOwner,snow);
        }
        @Override public void emitQuads(QuadEmitter emitter,BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random,Predicate<@Nullable Direction> cullTest) {
            BlockPos dynamicOwner=snowOwner?pos:pos.above();
            boolean dynamic=SnowSurfaceClient.ownsTop(dynamicOwner);
            // Native rebuilds follow the immutable target ownership used by the cap ACK handoff.
            // The dynamic wall reaches the real underside and replaces its tagged lower white fringe.
            if(dynamic)emitter.pushTransform(quad->!dynamicSnowQuad(snowOwner,quad.tag(),quad.nominalFace())
                    ||!SnowSurfaceClient.suppressTop(dynamicOwner));
            try {
                if(state!=bakedState){wrapped.emitQuads(emitter,level,pos,state,random,cullTest);return;}
                int key=key(session,level,pos,state,snowOwner);
                if(key==0){wrapped.emitQuads(emitter,level,pos,state,random,cullTest);return;}
                Part part=part(level,pos,state,random,key);
                if(part==null){wrapped.emitQuads(emitter,level,pos,state,random,cullTest);return;}
                output(part,emitter,level,pos,snowOwner,key&255,key>>8,cullTest);
            } finally {if(dynamic)emitter.popTransform();}
        }
        @Override public @Nullable Object createGeometryKey(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random) {
            if(snowOwner&&SnowSurfaceClient.ownsTop(pos))return null;
            int key=state==bakedState?key(session,level,pos,state,snowOwner):0;
            // The external model-geometry cache must never retain positional white minimum light.
            Object original=wrapped.createGeometryKey(level,pos,state,random);
            return key==0?original:(key&255)==0&&original!=null?new FlatKey(original,key,snowOwner?crustKey(level,pos,key>>8):0):null;
        }
        @Override public int materialFlags(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random) {
            int key=state==bakedState?key(session,level,pos,state,snowOwner):0;
            if(key==0)return wrapped.materialFlags(level,pos,state,random);
            Part part=part(level,pos,state,random,key);return part==null?wrapped.materialFlags(level,pos,state,random):part.flags;
        }
        @Override public boolean hasMaterialFlag(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random,int flag){return(materialFlags(level,pos,state,random)&flag)!=0;}
    }
    /** Conservatively retains the old subdivisions for a non-affine texture mapping. */
    static boolean affineUv(QuadView quad) {
        for(int v=0;v<4;v++)if(!Float.isFinite(quad.u(v))||!Float.isFinite(quad.v(v)))return false;
        return (double)quad.u(0)+quad.u(2)==(double)quad.u(1)+quad.u(3)
                &&(double)quad.v(0)+quad.v(2)==(double)quad.v(1)+quad.v(3);
    }
    /** Only mask-zero pieces enter here; the existing top object and its012/230 diagonal are retained. */
    static List<TestSnowTerrainGeometry.Piece> flatPieces(List<TestSnowTerrainGeometry.Piece> pieces) {
        var result=new ArrayList<TestSnowTerrainGeometry.Piece>(5);
        for(var piece:pieces)if(piece.face()==0)result.add(piece);
        for(int side=1;side<=4;side++){
            TestSnowTerrainGeometry.Piece first=null,last=null;int count=0;
            for(var piece:pieces)if(piece.face()==side){if(first==null)first=piece;last=piece;count++;}
            if(count!=3||first.vertices().size()!=4||last.vertices().size()!=4)throw new IllegalStateException("Flat snow side shape");
            result.add(new TestSnowTerrainGeometry.Piece(side,List.of(first.vertices().get(0),first.vertices().get(1),
                    last.vertices().get(2),last.vertices().get(3)),first.snow()));
        }
        return List.copyOf(result);
    }
    private static boolean collect(BlockStateModel model,float height,Map<Object,Template> templates)throws ReflectiveOperationException {
        if(model instanceof SingleVariant){
            var parts=new ArrayList<BlockStateModelPart>();model.collectParts(RandomSource.create(0),parts);if(parts.size()!=1)return false;
            Template template=template(parts.getFirst(),height);if(template==null)return false;templates.put(model,template);return true;
        }
        if(model instanceof WeightedVariants){
            Field field=WeightedVariants.class.getDeclaredField("list");field.setAccessible(true);
            for(var entry:((WeightedList<?>)field.get(model)).unwrap())if(!(entry.value() instanceof BlockStateModel child)||!collect(child,height,templates))return false;
            return !templates.isEmpty();
        }
        if(model.getClass().getName().equals("com.dark.winter.client.ThinTreeModels$SpruceSnowModel")){
            Field field=WrapperBlockStateModel.class.getDeclaredField("wrapped");field.setAccessible(true);
            return collect((BlockStateModel)field.get(model),height,templates);
        }
        return false;
    }
    private static @Nullable Template template(BlockStateModelPart part,float height) {
        var mutable=Renderer.get().mutableMesh();part.emitQuads(mutable.emitter(),side->false);
        var source=mutable.immutableCopy();Mesh[] faces=new Mesh[6];boolean[] valid={true};int[] count={0},internal={0};
        source.forEach(q->{
            Direction face=q.nominalFace();
            if(nativeInternalBottom(q,height)){
                // Native one-layer snow includes this isolated inward-facing plane. Original paths
                // retain it. Only the opaque closed paired volume omits it; a flat copy would leak.
                if(++internal[0]>1)valid[0]=false;return;
            }
            boolean nativeCull=face!=null&&(q.cullFace()==face||face==Direction.UP&&q.cullFace()==null);
            if(face==null||faces[face.ordinal()]!=null||!nativeCull||q.chunkLayer()!=ChunkSectionLayer.SOLID||!rectangle(q,face,height)){valid[0]=false;return;}
            for(int v=1;v<4;v++)if(q.color(v)!=q.color(0)||q.lightmap(v)!=q.lightmap(0)){valid[0]=false;return;}
            var copy=Renderer.get().mutableMesh();copy.emitter().copyFrom(q).emit();faces[face.ordinal()]=copy.immutableCopy();count[0]++;
        });
        return valid[0]&&count[0]==6?new Template(faces,height):null;
    }
    private static boolean nativeInternalBottom(QuadView q,float height) {
        if(!near(height,.125f)||q.cullFace()!=null||q.chunkLayer()!=ChunkSectionLayer.SOLID)return false;
        float inset=.002f/16f;int corners=0;
        for(int v=0;v<4;v++){
            if(!near(q.y(v),inset))return false;
            float x=q.x(v),z=q.z(v);
            if(!(near(x,inset)||near(x,1-inset))||!(near(z,inset)||near(z,1-inset)))return false;
            corners|=1<<((near(x,1-inset)?1:0)|(near(z,1-inset)?2:0));
        }
        // The exact native element reverses X; its geometric normal is upward despite DOWN shade.
        float nx=(q.y(1)-q.y(0))*(q.z(2)-q.z(0))-(q.z(1)-q.z(0))*(q.y(2)-q.y(0));
        float ny=(q.z(1)-q.z(0))*(q.x(2)-q.x(0))-(q.x(1)-q.x(0))*(q.z(2)-q.z(0));
        float nz=(q.x(1)-q.x(0))*(q.y(2)-q.y(0))-(q.y(1)-q.y(0))*(q.x(2)-q.x(0));
        return corners==15&&ny>.9f&&Math.abs(nx)<EPS&&Math.abs(nz)<EPS;
    }
    private static boolean rectangle(QuadView q,Direction face,float height) {
        int corners=0;
        for(int v=0;v<4;v++){
            float x=q.x(v),y=q.y(v),z=q.z(v);
            boolean plane=switch(face){case UP->near(y,height);case DOWN->near(y,0);case NORTH->near(z,0);case SOUTH->near(z,1);case WEST->near(x,0);case EAST->near(x,1);};
            if(!plane)return false;
            float a=face.getAxis()==Direction.Axis.X?z:x,b=face.getAxis()==Direction.Axis.Y?z:y/height;
            if(!(near(a,0)||near(a,1))||!(near(b,0)||near(b,1)))return false;
            corners|=1<<((near(a,1)?1:0)|(near(b,1)?2:0));
        }return corners==15;
    }
    private static void emitPiece(QuadEmitter out,QuadView original,TestSnowTerrainGeometry.Piece piece,int layers,int mask,boolean snowOwner,float sourceHeight) {
        var vertices=piece.vertices();if(vertices.size()>4){for(int i=1;i<vertices.size()-1;i++)emitPolygon(out,original,List.of(vertices.getFirst(),vertices.get(i),vertices.get(i+1)),piece,layers,mask,snowOwner,sourceHeight);}
        else emitPolygon(out,original,vertices,piece,layers,mask,snowOwner,sourceHeight);
    }
    private static void emitPolygon(QuadEmitter out,QuadView original,List<TestSnowTerrainGeometry.Vertex> vertices,TestSnowTerrainGeometry.Piece piece,int layers,int mask,boolean snowOwner,float sourceHeight) {
        out.copyFrom(original);Direction face=direction(piece.face());boolean white=piece.snow();
        var a=vertices.get(0);var b=vertices.get(1);var c=vertices.get(2);
        float nx=(b.y()-a.y())*(c.z()-a.z())-(b.z()-a.z())*(c.y()-a.y()),ny=(b.z()-a.z())*(c.x()-a.x())-(b.x()-a.x())*(c.z()-a.z()),nz=(b.x()-a.x())*(c.y()-a.y())-(b.y()-a.y())*(c.x()-a.x());
        float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(length<1e-8f)return;nx/=length;ny/=length;nz/=length;
        for(int v=0;v<4;v++){var p=vertices.get(Math.min(v,vertices.size()-1));float sourceY=p.y();
            if(white){if(!snowOwner)sourceY-=1;sourceY+=dropAt(mask,p.x(),p.z());}
            float u=face.getAxis()==Direction.Axis.X?p.z():p.x(),t=face.getAxis()==Direction.Axis.Y?p.z():sourceY/sourceHeight;
            out.pos(v,p.x(),p.y(),p.z()).uv(v,uv(original,face,sourceHeight,u,t,true),uv(original,face,sourceHeight,u,t,false));
            if(white&&piece.face()==0){
                var normal=TestSnowTerrainGeometry.smoothSample(layers,mask,0,p.x(),p.z());
                out.normal(v,(float)normal.normalX(),(float)normal.normalY(),(float)normal.normalZ());
            }else if(white)out.normal(v,nx,ny,nz);
        }
        out.tag(white?(snowOwner?SNOW_BEVEL_TAG:SOIL_FRINGE_TAG):SOIL_BEVEL_TAG);
        // Only existing vertical soil planes retain their native face-occlusion test.
        if(white){out.cullFace(piece.face()==0?null:face).nominalFace(face);if(mask!=0)out.ambientOcclusion(TriState.FALSE);}
        out.emit();
    }
    private static float dropAt(int mask,float x,float z){
        return (float)TestSnowTerrainGeometry.shoulderDrop(mask,x,z);
    }
    private static float uv(QuadView q,Direction face,float height,float u,float v,boolean first){
        float result=0;for(int i=0;i<4;i++){float a=face.getAxis()==Direction.Axis.X?q.z(i):q.x(i),b=face.getAxis()==Direction.Axis.Y?q.z(i):q.y(i)/height;
            result+=(near(a,1)?u:1-u)*(near(b,1)?v:1-v)*(first?q.u(i):q.v(i));
        }return result;
    }
    private static boolean near(float a,float b){return Math.abs(a-b)<EPS;}
    private static Direction direction(int face){return switch(face){case 0->Direction.UP;case 1->Direction.NORTH;case 2->Direction.EAST;case 3->Direction.SOUTH;default->Direction.WEST;};}
}
