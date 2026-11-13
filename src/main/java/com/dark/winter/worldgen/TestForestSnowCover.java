// Completes fresh test-forest floor snow before terraces and ground decorations are placed.
package com.dark.winter.worldgen;

import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Generation-only bounded center-chunk writes; loaded worlds and ordinary biomes are never repaired here. */
public record TestForestSnowCover() implements Feature {
    public static final Identifier ID=Identifier.fromNamespaceAndPath("darkwinter","test_forest_snow_cover");
    public static final MapCodec<TestForestSnowCover> CODEC=MapCodec.unit(TestForestSnowCover::new);
    public static final ResourceKey<PlacedFeature> PLACED=ResourceKey.create(Registries.PLACED_FEATURE,ID);
    public static final int MAX_COLUMN_DEPTH=64;
    private static final int FLAGS=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SKIP_ON_PLACE;

    public static void init() {
        Registry.register(BuiltInRegistries.FEATURE_TYPE,ID,CODEC);
        // Appended after the biome's native freeze_top_layer, registered before terraces and furniture.
        BiomeModifications.addFeature(BiomeSelectors.includeByKey(TestBiomeCompatibility.BIOME),
                GenerationStep.Decoration.TOP_LAYER_MODIFICATION,PLACED);
    }

    @Override public MapCodec<TestForestSnowCover> codec(){return CODEC;}

    @Override public boolean place(WorldGenLevel level,ChunkGenerator generator,RandomSource random,BlockPos origin) {
        if(!(level instanceof WorldGenRegion region)||region.getLevel().dimension()!=Level.OVERWORLD)return false;
        var center=region.getCenter();
        if((origin.getX()>>4)!=center.x()||(origin.getZ()>>4)!=center.z())return false;
        ChunkAccess chunk=region.getChunk(center.x(),center.z());
        if(!(chunk instanceof ProtoChunk)||chunk instanceof ImposterProtoChunk||chunk.isUpgrading()
                ||chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES))return false;
        boolean changed=false;
        BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
        for(int dx=0;dx<16;dx++)for(int dz=0;dz<16;dz++) {
            int top=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,dx,dz);
            int bottom=Math.max(region.getMinY(),top-MAX_COLUMN_DEPTH+1);
            for(int y=top;y>=bottom;y--) {
                cursor.set(center.getMinBlockX()+dx,y,center.getMinBlockZ()+dz);
                BlockState state=chunk.getBlockState(cursor);
                if(!state.getFluidState().isEmpty())break;
                if(state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.PODZOL)) {
                    changed|=cover(region,chunk,cursor.immutable(),state);
                    break; // Never search through the first floor for a lower cave or buried grass.
                }
                if(state.isAir()||state.is(Blocks.SNOW)||state.is(Blocks.SNOW_BLOCK)
                        ||state.is(TestSpruceBlocks.LOG)||state.is(TestSpruceBlocks.BOUGH)
                        ||state.getBlock() instanceof VegetationBlock)continue;
                break;
            }
        }
        return changed;
    }

    private static boolean cover(WorldGenRegion region,ChunkAccess chunk,BlockPos soilPos,BlockState soil) {
        BlockPos target=soilPos.above();
        if(!region.ensureCanWrite(target)||!TestBiomeCompatibility.snowTerrain(region.getBiome(target))
                ||!soil.getFluidState().isEmpty())return false;
        BlockState existing=chunk.getBlockState(target);
        if(!existing.getFluidState().isEmpty())return false;
        boolean mushroom=existing.is(Blocks.BROWN_MUSHROOM)||existing.is(Blocks.RED_MUSHROOM);
        if(!existing.isAir()) {
            // Grass mushrooms are accepted before skylight exists, block native snowfall, then
            // disappear during postprocessing at skylight 15. Podzol's surviving mushrooms remain.
            if(!mushroom||soil.is(BlockTags.OVERRIDES_MUSHROOM_LIGHT_REQUIREMENT)
                    ||chunk.getHeight(Heightmap.Types.MOTION_BLOCKING,target.getX()&15,target.getZ()&15)!=soilPos.getY())return false;
        }
        if(region.getBiome(target).value().getPrecipitationAt(target,region.getSeaLevel())!=Biome.Precipitation.SNOW)return false;
        BlockState snow=Blocks.SNOW.defaultBlockState();
        if(!snow.canSurvive(region,target)||!region.setBlock(target,snow,FLAGS))return false;
        if(soil.hasProperty(SnowyBlock.SNOWY))
            region.setBlock(soilPos,soil.setValue(SnowyBlock.SNOWY,true),FLAGS);
        return true;
    }
}
