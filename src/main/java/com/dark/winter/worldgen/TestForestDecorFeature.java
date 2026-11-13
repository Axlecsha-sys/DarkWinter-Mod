// Places sparse snowy deadwood and stones while fresh test-biome chunks finish decoration.
package com.dark.winter.worldgen;

import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.SnowVariantFurnitureBlock;
import com.dark.winter.registry.ModFurniture;
import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.jspecify.annotations.Nullable;

/** Generation-only, fixed work budget; existing vegetation, terrain and loaded chunks are never replaced. */
public record TestForestDecorFeature() implements Feature {
    public static final Identifier ID=Identifier.fromNamespaceAndPath("darkwinter","test_forest_decor");
    public static final MapCodec<TestForestDecorFeature> CODEC=MapCodec.unit(TestForestDecorFeature::new);
    public static final ResourceKey<PlacedFeature> PLACED=ResourceKey.create(Registries.PLACED_FEATURE,ID);
    public static final int MAX_ATTEMPTS=12,MAX_WRITES=7,EDGE_MARGIN=3,MAX_COLUMN_DEPTH=64;
    private static final int FLAGS=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SKIP_ON_PLACE;
    private static final Direction[] FACINGS={Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST};
    // Expected selections per 64x64 blocks: 5 branch piles, 1 log, 1 stump, 4 rock scatters.
    // Failed terrain/footprint checks reduce the actual count rather than moving existing objects.
    private static final String[] IDS={"fallen_spruce_log","spruce_stump","forest_branch_pile","rock_scatter"};
    private static final int[] CHANCES={1,1,5,4};

    public static void init(){
        Registry.register(BuiltInRegistries.FEATURE_TYPE,ID,CODEC);
        BiomeModifications.addFeature(BiomeSelectors.includeByKey(TestBiomeCompatibility.BIOME),
                GenerationStep.Decoration.TOP_LAYER_MODIFICATION,PLACED);
    }
    @Override public MapCodec<TestForestDecorFeature> codec(){return CODEC;}

    @Override public boolean place(WorldGenLevel level,ChunkGenerator generator,RandomSource random,BlockPos origin){
        if(!(level instanceof WorldGenRegion region)||region.getLevel().dimension()!=Level.OVERWORLD)return false;
        var center=region.getCenter();
        if((origin.getX()>>4)!=center.x()||(origin.getZ()>>4)!=center.z())return false;
        ChunkAccess chunk=region.getChunk(center.x(),center.z());
        if(chunk instanceof ImposterProtoChunk||chunk.isUpgrading()
                ||chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES))return false;
        // Keep each complete footprint inside the center chunk. This bounds writes and
        // avoids partial assemblies at decoration-neighborhood edges.
        int anchorX=6+random.nextInt(4),anchorZ=6+random.nextInt(4);
        int attempts=0,writes=0;
        for(int kind=0;kind<IDS.length;kind++){
            boolean selected=random.nextInt(16)<CHANCES[kind];
            if(!selected)continue;
            String id=IDS[kind];
            if(kind==3){
                int variant=random.nextInt(8);
                id=variant==0?"boulder_large":variant<4?"boulder_small":"rock_scatter";
            }
            FurnitureBlock block=ModFurniture.get(id);
            if(!(block instanceof SnowVariantFurnitureBlock)
                    ||writes+block.definition().parts().size()>MAX_WRITES)continue;
            for(int retry=0;retry<3&&attempts<MAX_ATTEMPTS;retry++){
                attempts++;
                int x=anchorX+random.nextInt(7)-3,z=anchorZ+random.nextInt(7)-3;
                BlockPos root=floor(region,chunk,center.getMinBlockX()+x,center.getMinBlockZ()+z);
                if(root==null||!nearForestAnchor(region,chunk,root))continue;
                int start=random.nextInt(4);
                Direction facing=null;
                for(int rotation=0;rotation<4;rotation++){
                    Direction candidate=FACINGS[(start+rotation)&3];
                    if(fits(region,chunk,block,root,candidate)){facing=candidate;break;}
                }
                if(facing==null)continue;
                var positions=block.positions(root,facing);
                // The whole assembly has been validated against the same unmodified snapshot.
                // Only snow cells are replaced; every part is on the same full-height floor.
                for(int part=0;part<positions.size();part++){
                    BlockState placed=block.defaultBlockState().setValue(FurnitureBlock.PART,part)
                            .setValue(FurnitureBlock.FACING,facing).setValue(SnowVariantFurnitureBlock.SNOWY,true);
                    region.setBlock(positions.get(part),placed,FLAGS);
                }
                writes+=positions.size();
                break;
            }
        }
        return writes>0;
    }

    private static @Nullable BlockPos floor(WorldGenRegion region,ChunkAccess chunk,int x,int z){
        if((x&15)<EDGE_MARGIN||(x&15)>15-EDGE_MARGIN||(z&15)<EDGE_MARGIN||(z&15)>15-EDGE_MARGIN)return null;
        int top=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x&15,z&15);
        int bottom=Math.max(region.getMinY(),top-MAX_COLUMN_DEPTH+1);
        BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos(x,top,z);
        for(int y=top;y>=bottom;y--){
            cursor.setY(y);
            BlockState state=chunk.getBlockState(cursor);
            if(state.is(Blocks.SNOW)&&state.getValue(SnowLayerBlock.LAYERS)==1
                    &&naturalSoil(chunk.getBlockState(cursor.below())))return cursor.immutable();
        }
        return null;
    }

    private static boolean fits(WorldGenRegion region,ChunkAccess chunk,FurnitureBlock block,BlockPos root,Direction facing){
        var center=chunk.getPos();
        for(BlockPos target:block.positions(root,facing)){
            int x=target.getX()&15,z=target.getZ()&15;
            if(target.getY()!=root.getY()||(target.getX()>>4)!=center.x()||(target.getZ()>>4)!=center.z()
                    ||x<EDGE_MARGIN||x>15-EDGE_MARGIN||z<EDGE_MARGIN||z>15-EDGE_MARGIN
                    ||region.isOutsideBuildHeight(target)||!region.ensureCanWrite(target)
                    ||!TestBiomeCompatibility.snowTerrain(region.getBiome(target)))return false;
            BlockState existing=chunk.getBlockState(target),support=chunk.getBlockState(target.below());
            if(!existing.is(Blocks.SNOW)||existing.getValue(SnowLayerBlock.LAYERS)!=1
                    ||!existing.getFluidState().isEmpty()||!naturalSoil(support)
                    ||!support.getFluidState().isEmpty()||!support.isFaceSturdy(region,target.below(),Direction.UP))return false;
        }
        return true;
    }
    /** Attach sparse groups to an actual trunk base or the foot of a natural snow terrace. */
    private static boolean nearForestAnchor(WorldGenRegion region,ChunkAccess chunk,BlockPos root){
        var center=chunk.getPos();
        BlockPos.MutableBlockPos nearby=new BlockPos.MutableBlockPos();
        for(int dz=-4;dz<=4;dz++)for(int dx=-4;dx<=4;dx++){
            int distance=dx*dx+dz*dz;
            if(distance<4||distance>16)continue;
            int x=root.getX()+dx,z=root.getZ()+dz;
            if((x>>4)!=center.x()||(z>>4)!=center.z())continue;
            for(int dy=-1;dy<=1;dy++){
                nearby.set(x,root.getY()+dy,z);
                if(chunk.getBlockState(nearby).is(TestSpruceBlocks.LOG)
                        &&naturalSoil(chunk.getBlockState(nearby.below()))
                        &&TestBiomeCompatibility.snowTerrain(region.getBiome(nearby)))return true;
            }
            nearby.set(x,root.getY(),z);
            BlockState step=chunk.getBlockState(nearby),support=chunk.getBlockState(nearby.below());
            boolean halfStep=step.is(TestTerrainBlocks.SNOWY_GRASS_SLAB)
                    &&step.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)==net.minecraft.world.level.block.state.properties.SlabType.BOTTOM
                    &&step.getValue(com.dark.winter.block.TestSnowyGrassSlabBlock.SNOWY)
                    &&step.getFluidState().isEmpty();
            if((naturalSoil(step)||halfStep)&&naturalSoil(support)
                    &&TestBiomeCompatibility.snowTerrain(region.getBiome(nearby))){
                BlockState cover=chunk.getBlockState(nearby.above());
                if(cover.isAir()||cover.is(Blocks.SNOW)&&cover.getValue(SnowLayerBlock.LAYERS)==1)return true;
            }
        }
        return false;
    }

    private static boolean naturalSoil(BlockState state){
        return state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.PODZOL)||state.is(BlockTags.DIRT);
    }
}
