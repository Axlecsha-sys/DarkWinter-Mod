// Adds shallow shared snow shoulders only to native bottom snowy-grass slabs in the test biome.
package com.dark.winter.client;

import com.dark.winter.block.TestSnowyGrassSlabBlock;
import com.winterkore.engine.surface.client.SnowSurfaceClient;
import com.dark.winter.worldgen.TestBiomeCompatibility;
import com.dark.winter.worldgen.TestTerrainBlocks;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.model.MeshQuadCollection;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jspecify.annotations.Nullable;

public final class TestSnowSlabModels {
    public static final int SNOW_SLAB_CAP_TAG = 0x44575C, SNOW_SLAB_BODY_TAG = 0x44575D;
    private static final float EPS = .00001f, BASE = TestSnowSlabGeometry.BASE, CAP = TestSnowSlabGeometry.CAP;
    private static final int UNKNOWN = 99;
    private static final int[] DX = {-1,0,1,-1,0,1,-1,0,1}, DZ = {-1,-1,-1,0,0,0,1,1,1};
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static volatile Biome testBiome;
    private static boolean initialized;
    private static final ColorResolver TEST_BIOME=SnowBiomePresentation.TERRAIN_MEMBERSHIP;

    private TestSnowSlabModels() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        SnowBiomePresentation.registerTerrainResolver();
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client,level) ->
                testBiome = level.registryAccess().lookupOrThrow(Registries.BIOME).get(TestBiomeCompatibility.BIOME)
                        .map(reference -> reference.value()).orElse(null));
        ClientPlayConnectionEvents.DISCONNECT.register((listener,client) -> testBiome = null);
        ModelLoadingPlugin.register(plugin -> plugin.modifyBlockModelAfterBake().register((original,context) -> {
            if (!eligible(context.state()) || !(original instanceof SingleVariant)) return original;
            Template template = template(original);
            return template == null ? original : new SlabModel(original, context.state(), template);
        }));
    }

    private static boolean eligible(BlockState state) {
        return state.is(TestTerrainBlocks.SNOWY_GRASS_SLAB)
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM
                && state.getValue(TestSnowyGrassSlabBlock.SNOWY)
                && !state.getValue(SlabBlock.WATERLOGGED);
    }

    private static boolean soil(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.ROOTED_DIRT);
    }

    private static boolean test(BlockAndTintGetter level,BlockPos pos) {
        return testBiome != null && SnowBiomePresentation.matchesTerrainTint(level.getBlockTint(pos,TEST_BIOME));
    }

    /** Geometric context mask; unsupported native resource models still retain their wrapped fallback. */
    public static int surfaceMask(BlockAndTintGetter level,BlockPos pos) {
        return mask(level,pos,level.getBlockState(pos));
    }

    private static int mask(BlockAndTintGetter level,BlockPos pos,BlockState state) {
        if (testBiome == null || !eligible(state)) return 0;
        Scratch scratch = SCRATCH.get();
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        for (int i = 0; i < 9; i++)
            scratch.columns[i] = column(level,scratch.pos,x + DX[i],y,z + DZ[i]);
        return TestSnowSlabGeometry.cornerMask(scratch.columns);
    }

    private static int column(BlockAndTintGetter level,BlockPos.MutableBlockPos cursor,int x,int y,int z) {
        cursor.set(x,y,z);
        if (!test(level,cursor)) return UNKNOWN;
        BlockState here = level.getBlockState(cursor), above = level.getBlockState(cursor.set(x,y + 1,z));
        if (!here.getFluidState().isEmpty() || !above.isAir()) return UNKNOWN;
        BlockState below = level.getBlockState(cursor.set(x,y - 1,z));
        if (eligible(here) && soil(below) && below.getFluidState().isEmpty()) return 0;
        if (here.is(Blocks.SNOW) && here.getValue(SnowLayerBlock.LAYERS) == 1
                && soil(below) && below.getFluidState().isEmpty()) return -1;
        if (here.isAir() && eligible(below) && soil(level.getBlockState(cursor.set(x,y - 2,z)))) return -1;
        // Bare/wet/top/double slabs, objects, plants and unsupported contacts keep a flat shared corner.
        return UNKNOWN;
    }

    private static final class Scratch {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final int[] columns = new int[9];
    }
    private record Part(Mesh mesh,int flags) {}

    private static final class Template {
        final Mesh bottom;
        final Mesh[] body, cap;
        final AtomicReferenceArray<Part> variants = new AtomicReferenceArray<>(16);
        Template(Mesh bottom,Mesh[] body,Mesh[] cap) { this.bottom = bottom; this.body = body; this.cap = cap; }
        Part part(int mask) {
            int key = (mask & 0xF0) >>> 4;
            Part cached = variants.get(key);
            if (cached != null) return cached;
            var mutable = Renderer.get().mutableMesh();
            QuadEmitter emitter = mutable.emitter();
            bottom.outputTo(emitter);
            for (var piece : TestSnowSlabGeometry.shape(mask)) {
                Mesh prototype = piece.snow() ? cap[piece.face()] : body[piece.face()];
                prototype.forEach(original -> emitPiece(emitter,original,piece,mask));
            }
            Mesh mesh = mutable.immutableCopy();
            Part made = new Part(mesh,new MeshQuadCollection(mesh).materialFlags());
            return variants.compareAndSet(key,null,made) ? made : variants.get(key);
        }
    }

    private static final class SlabModel extends WrapperBlockStateModel {
        final BlockState bakedState;
        final Template template;
        SlabModel(BlockStateModel original,BlockState state,Template template) {
            super(original); this.bakedState = state; this.template = template;
        }
        @Override public void emitQuads(QuadEmitter emitter,BlockAndTintGetter level,BlockPos pos,BlockState state,
                                       RandomSource random,Predicate<@Nullable Direction> cullTest) {
            boolean dynamic=SnowSurfaceClient.ownsTop(pos);
            if(dynamic)emitter.pushTransform(quad->{
                boolean cap=quad.tag()==SNOW_SLAB_CAP_TAG;
                if(!cap){
                    boolean atOrAbove=true,above=false;
                    for(int v=0;v<4;v++){atOrAbove&=quad.y(v)>=BASE-EPS;above|=quad.y(v)>BASE+EPS;}
                    cap=atOrAbove&&above;
                }
                // Keep every soil/body face, including the original top under a compacted cap.
                return !cap||!SnowSurfaceClient.suppressTop(pos);
            });
            try {
            int mask = state == bakedState ? mask(level,pos,state) : 0;
            if (mask == 0) { wrapped.emitQuads(emitter,level,pos,state,random,cullTest); return; }
            Part part = template.part(mask);
            // World light belongs to this emission, never the finite immutable geometry cache.
            int light = LightCoordsUtil.getLightCoords(level,SCRATCH.get().pos.set(pos.getX(),pos.getY() + 1,pos.getZ()));
            part.mesh.forEach(quad -> {
                if (cullTest.test(quad.cullFace())) return;
                emitter.copyFrom(quad);
                if (quad.tag() == SNOW_SLAB_CAP_TAG) emitter.shadeMode(ShadeMode.ENHANCED).minLightmap(light);
                emitter.emit();
            });
            }finally{if(dynamic)emitter.popTransform();}
        }
        @Override public @Nullable Object createGeometryKey(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random) {
            if(SnowSurfaceClient.ownsTop(pos))return null;
            return state == bakedState && mask(level,pos,state) != 0 ? null : wrapped.createGeometryKey(level,pos,state,random);
        }
        @Override public int materialFlags(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random) {
            int mask = state == bakedState ? mask(level,pos,state) : 0;
            return mask == 0 ? wrapped.materialFlags(level,pos,state,random) : template.part(mask).flags;
        }
        @Override public boolean hasMaterialFlag(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random,int flag) {
            return (materialFlags(level,pos,state,random) & flag) != 0;
        }
    }

    private static @Nullable Template template(BlockStateModel model) {
        var parts = new ArrayList<BlockStateModelPart>();
        model.collectParts(RandomSource.create(0),parts);
        if (parts.size() != 1) return null;
        var mutable = Renderer.get().mutableMesh();
        parts.getFirst().emitQuads(mutable.emitter(),side -> false);
        Mesh[] body = new Mesh[5], cap = new Mesh[5], bottom = new Mesh[1];
        int[] count = {0,0}; boolean[] valid = {true};
        mutable.forEach(q -> {
            count[0]++;
            Direction face = q.nominalFace();
            if (face == null || q.chunkLayer() != ChunkSectionLayer.SOLID || q.emissive()) { valid[0] = false; return; }
            for (int v = 1; v < 4; v++)
                if (q.color(v) != q.color(0) || q.lightmap(v) != q.lightmap(0)) valid[0] = false;
            if (face == Direction.DOWN && rectangle(q,face,0,0) && q.cullFace() == Direction.DOWN && bottom[0] == null) bottom[0] = copy(q);
            else if (face == Direction.UP && rectangle(q,face,BASE,BASE) && q.tintIndex() == 0) count[1]++;
            else if (face == Direction.UP && rectangle(q,face,BASE + CAP,BASE + CAP) && cap[0] == null && q.cullFace() == null) cap[0] = copy(q);
            else if (face.getAxis().isHorizontal() && q.cullFace() == face) {
                int index = faceIndex(face);
                if (rectangle(q,face,0,BASE) && body[index] == null) body[index] = copy(q);
                else if (rectangle(q,face,BASE,BASE + CAP) && cap[index] == null) cap[index] = copy(q);
                else valid[0] = false;
            } else valid[0] = false;
        });
        if (!valid[0] || count[0] != 11 || count[1] != 1 || bottom[0] == null || cap[0] == null) return null;
        for (int i = 1; i < 5; i++) if (body[i] == null || cap[i] == null) return null;
        return new Template(bottom[0],body,cap);
    }

    private static Mesh copy(QuadView quad) {
        var mesh = Renderer.get().mutableMesh();
        mesh.emitter().copyFrom(quad).emit();
        return mesh.immutableCopy();
    }

    private static boolean rectangle(QuadView quad,Direction face,float low,float high) {
        int corners = 0;
        for (int v = 0; v < 4; v++) {
            float x = quad.x(v), y = quad.y(v), z = quad.z(v);
            boolean plane = switch (face) {
                case UP, DOWN -> near(y,low);
                case NORTH -> near(z,0); case SOUTH -> near(z,1);
                case EAST -> near(x,1); case WEST -> near(x,0);
            };
            float a = face.getAxis() == Direction.Axis.X ? z : x;
            float b = face.getAxis() == Direction.Axis.Y ? z : (y - low) / (high - low);
            if (!plane || !(near(a,0) || near(a,1)) || !(near(b,0) || near(b,1))) return false;
            corners |= 1 << ((near(a,1) ? 1 : 0) | (near(b,1) ? 2 : 0));
        }
        return corners == 15;
    }

    private static void emitPiece(QuadEmitter emitter,QuadView original,TestSnowTerrainGeometry.Piece piece,int mask) {
        var vertices = piece.vertices();
        if (vertices.size() > 4) {
            for (int i = 1; i < vertices.size() - 1; i++)
                emitPolygon(emitter,original,List.of(vertices.getFirst(),vertices.get(i),vertices.get(i + 1)),piece,mask);
        } else emitPolygon(emitter,original,vertices,piece,mask);
    }

    private static void emitPolygon(QuadEmitter out,QuadView original,List<TestSnowTerrainGeometry.Vertex> vertices,
                                    TestSnowTerrainGeometry.Piece piece,int mask) {
        var a = vertices.get(0); var b = vertices.get(1); var c = vertices.get(2);
        float nx = (b.y()-a.y())*(c.z()-a.z()) - (b.z()-a.z())*(c.y()-a.y());
        float ny = (b.z()-a.z())*(c.x()-a.x()) - (b.x()-a.x())*(c.z()-a.z());
        float nz = (b.x()-a.x())*(c.y()-a.y()) - (b.y()-a.y())*(c.x()-a.x());
        float length = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
        if (length < 1e-8f) return;
        nx /= length; ny /= length; nz /= length;
        out.copyFrom(original);
        Direction face = direction(piece.face());
        for (int v = 0; v < 4; v++) {
            var p = vertices.get(Math.min(v,vertices.size()-1));
            float u = face.getAxis() == Direction.Axis.X ? p.z() : p.x();
            float t = face.getAxis() == Direction.Axis.Y ? p.z()
                    : piece.snow() ? (p.y() - BASE + edgeDrop(mask,p.x(),p.z())) / CAP : p.y() / BASE;
            out.pos(v,p.x(),p.y(),p.z()).uv(v,uv(original,face,piece.snow() ? BASE : 0,piece.snow() ? CAP : BASE,u,t,true),
                    uv(original,face,piece.snow() ? BASE : 0,piece.snow() ? CAP : BASE,u,t,false));
            if (piece.snow() && piece.face()==0) {
                var normal=TestSnowTerrainGeometry.smoothSample(1,mask,0,p.x(),p.z());
                out.normal(v,(float)normal.normalX(),(float)normal.normalY(),(float)normal.normalZ());
            } else if (piece.snow()) out.normal(v,nx,ny,nz);
        }
        out.tag(piece.snow() ? SNOW_SLAB_CAP_TAG : SNOW_SLAB_BODY_TAG);
        if (piece.snow()) {
            // Native slab occlusion ends at y=.5; it cannot cull the visual cap above that plane.
            out.cullFace(null).nominalFace(face).ambientOcclusion(TriState.FALSE);
        }
        out.emit();
    }

    private static float edgeDrop(int mask,float x,float z) {
        if (near(z,0)) return edge(mask,0,0,3,0,x);
        if (near(z,1)) return edge(mask,0,3,3,3,x);
        if (near(x,0)) return edge(mask,0,0,0,3,z);
        if (near(x,1)) return edge(mask,3,0,3,3,z);
        return 0;
    }
    private static float edge(int mask,int x0,int z0,int x1,int z1,float t) {
        float first = TestSnowTerrainGeometry.dropAtGrid(mask,x0,z0);
        return first + (TestSnowTerrainGeometry.dropAtGrid(mask,x1,z1) - first) * t;
    }
    private static float uv(QuadView q,Direction face,float low,float height,float u,float v,boolean first) {
        float value = 0;
        for (int i = 0; i < 4; i++) {
            float a = face.getAxis() == Direction.Axis.X ? q.z(i) : q.x(i);
            float b = face.getAxis() == Direction.Axis.Y ? q.z(i) : (q.y(i) - low) / height;
            value += (near(a,1) ? u : 1-u) * (near(b,1) ? v : 1-v) * (first ? q.u(i) : q.v(i));
        }
        return value;
    }
    private static boolean near(float a,float b) { return Math.abs(a-b) < EPS; }
    private static int faceIndex(Direction face) {
        return switch (face) { case NORTH -> 1; case EAST -> 2; case SOUTH -> 3; case WEST -> 4; default -> 0; };
    }
    private static Direction direction(int face) {
        return switch (face) { case 0 -> Direction.UP; case 1 -> Direction.NORTH; case 2 -> Direction.EAST; case 3 -> Direction.SOUTH; default -> Direction.WEST; };
    }
}
