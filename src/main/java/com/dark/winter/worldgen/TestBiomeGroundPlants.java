// Replaces existing floor vegetation only while new chunks of the dedicated test biome are decorated.
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
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Keeps vanilla patch positions and density; no world-load, command or tick-time conversion. */
public record TestBiomeGroundPlants() implements Feature {
    public static final Identifier ID=Identifier.fromNamespaceAndPath("darkwinter","test_ground_plants");
    public static final MapCodec<TestBiomeGroundPlants> CODEC=MapCodec.unit(TestBiomeGroundPlants::new);
    public static final ResourceKey<PlacedFeature> PLACED=ResourceKey.create(Registries.PLACED_FEATURE,ID);
    private static final int MAX_COLUMN_DEPTH=64;
    private static final int FLAGS=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SKIP_ON_PLACE;
    private static final Direction[] FACINGS={Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST};

    public static void init() {
        Registry.register(BuiltInRegistries.FEATURE_TYPE,ID,CODEC);
        // The added final step follows vanilla patches and freeze_top_layer, never other biomes.
        BiomeModifications.addFeature(BiomeSelectors.includeByKey(TestBiomeCompatibility.BIOME),
                GenerationStep.Decoration.TOP_LAYER_MODIFICATION,PLACED);
    }
    @Override public MapCodec<TestBiomeGroundPlants> codec(){return CODEC;}

    @Override public boolean place(WorldGenLevel level,ChunkGenerator generator,RandomSource random,BlockPos origin) {
        if(!(level instanceof WorldGenRegion region)||region.getLevel().dimension()!=Level.OVERWORLD)return false;
        var center=region.getCenter();
        if((origin.getX()>>4)!=center.x()||(origin.getZ()>>4)!=center.z())return false;
        ChunkAccess chunk=region.getChunk(center.x(),center.z());
        if(chunk instanceof ImposterProtoChunk||chunk.isUpgrading()
                ||chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES))return false;
        FurnitureBlock grass=ModFurniture.get("dry_grass_tuft"),shrub=ModFurniture.get("snowy_shrub");
        if(!(grass instanceof SnowVariantFurnitureBlock)||!(shrub instanceof SnowVariantFurnitureBlock))return false;
        boolean changed=false;BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            int top=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);
            int bottom=Math.max(region.getMinY(),top-MAX_COLUMN_DEPTH+1);
            for(int y=top;y>=bottom;y--){
                cursor.set(center.getMinBlockX()+x,y,center.getMinBlockZ()+z);
                BlockState state=chunk.getBlockState(cursor);
                FurnitureBlock replacement=state.is(Blocks.SHORT_GRASS)?grass:
                        state.is(Blocks.FERN)||state.is(Blocks.LARGE_FERN)
                                &&state.getValue(DoublePlantBlock.HALF)==DoubleBlockHalf.LOWER?shrub:null;
                if(replacement!=null)changed|=replace(region,cursor.immutable(),state,replacement);
            }
        }
        return changed;
    }

    private static boolean replace(WorldGenRegion region,BlockPos origin,BlockState original,FurnitureBlock replacement) {
        if(!TestBiomeCompatibility.snowTerrain(region.getBiome(origin)))return false;
        BlockPos below=origin.below();BlockState support=region.getBlockState(below);
        // In 26.3 the dirt tag contains only dirt/coarse/rooted dirt, not grass or podzol.
        if(!(support.is(Blocks.GRASS_BLOCK)||support.is(Blocks.PODZOL)||support.is(BlockTags.DIRT))
                ||!support.isFaceSturdy(region,below,Direction.UP)
                ||!support.getFluidState().isEmpty())return false;
        // Position-derived orientation does not consume the generator's shared random stream.
        Direction facing=FACINGS[(int)(origin.asLong()^(origin.asLong()>>>32))&3];
        var positions=replacement.positions(origin,facing);
        for(int part=0;part<positions.size();part++){
            BlockPos target=positions.get(part);
            if((target.getX()>>4)!=(origin.getX()>>4)||(target.getZ()>>4)!=(origin.getZ()>>4)
                    ||!region.ensureCanWrite(target)||!TestBiomeCompatibility.snowTerrain(region.getBiome(target)))return false;
            BlockState present=region.getBlockState(target);
            if(!present.getFluidState().isEmpty())return false;
            if(part==0){if(present!=original)return false;}
            else if(!present.isAir()&&!(original.is(Blocks.LARGE_FERN)&&present.is(Blocks.LARGE_FERN)
                    &&present.getValue(DoublePlantBlock.HALF)==DoubleBlockHalf.UPPER))return false;
        }
        // Validate the entire two-part shrub before writing either half into the ProtoChunk.
        for(int part=0;part<positions.size();part++){
            BlockState state=replacement.defaultBlockState().setValue(FurnitureBlock.FACING,facing)
                    .setValue(FurnitureBlock.PART,part).setValue(SnowVariantFurnitureBlock.SNOWY,true);
            region.setBlock(positions.get(part),state,FLAGS);
        }
        if(support.hasProperty(SnowyBlock.SNOWY))region.setBlock(below,support.setValue(SnowyBlock.SNOWY,true),FLAGS);
        return true;
    }
}
