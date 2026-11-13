// Adds half-height snowy grass steps only while fresh test-biome terrain is decorated.
package com.dark.winter.worldgen;

import com.dark.winter.block.TestSnowyGrassSlabBlock;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

public record TestSnowTerraceFeature() implements Feature {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("darkwinter", "test_snow_terraces");
    public static final MapCodec<TestSnowTerraceFeature> CODEC = MapCodec.unit(TestSnowTerraceFeature::new);
    public static final ResourceKey<PlacedFeature> PLACED = ResourceKey.create(Registries.PLACED_FEATURE, ID);
    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final int MAX_COLUMN_DEPTH = 64;

    public static void init() {
        TestTerrainBlocks.initialize();
        Registry.register(BuiltInRegistries.FEATURE_TYPE, ID, CODEC);
        BiomeModifications.addFeature(BiomeSelectors.includeByKey(TestBiomeCompatibility.BIOME),
                GenerationStep.Decoration.TOP_LAYER_MODIFICATION, PLACED);
    }

    @Override public MapCodec<TestSnowTerraceFeature> codec() { return CODEC; }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        if (!(level instanceof WorldGenRegion region) || region.getLevel().dimension() != Level.OVERWORLD) return false;
        var center = region.getCenter();
        if ((origin.getX() >> 4) != center.x() || (origin.getZ() >> 4) != center.z()) return false;
        ChunkAccess chunk = region.getChunk(center.x(), center.z());
        if (chunk instanceof ImposterProtoChunk || chunk.isUpgrading()
                || chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) return false;

        // Read the already reserved generation neighborhood only. No chunk requests leave the
        // current feature dependency radius, and only the center chunk is ever written.
        ChunkAccess[] neighbors = new ChunkAccess[9];
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            int cx = center.x() + dx, cz = center.z() + dz;
            if (region.hasChunk(cx, cz)) neighbors[(dz + 1) * 3 + dx + 1] = region.getChunk(cx, cz, ChunkStatus.EMPTY, false);
        }
        List<BlockPos> positions = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
            int x = center.getMinBlockX() + dx, z = center.getMinBlockZ() + dz;
            int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, dx, dz);
            int bottom = Math.max(region.getMinY(), top - MAX_COLUMN_DEPTH);
            for (int y = top; y >= bottom; y--) {
                cursor.set(x, y, z);
                BlockState snow = chunk.getBlockState(cursor);
                if (!snow.is(Blocks.SNOW) || snow.getValue(SnowLayerBlock.LAYERS) != 1) continue;
                if (!TestBiomeCompatibility.snowTerrain(region.getBiome(cursor))
                        || !chunk.getBlockState(cursor.below()).is(Blocks.GRASS_BLOCK)
                        || !chunk.getBlockState(cursor.above()).isAir()) continue;
                if (besideHigherGrass(region, neighbors, center.x(), center.z(), cursor)) positions.add(cursor.immutable());
            }
        }
        // Plan first so the result cannot grow a second row from slabs written earlier in this pass.
        BlockState slab = TestTerrainBlocks.SNOWY_GRASS_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM).setValue(SlabBlock.WATERLOGGED, false)
                .setValue(TestSnowyGrassSlabBlock.SNOWY, true);
        boolean changed = false;
        for (BlockPos pos : positions) {
            BlockState previous = chunk.getBlockState(pos);
            if (previous.is(Blocks.SNOW) && previous.getValue(SnowLayerBlock.LAYERS) == 1)
                changed |= region.setBlock(pos, slab, Block.UPDATE_CLIENTS);
        }
        return changed;
    }

    private static boolean besideHigherGrass(WorldGenRegion region, ChunkAccess[] chunks, int centerX, int centerZ, BlockPos pos) {
        for (Direction direction : SIDES) {
            BlockPos other = pos.relative(direction);
            int dx = (other.getX() >> 4) - centerX, dz = (other.getZ() >> 4) - centerZ;
            if (Math.abs(dx) > 1 || Math.abs(dz) > 1) continue;
            ChunkAccess chunk = chunks[(dz + 1) * 3 + dx + 1];
            if (chunk == null || !chunk.getBlockState(other).is(Blocks.GRASS_BLOCK)
                    || !chunk.getBlockState(other.below()).is(Blocks.DIRT)
                    || !TestBiomeCompatibility.snowTerrain(region.getBiome(other))) continue;
            BlockState cover = chunk.getBlockState(other.above());
            // Adjacent chunks may not have run freeze_top_layer yet. Native air and its
            // one-layer snow result are equivalent; plants and furniture never qualify.
            if (cover.isAir() || cover.is(Blocks.SNOW) && cover.getValue(SnowLayerBlock.LAYERS) == 1) return true;
        }
        return false;
    }
}
