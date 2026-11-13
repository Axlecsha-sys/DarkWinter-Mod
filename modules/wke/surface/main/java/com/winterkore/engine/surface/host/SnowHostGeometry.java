// Shared observation of the existing authored snowy terrain. No client models or Minecraft world edits.
package com.winterkore.engine.surface.host;

import com.dark.winter.block.SnowyPlantFloorRestore;
import com.dark.winter.block.TestSnowyGrassSlabBlock;
import com.dark.winter.worldgen.TestBiomeCompatibility;
import com.dark.winter.worldgen.TestSpruceBlocks;
import com.dark.winter.worldgen.TestTerrainBlocks;
import com.winterkore.engine.surface.geometry.SnowCrustRelief;
import com.winterkore.engine.surface.geometry.SnowContactRelief;
import com.winterkore.engine.surface.geometry.SnowTerrainGeometry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Masks reproduce the mod's current chamfers and crust. Position is the owner of the white cap. */
public final class SnowHostGeometry {
    private static final int UNKNOWN=99;
    private static final int[] DX={-1,0,1,-1,0,1,-1,0,1},DZ={-1,-1,-1,0,0,0,1,1,1};
    private static final int[][] CORNERS={{0,1,3,4},{1,2,4,5},{4,5,7,8},{3,4,6,7}};
    private static final int[] EDGES={1,5,7,3};
    private SnowHostGeometry(){}
    public static final int MAX_PATCHES=16;
    /** Exact exposed horizontal support, in owner-local coordinates. Empty space is never filled. */
    public record Patch(float minX,float minZ,float maxX,float maxZ,float top) {
        public Patch {
            if(!Float.isFinite(minX+minZ+maxX+maxZ+top)||minX<0||minZ<0||maxX>1||maxZ>1
                    ||minX>=maxX||minZ>=maxZ||top<0||top>1)throw new IllegalArgumentException("Snow support patch");
        }
        public boolean contains(double x,double z){return x>=minX-1e-7&&x<=maxX+1e-7&&z>=minZ-1e-7&&z<=maxZ+1e-7;}
    }
    public record Cell(BlockPos pos,int layers,int mask,int crust,double baseOffset,List<Patch> patches) {
        public Cell(BlockPos pos,int layers,int mask,int crust,double baseOffset){this(pos,layers,mask,crust,baseOffset,List.of());}
        public Cell {
            pos=pos.immutable();patches=List.copyOf(patches);
            if(patches.size()>MAX_PATCHES||!Double.isFinite(baseOffset))throw new IllegalArgumentException("Snow support geometry");
        }
        public boolean generic(){return !patches.isEmpty();}
        public boolean contains(double x,double z){return !generic()||Double.isFinite(supportTop(x-pos.getX(),z-pos.getZ()));}
        public double supportTop(double x,double z){
            double top=Double.NEGATIVE_INFINITY;
            for(Patch p:patches)if(p.contains(x,z))top=Math.max(top,p.top());
            return top==Double.NEGATIVE_INFINITY?Double.NaN:top;
        }
        public double nominalHeight(){
            if(!generic())return pos.getY()+baseOffset+Math.max(1,layers)/8.0;
            double top=0;for(Patch p:patches)top=Math.max(top,p.top());return pos.getY()+top;
        }
    }
    public static boolean soil(BlockState s){return s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.DIRT)||s.is(Blocks.COARSE_DIRT)||s.is(Blocks.PODZOL)||s.is(Blocks.ROOTED_DIRT);}
    public static boolean groundPlant(BlockState state) {
        return state.is(TestSpruceBlocks.BOUGH)||state.is(Blocks.FERN)||state.is(Blocks.SHORT_GRASS)
                ||state.is(Blocks.LARGE_FERN)&&state.getValue(DoublePlantBlock.HALF)==DoubleBlockHalf.LOWER
                ||SnowyPlantFloorRestore.floorPart(state);
    }
    public static boolean snowySlab(BlockState s){return s.is(TestTerrainBlocks.SNOWY_GRASS_SLAB)
            &&s.getValue(SlabBlock.TYPE)==SlabType.BOTTOM&&s.getValue(TestSnowyGrassSlabBlock.SNOWY)&&!s.getValue(SlabBlock.WATERLOGGED);}
    public static int nativeLayers(BlockState s){return s.is(Blocks.SNOW)?s.getValue(SnowLayerBlock.LAYERS):s.is(Blocks.SNOW_BLOCK)?8:0;}
    private static boolean test(Level level,BlockPos pos){return level.hasChunkAt(pos)&&level.getBiome(pos).is(TestBiomeCompatibility.BIOME);}
    private static int layers(Level level,BlockPos pos,BlockState s) {
        int nativeSnow=nativeLayers(s);if(nativeSnow!=0)return nativeSnow;
        if(!groundPlant(s)||!s.getFluidState().isEmpty())return 0;
        BlockPos below=pos.below();if(!level.getBlockState(below).isFaceSturdy(level,below,Direction.UP))return 0;
        int result=9;
        for(Direction d:new Direction[]{Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST}) {
            if(!level.hasChunkAt(pos.relative(d)))continue;
            int other=nativeLayers(level.getBlockState(pos.relative(d)));if(other>0)result=Math.min(result,other);
        }
        return result==9&&soil(level.getBlockState(below))?1:result==9?0:result;
    }
    public static Cell describe(Level level,BlockPos pos) {return describe(level,pos,false);}
    /** Soil collision can change only for a nonzero authored shoulder mask.
     * Observe that mask freshly, without building unused crust or zero-layer patches. */
    public static Cell describeForSoilCollision(Level level,BlockPos pos) {return describe(level,pos,true);}
    private static Cell describe(Level level,BlockPos pos,boolean soilCollision) {
        if(level.isOutsideBuildHeight(pos)||!level.hasChunkAt(pos))return null;
        BlockState state=level.getBlockState(pos);
        // These exact vanilla full cubes never own an internal top; their cover belongs to the cell above.
        // Skip buried soil before biome/shape/patch observations during bounded multi-level discovery.
        if(soil(state)||!test(level,pos))return null;
        if(!state.getFluidState().isEmpty())return null;
        if(snowySlab(state))return new Cell(pos,1,slabMask(level,pos),0,.5);
        int count=layers(level,pos,state);
        if(count==0) {
            if(soilCollision)return null;
            // Full top faces belong to the space above; partial tops belong to their actual block.
            // Keeping this ownership stable permits roof and ground caps in the same X/Z column.
            List<Patch> patches=genericPatches(level,pos);
            return patches.isEmpty()?null:new Cell(pos,0,0,0,0,patches);
        }
        BlockPos support=pos.below();int mask=0;
        if(soil(level.getBlockState(support))&&level.getBlockState(support).getFluidState().isEmpty()) {
            int[] columns=new int[9];int y=support.getY();
            for(int i=0;i<9;i++)columns[i]=column(level,pos.getX()+DX[i],y,pos.getZ()+DZ[i],count);
            if(columns[4]==0) {
                for(int side=0;side<4;side++)if(columns[EDGES[side]]==-1)mask|=1<<side;
                for(int corner=0;corner<4;corner++) {
                    boolean known=true,lower=false;
                    for(int i:CORNERS[corner]){known&=columns[i]!=UNKNOWN;lower|=columns[i]==-1;}
                    if(known&&lower)mask|=1<<(corner+4);
                }
            }
        }
        if(soilCollision&&mask==0)return null;
        return new Cell(pos,count,mask,mask==0?crust(level,pos,count):0,0);
    }
    /** Include physical furniture owners shifted away from their logical storage cell. */
    private static List<Patch> genericPatches(Level level,BlockPos physical){
        var owners=new java.util.LinkedHashSet<BlockPos>();
        owners.add(physical);owners.add(physical.below());
        owners.addAll(com.dark.winter.placement.AnchorRuntime.logicalOwners(level,physical));
        owners.addAll(com.dark.winter.placement.AnchorRuntime.logicalOwners(level,physical.below()));
        var boxes=new ArrayList<AABB>();
        var supports=new ArrayList<AABB>();
        for(BlockPos logical:owners){
            if(!level.hasChunkAt(logical))continue;
            BlockState state=level.getBlockState(logical);
            // A placed item's selection handle is not its rendered physical surface.
            if(state.getBlock() instanceof com.dark.winter.placement.item.PlacedItemBlock
                    ||!state.getFluidState().isEmpty()||state.is(BlockTags.LEAVES)||groundPlant(state)||nativeLayers(state)>0||snowySlab(state))continue;
            for(AABB box:state.getShape(level,logical,CollisionContext.empty()).toAabbs()){
                AABB actual=box.move(logical.getX()-physical.getX(),logical.getY()-physical.getY(),logical.getZ()-physical.getZ());
                if(actual.maxX>0&&actual.minX<1&&actual.maxZ>0&&actual.minZ<1){
                    boxes.add(actual);
                    // A tapering living trunk is an occluder, never a stack of flat snow shelves.
                    // Shrub selection boxes likewise describe picking, not the authored twig canopy.
                    if(!canopyOwnsSnow(state))supports.add(actual);
                }
            }
            if(boxes.size()>128)return List.of();
        }
        // Keep the surrounding ground as well as internal furniture/slab tops; occupied legs are clipped below.
        var patches=new ArrayList<Patch>(supportPatches(supports,boxes,false));
        patches.addAll(supportPatches(supports,boxes,true));
        return patches.size()>MAX_PATCHES?List.of():List.copyOf(patches);
    }
    /** Vegetation has authored canopy snow. Its coarse picking/wood boxes cannot grow a shell. */
    public static boolean canopyOwnsSnow(BlockState state){
        if(state.is(TestSpruceBlocks.LOG))return state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)==Direction.Axis.Y;
        if(state.getBlock() instanceof com.dark.winter.block.FurnitureBlock furniture)
            return furniture.definition().id().equals("snowy_shrub");
        return false;
    }
    /** Upper envelope of actual shape boxes, clipped to this owner; no bounding-box fill over holes. */
    private static List<Patch> supportPatches(List<AABB> boxes,boolean boundaryTop) {return supportPatches(boxes,boxes,boundaryTop);}
    private static List<Patch> supportPatches(List<AABB> supports,List<AABB> boxes,boolean boundaryTop) {
        if(supports.isEmpty()||boxes.size()>128)return List.of();
        var result=new ArrayList<Patch>();
        for(AABB box:supports){
            double top=box.maxY;
            if(boundaryTop?Math.abs(top)>1e-6:top<=1e-6||top>=1-1e-6)continue;
            double x0=Math.clamp(box.minX,0,1),x1=Math.clamp(box.maxX,0,1),z0=Math.clamp(box.minZ,0,1),z1=Math.clamp(box.maxZ,0,1);
            if(x1-x0<1e-5||z1-z0<1e-5)continue;
            var exposed=new ArrayList<double[]>();exposed.add(new double[]{x0,z0,x1,z1});
            for(AABB cover:boxes){
                if(cover.minY>top+1e-6||cover.maxY<=top+1e-6)continue;
                var next=new ArrayList<double[]>();
                for(double[] r:exposed){
                    double ax=Math.max(r[0],cover.minX),az=Math.max(r[1],cover.minZ),bx=Math.min(r[2],cover.maxX),bz=Math.min(r[3],cover.maxZ);
                    if(ax>=bx-1e-7||az>=bz-1e-7){next.add(r);continue;}
                    if(r[0]<ax)next.add(new double[]{r[0],r[1],ax,r[3]});
                    if(bx<r[2])next.add(new double[]{bx,r[1],r[2],r[3]});
                    if(r[1]<az)next.add(new double[]{ax,r[1],bx,az});
                    if(bz<r[3])next.add(new double[]{ax,bz,bx,r[3]});
                }
                exposed=next;if(exposed.isEmpty())break;
                if(exposed.size()>MAX_PATCHES)return List.of();
            }
            for(double[] r:exposed){
                if((r[2]-r[0])*(r[3]-r[1])<1.0/4096)continue;
                Patch patch=new Patch((float)r[0],(float)r[1],(float)r[2],(float)r[3],boundaryTop?0:(float)top);
                if(!result.contains(patch))result.add(patch);
                if(result.size()>128)return List.of();
            }
        }
        // Exact coplanar rectangles merge without filling holes or crossing a height discontinuity.
        boolean changed;
        do{
            changed=false;
            outer:for(int i=0;i<result.size();i++)for(int j=i+1;j<result.size();j++){
                Patch a=result.get(i),b=result.get(j);if(Math.abs(a.top()-b.top())>1e-6)continue;
                Patch joined=null;
                if(Math.abs(a.minZ()-b.minZ())<1e-6&&Math.abs(a.maxZ()-b.maxZ())<1e-6
                        &&a.minX()<=b.maxX()+1e-6&&b.minX()<=a.maxX()+1e-6)
                    joined=new Patch(Math.min(a.minX(),b.minX()),a.minZ(),Math.max(a.maxX(),b.maxX()),a.maxZ(),a.top());
                else if(Math.abs(a.minX()-b.minX())<1e-6&&Math.abs(a.maxX()-b.maxX())<1e-6
                        &&a.minZ()<=b.maxZ()+1e-6&&b.minZ()<=a.maxZ()+1e-6)
                    joined=new Patch(a.minX(),Math.min(a.minZ(),b.minZ()),a.maxX(),Math.max(a.maxZ(),b.maxZ()),a.top());
                if(joined!=null){result.set(i,joined);result.remove(j);changed=true;break outer;}
            }
        }while(changed);
        return result.size()>MAX_PATCHES?List.of():List.copyOf(result);
    }
    private static int column(Level level,int x,int y,int z,int count) {
        BlockPos p=new BlockPos(x,y,z),above=p.above();
        if(!test(level,above))return UNKNOWN;
        BlockState here=level.getBlockState(p),cover=level.getBlockState(above);
        if(!here.getFluidState().isEmpty()||!cover.getFluidState().isEmpty())return UNKNOWN;
        if(soil(here)&&layers(level,above,cover)==count)
            return groundPlant(cover)||level.getBlockState(above.above()).isAir()?0:UNKNOWN;
        if(layers(level,p,here)==count&&(cover.isAir()||groundPlant(here))) {
            BlockState below=level.getBlockState(p.below());
            return soil(below)&&below.getFluidState().isEmpty()?-1:UNKNOWN;
        }
        return snowySlab(here)&&cover.isAir()?-1:UNKNOWN;
    }
    private static int slabMask(Level level,BlockPos pos) {
        int[] columns=new int[9];
        for(int i=0;i<9;i++) {
            BlockPos p=pos.offset(DX[i],0,DZ[i]);
            if(!level.hasChunkAt(p)){columns[i]=UNKNOWN;continue;}
            BlockState s=level.getBlockState(p),above=level.getBlockState(p.above()),below=level.getBlockState(p.below());
            int value=UNKNOWN;
            if(test(level,p)&&s.getFluidState().isEmpty()&&above.isAir()) {
                if(snowySlab(s)&&soil(below)&&below.getFluidState().isEmpty())value=0;
                else if(s.is(Blocks.SNOW)&&s.getValue(SnowLayerBlock.LAYERS)==1&&soil(below)&&below.getFluidState().isEmpty())value=-1;
                else if(s.isAir()&&snowySlab(below)&&soil(level.getBlockState(p.below(2))))value=-1;
            }
            columns[i]=value;
        }
        if(columns[4]!=0)return 0;int mask=0;
        for(int corner=0;corner<4;corner++) {
            boolean known=true,lower=false;
            for(int i:CORNERS[corner]){known&=columns[i]==0||columns[i]==-1;lower|=columns[i]==-1;}
            if(known&&lower)mask|=1<<(corner+4);
        }
        return mask;
    }
    private static int crust(Level level,BlockPos pos,int count) {
        if(count<1||count>7)return 0;long columns=0;
        for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++) {
            BlockPos p=pos.offset(dx,0,dz);if(!level.hasChunkAt(p))continue;
            BlockState cover=level.getBlockState(p),support=level.getBlockState(p.below());
            if(!test(level,p)||!cover.getFluidState().isEmpty()||layers(level,p,cover)!=count||!soil(support)||!support.getFluidState().isEmpty())continue;
            if((support.is(Blocks.GRASS_BLOCK)||support.is(Blocks.PODZOL))&&!support.getValue(SnowyBlock.SNOWY))continue;
            if(!groundPlant(cover)&&!level.getBlockState(p.above()).isAir())continue;
            columns|=1L<<((dz+2)*5+dx+2);
        }
        return SnowCrustRelief.pattern(pos.getX(),pos.getZ(),columns);
    }
    public static Cell find(Level level,double x,double estimatedY,double z) {
        if(!Double.isFinite(x)||!Double.isFinite(estimatedY)||!Double.isFinite(z))return null;
        int bx=(int)Math.floor(x),bz=(int)Math.floor(z),y=(int)Math.floor(estimatedY+.1);
        Cell nearest=null;double distance=Double.POSITIVE_INFINITY;
        for(int dy=2;dy>=-3;dy--) {
            Cell c=describe(level,new BlockPos(bx,y+dy,bz));if(c==null||!c.contains(x,z))continue;
            double actual=height(c,x,z,0);
            double d=Math.abs(actual-estimatedY);
            if(d<distance&&d<=1.75){nearest=c;distance=d;}
        }
        return nearest;
    }
    /** Cheap compatibility observation: masks and crust are irrelevant to weather corner sharing. */
    private static double nominalHeight(Level level,BlockPos pos) {
        if(level.isOutsideBuildHeight(pos)||!test(level,pos))return Double.NaN;
        BlockState state=level.getBlockState(pos);if(!state.getFluidState().isEmpty())return Double.NaN;
        if(snowySlab(state))return pos.getY()+.625;
        int count=layers(level,pos,state);
        if(count>0)return pos.getY()+count/8.0;
        Cell generic=describe(level,pos);return generic==null?Double.NaN:generic.nominalHeight();
    }
    /**
     * One atomic world observation, shared by all owners in a client build snapshot.
     * Primitive keys and sample values are stored densely; the open-addressed index
     * contains row numbers only. Never retain this sampler across ticks/world edits.
     */
    public static final class CornerSampler {
        private static final int NOMINAL=1,DEPTH=2,GEOMETRY=4;
        private static final int[] CORNER_X={0,1,1,0},CORNER_Z={0,0,1,1};
        private final ToDoubleFunction<BlockPos> nominalReader,depthReader;
        private final java.util.function.Function<BlockPos,Cell> geometryReader;
        private final BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
        private long[] keys=new long[256];
        private double[] nominals=new double[256],depths=new double[256];
        private byte[] observed=new byte[256];
        private Cell[] geometry;
        private int[] slots=new int[512];
        private int size;

        public CornerSampler(Level level,ToDoubleFunction<BlockPos> depths) {
            this(p->nominalHeight(level,p),p->describe(level,p),depths);
        }
        /** Reader injection keeps exact cached/cold equivalence independently testable. */
        private CornerSampler(ToDoubleFunction<BlockPos> nominal,
                java.util.function.Function<BlockPos,Cell> geometry,ToDoubleFunction<BlockPos> depths) {
            nominalReader=java.util.Objects.requireNonNull(nominal);
            geometryReader=java.util.Objects.requireNonNull(geometry);
            depthReader=java.util.Objects.requireNonNull(depths);
        }
        private static int hash(long key) {
            key=(key^(key>>>30))*0xbf58476d1ce4e5b9L;
            key=(key^(key>>>27))*0x94d049bb133111ebL;
            return (int)(key^(key>>>31));
        }
        private int row(BlockPos pos) {
            long key=pos.asLong();int slot=hash(key)&(slots.length-1);
            while(slots[slot]!=0) {
                int row=slots[slot]-1;if(keys[row]==key)return row;
                slot=(slot+1)&(slots.length-1);
            }
            if(size==keys.length) {
                int length=Math.multiplyExact(keys.length,2);
                keys=java.util.Arrays.copyOf(keys,length);
                nominals=java.util.Arrays.copyOf(nominals,length);
                depths=java.util.Arrays.copyOf(depths,length);
                observed=java.util.Arrays.copyOf(observed,length);
                if(geometry!=null)geometry=java.util.Arrays.copyOf(geometry,length);
            }
            if((size+1)*2>slots.length) {
                slots=new int[Math.multiplyExact(slots.length,2)];
                for(int i=0;i<size;i++) {
                    int at=hash(keys[i])&(slots.length-1);
                    while(slots[at]!=0)at=(at+1)&(slots.length-1);
                    slots[at]=i+1;
                }
                slot=hash(key)&(slots.length-1);
                while(slots[slot]!=0)slot=(slot+1)&(slots.length-1);
            }
            int row=size++;keys[row]=key;slots[slot]=row+1;return row;
        }
        private double nominal(int row,BlockPos pos) {
            if((observed[row]&NOMINAL)==0) {
                nominals[row]=nominalReader.applyAsDouble(pos);observed[row]|=NOMINAL;
            }
            return nominals[row];
        }
        private double depth(int row,BlockPos pos) {
            if((observed[row]&DEPTH)==0) {
                depths[row]=depthReader.applyAsDouble(pos);observed[row]|=DEPTH;
            }
            return depths[row];
        }
        private Cell geometry(int row,BlockPos pos) {
            if((observed[row]&GEOMETRY)==0) {
                if(geometry==null)geometry=new Cell[keys.length];
                geometry[row]=geometryReader.apply(pos);observed[row]|=GEOMETRY;
            }
            return geometry[row];
        }
        public double[] corners(Cell cell) {
            double[] values=new double[4];
            int x=cell.pos().getX(),y=cell.pos().getY(),z=cell.pos().getZ();
            double own=depth(row(cell.pos()),cell.pos()),height=cell.nominalHeight();
            for(int corner=0;corner<4;corner++) {
                int wx=x+CORNER_X[corner],wz=z+CORNER_Z[corner];
                double sum=0;int count=0;boolean incomplete=false;
                for(int dz=-1;dz<=0;dz++)for(int dx=-1;dx<=0;dx++) {
                    int px=wx+dx,pz=wz+dz;cursor.set(px,y,pz);
                    int row=row(cursor);
                    if(cell.generic()) {
                        Cell neighbour=geometry(row,cursor);if(neighbour==null)continue;
                        double a=height(cell,Math.clamp(wx,x,x+1.0),Math.clamp(wz,z,z+1.0),0);
                        double b=height(neighbour,Math.clamp(wx,px,px+1.0),Math.clamp(wz,pz,pz+1.0),0);
                        if(!Double.isFinite(a+b)||Math.abs(a-b)>.125)continue;
                    } else {
                        double nominal=nominal(row,cursor);
                        if(!Double.isFinite(nominal)||Math.abs(nominal-height)>1.125)continue;
                    }
                    double value=depth(row,cursor);
                    if(Double.isFinite(value)){sum+=Math.clamp(value,-1500,1500);count++;}
                    else incomplete=true;
                }
                values[corner]=incomplete||count==0?(cell.generic()&&Double.isFinite(own)?Math.clamp(own,-1500,1500):0):sum/count;
            }
            return values;
        }
    }

    /** Same four global corner averages on either side of a block/chunk boundary. */
    public static double[] fineCornersMillimeters(Level level,Cell cell,ToDoubleFunction<BlockPos> depths) {
        double[] values=new double[4];int[] cx={0,1,1,0},cz={0,0,1,1};
        for(int corner=0;corner<4;corner++) {
            int wx=cell.pos().getX()+cx[corner],wz=cell.pos().getZ()+cz[corner];double sum=0;int count=0;boolean incomplete=false;
            for(int dz=-1;dz<=0;dz++)for(int dx=-1;dx<=0;dx++) {
                BlockPos p=new BlockPos(wx+dx,cell.pos().getY(),wz+dz);
                double nominal=nominalHeight(level,p);
                if(cell.generic()){
                    Cell neighbour=describe(level,p);
                    if(neighbour==null)continue;
                    double a=height(cell,Math.clamp(wx,cell.pos().getX(),cell.pos().getX()+1.0),Math.clamp(wz,cell.pos().getZ(),cell.pos().getZ()+1.0),0);
                    double b=height(neighbour,Math.clamp(wx,p.getX(),p.getX()+1.0),Math.clamp(wz,p.getZ(),p.getZ()+1.0),0);
                    if(!Double.isFinite(a+b)||Math.abs(a-b)>.125)continue;
                }else if(!Double.isFinite(nominal)||Math.abs(nominal-cell.nominalHeight())>1.125)continue;
                double value=depths.applyAsDouble(p);
                if(Double.isFinite(value)){sum+=Math.clamp(value,-1500,1500);count++;}else incomplete=true;
            }
            double own=depths.applyAsDouble(cell.pos());
            values[corner]=incomplete||count==0?(cell.generic()&&Double.isFinite(own)?Math.clamp(own,-1500,1500):0):sum/count;
        }
        return values;
    }
    public static double displacementMillimeters(Level level,Cell cell,double x,double z,ToDoubleFunction<BlockPos> depths) {
        double[] c=fineCornersMillimeters(level,cell,depths);double lx=Math.clamp(x-cell.pos().getX(),0,1),lz=Math.clamp(z-cell.pos().getZ(),0,1);
        return (c[0]*(1-lx)+c[1]*lx)*(1-lz)+(c[3]*(1-lx)+c[2]*lx)*lz;
    }
    /** One owner-height test for both the visual mesh and physics: deep covers retain new prints. */
    public static boolean contactMatches(Cell cell,double[] fineCornersMillimeters,double markX,double markY,double markZ,double width,double length) {
        if(!Double.isFinite(markX)||!Double.isFinite(markY)||!Double.isFinite(markZ)||!Double.isFinite(width)||!Double.isFinite(length)||width<=0||length<=0)return false;
        return contactMatches(cell,fineCornersMillimeters,markX,markY,markZ,width,length,Integer.MIN_VALUE);
    }
    public static boolean contactMatches(Cell cell,double[] fineCornersMillimeters,double markX,double markY,double markZ,double width,double length,int ownerY) {
        if(ownerY!=Integer.MIN_VALUE&&ownerY!=cell.pos().getY())return false;
        double radius=SnowContactRelief.influenceRadiusMeters(width,length,1500);
        int x=cell.pos().getX(),z=cell.pos().getZ();
        if(markX+radius<x||markX-radius>x+1||markZ+radius<z||markZ-radius>z+1)return false;
        double sampleX=Math.clamp(markX,x,x+1.0),sampleZ=Math.clamp(markZ,z,z+1.0);
        if(cell.generic()){
            double nearest=Double.POSITIVE_INFINITY;
            for(Patch patch:cell.patches()){
                double px=Math.clamp(markX,x+patch.minX(),x+patch.maxX()),pz=Math.clamp(markZ,z+patch.minZ(),z+patch.maxZ());
                double distance=Math.hypot(px-markX,pz-markZ);
                if(distance<nearest){nearest=distance;sampleX=px;sampleZ=pz;}
            }
            if(nearest>radius)return false;
        }
        double top=height(cell,sampleX,sampleZ,fineCornersMillimeters);
        // The authored shoulder plus existing compaction fit within 25 cm; a different floor does not.
        double bottom=snowBottomHeight(cell,sampleX,sampleZ);
        return Double.isFinite(top+bottom)&&(ownerY==Integer.MIN_VALUE?Math.abs(markY-top)<=.25:markY>=bottom-.05&&markY<=top+.25);
    }
    /** Authored snow underside follows the original chamfer/crust, including the lower soil owner. */
    public static double snowBottomHeight(Cell cell,double x,double z) {
        return height(cell,x,z,0)-cell.layers()/8.0;
    }
    public static double bottomHeight(Cell cell,double x,double z){return snowBottomHeight(cell,x,z);}
    public static double clampCompactedHeight(Cell cell,double x,double z,double undepressedTopHeight,double depressionMillimeters) {
        if(!Double.isFinite(undepressedTopHeight)||!Double.isFinite(depressionMillimeters))throw new IllegalArgumentException("Snow compaction height");
        return Math.max(snowBottomHeight(cell,x,z),undepressedTopHeight-depressionMillimeters/1000);
    }
    public static double compactedHeight(Cell cell,double x,double z,double[] fineCornersMillimeters,double depressionMillimeters) {
        return clampCompactedHeight(cell,x,z,height(cell,x,z,fineCornersMillimeters),depressionMillimeters);
    }
    /** Same observed support height as sample(), without constructing normals or Sample records. */
    public static double height(Cell cell,double x,double z,double deltaMillimeters){
        if(cell.generic())return cell.pos().getY()+cell.supportTop(x-cell.pos().getX(),z-cell.pos().getZ())+deltaMillimeters/1000;
        return cell.pos().getY()+cell.baseOffset()+SnowTerrainGeometry.height(Math.max(1,cell.layers()),cell.mask(),cell.crust(),
                Math.clamp(x-cell.pos().getX(),0,1),Math.clamp(z-cell.pos().getZ(),0,1),deltaMillimeters);
    }
    public static double height(Cell cell,double x,double z,double[] fineCornersMillimeters){
        if(cell.generic()){
            double lx=Math.clamp(x-cell.pos().getX(),0,1),lz=Math.clamp(z-cell.pos().getZ(),0,1);
            double[] c=fineCornersMillimeters;
            double delta=(c[0]*(1-lx)+c[1]*lx)*(1-lz)+(c[3]*(1-lx)+c[2]*lx)*lz;
            return cell.pos().getY()+cell.supportTop(lx,lz)+delta/1000;
        }
        return cell.pos().getY()+cell.baseOffset()+SnowTerrainGeometry.height(Math.max(1,cell.layers()),cell.mask(),cell.crust(),
                Math.clamp(x-cell.pos().getX(),0,1),Math.clamp(z-cell.pos().getZ(),0,1),fineCornersMillimeters);
    }
    public static SnowTerrainGeometry.Sample sample(Cell cell,double x,double z,double deltaMillimeters) {
        if(cell.generic())return new SnowTerrainGeometry.Sample(cell.pos().getY()+cell.supportTop(x-cell.pos().getX(),z-cell.pos().getZ())+deltaMillimeters/1000,0,1,0);
        var s=SnowTerrainGeometry.sample(Math.max(1,cell.layers()),cell.mask(),cell.crust(),
                Math.clamp(x-cell.pos().getX(),0,1),Math.clamp(z-cell.pos().getZ(),0,1),deltaMillimeters);
        return new SnowTerrainGeometry.Sample(cell.pos().getY()+cell.baseOffset()+s.height(),s.normalX(),s.normalY(),s.normalZ());
    }
    public static SnowTerrainGeometry.Sample sample(Cell cell,double x,double z,double[] fineCornersMillimeters) {
        if(cell.generic()){
            double lx=Math.clamp(x-cell.pos().getX(),0,1),lz=Math.clamp(z-cell.pos().getZ(),0,1);
            double[] c=fineCornersMillimeters;
            double delta=(c[0]*(1-lx)+c[1]*lx)*(1-lz)+(c[3]*(1-lx)+c[2]*lx)*lz;
            double dx=((c[1]-c[0])*(1-lz)+(c[2]-c[3])*lz)/1000,dz=((c[3]-c[0])*(1-lx)+(c[2]-c[1])*lx)/1000,n=Math.sqrt(1+dx*dx+dz*dz);
            return new SnowTerrainGeometry.Sample(cell.pos().getY()+cell.supportTop(lx,lz)+delta/1000,-dx/n,1/n,-dz/n);
        }
        var s=SnowTerrainGeometry.sample(Math.max(1,cell.layers()),cell.mask(),cell.crust(),
                Math.clamp(x-cell.pos().getX(),0,1),Math.clamp(z-cell.pos().getZ(),0,1),fineCornersMillimeters);
        return new SnowTerrainGeometry.Sample(cell.pos().getY()+cell.baseOffset()+s.height(),s.normalX(),s.normalY(),s.normalZ());
    }
}
