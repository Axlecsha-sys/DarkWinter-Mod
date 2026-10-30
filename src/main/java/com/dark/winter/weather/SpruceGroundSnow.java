// Forest-floor snow created with new terrain, never by a world-load or server-tick callback.
package com.dark.winter.weather;

import com.dark.winter.worldgen.TestBiomeCompatibility;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Forest-floor snow created with new terrain, never by a world-load or server-tick callback. */
public record SpruceGroundSnow() implements Feature {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("darkwinter", "forest_floor_snow");
    public static final MapCodec<SpruceGroundSnow> CODEC = MapCodec.unit(SpruceGroundSnow::new);
    public static final ResourceKey<PlacedFeature> PLACED = ResourceKey.create(Registries.PLACED_FEATURE, ID);
    public static final int MAX_COLUMN_DEPTH = 64;

    public static void init() {
        Registry.register(BuiltInRegistries.FEATURE_TYPE, ID, CODEC);
        // Every tree species in the northern Overworld receives its winter ground cover.
        // The final decoration step runs after vegetation and vanilla surface snow.
        // The isolated test biome retains vanilla floor decoration. Its snow belongs
        // to freeze_top_layer and the new tree mesh, not this DarkWinter addition.
        BiomeModifications.addFeature(BiomeSelectors.foundInOverworld()
                        .and(BiomeSelectors.excludeByKey(TestBiomeCompatibility.BIOME)),
                GenerationStep.Decoration.TOP_LAYER_MODIFICATION, PLACED);
    }

    @Override
    public MapCodec<SpruceGroundSnow> codec() {
        return CODEC;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        // Commands, saplings and loaded LevelChunks must never apply this feature to old terrain.
        if (!(level instanceof WorldGenRegion region) || region.getLevel().dimension() != Level.OVERWORLD) return false;
        var center = region.getCenter();
        if ((origin.getX() >> 4) != center.x() || (origin.getZ() >> 4) != center.z()) return false;
        ChunkAccess chunk = region.getChunk(center.x(), center.z());
        if (chunk instanceof ImposterProtoChunk || chunk.isUpgrading()
                || chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) return false;
        boolean placed = false;
        for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
            placed |= coverColumn(region, chunk, center.getMinBlockX() + dx, center.getMinBlockZ() + dz);
        }
        return placed;
    }

    private static boolean coverColumn(WorldGenRegion region, ChunkAccess chunk, int x, int z) {
        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        int bottom = Math.max(region.getMinY(), top - MAX_COLUMN_DEPTH + 1);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, top, z);
        boolean naturalCanopy = false;
        int groundCoverTop = Integer.MIN_VALUE;
        for (int y = top; y >= bottom; y--) {
            cursor.setY(y);
            BlockState state = chunk.getBlockState(cursor);
            if (!state.getFluidState().isEmpty()) return false;
            if (state.isAir()) continue;
            if (state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT)) {
                if (state.getValue(LeavesBlock.PERSISTENT)) return false;
                naturalCanopy = true;
                continue;
            }
            if (state.is(Blocks.SNOW)) {
                // Each lower branch tier can have its own cap. Look through those caps,
                // preserving them, but stop at snow resting on the floor, a log or a roof.
                BlockState support = chunk.getBlockState(cursor.below());
                if (support.is(BlockTags.LEAVES) && support.hasProperty(LeavesBlock.PERSISTENT)
                        && !support.getValue(LeavesBlock.PERSISTENT)) continue;
                return false;
            }
            if (naturalCanopy && ordinaryGroundCover(state)) {
                groundCoverTop = Math.max(groundCoverTop, y);
                continue;
            }
            if (!naturalCanopy || !naturalGround(state) || state.is(Blocks.SNOW_BLOCK)) return false;
            BlockPos target = cursor.above();
            BlockState snow = Blocks.SNOW.defaultBlockState();
            BlockState replaced = chunk.getBlockState(target);
            if ((!replaced.isAir() && !ordinaryGroundCover(replaced)) || !snow.canSurvive(region, target)) return false;
            boolean placed = region.setBlock(target, snow, Block.UPDATE_CLIENTS);
            // Fresh forest vegetation is buried by the generated snow. Clear the upper half
            // of tall grass/fern too; flowers, berry bushes and all old chunks stay untouched.
            if (placed && groundCoverTop > target.getY()) {
                BlockPos.MutableBlockPos upper = target.mutable();
                for (int coverY = target.getY() + 1; coverY <= groundCoverTop; coverY++) {
                    upper.setY(coverY);
                    if (ordinaryGroundCover(chunk.getBlockState(upper)))
                        region.setBlock(upper, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            if (placed && state.hasProperty(SnowyBlock.SNOWY)) {
                region.setBlock(cursor, state.setValue(SnowyBlock.SNOWY, true), Block.UPDATE_CLIENTS);
            }
            return placed;
        }
        return false;
    }

    private static boolean naturalGround(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM) || state.is(Blocks.GRAVEL) || state.is(Blocks.STONE)
                || state.is(Blocks.DEEPSLATE) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.MOSS_BLOCK);
    }

    private static boolean ordinaryGroundCover(BlockState state) {
        return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.FERN)
                || state.is(Blocks.TALL_GRASS) || state.is(Blocks.LARGE_FERN);
    }
}
