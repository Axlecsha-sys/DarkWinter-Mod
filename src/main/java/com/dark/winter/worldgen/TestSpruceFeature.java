// An irregular, bounded network of original trunk and drooping bough meshes.
package com.dark.winter.worldgen;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/** An irregular, bounded network of original trunk and drooping bough meshes. */
public record TestSpruceFeature() implements Feature {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("darkwinter", "test_spruce_tree");
    public static final MapCodec<TestSpruceFeature> CODEC = MapCodec.unit(TestSpruceFeature::new);
    private static final int[] DX = {0,1,1,1,0,-1,-1,-1}, DZ = {-1,-1,0,1,1,1,0,-1};
    public static void init() { Registry.register(BuiltInRegistries.FEATURE_TYPE, ID, CODEC); }
    @Override public MapCodec<TestSpruceFeature> codec() { return CODEC; }

    @Override public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        // Three related habits share the same placement rules: an open tall spruce,
        // a medium tree, and a fuller sheltered tree. The variation belongs to the
        // tree, rather than choosing a new unrelated density at every branch tier.
        int habit = random.nextInt(3);
        int height = switch (habit) {
            case 0 -> 18 + random.nextInt(3);
            case 1 -> 16 + random.nextInt(3);
            default -> 14 + random.nextInt(4);
        };
        BlockState ground = level.getBlockState(origin.below());
        if (!(ground.is(BlockTags.DIRT) || ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.PODZOL)
                || ground.is(Blocks.MYCELIUM) || ground.is(Blocks.MOSS_BLOCK))
                || !level.getFluidState(origin).isEmpty()) return false;
        // Taller crowns need room around their trunks. Keeping the vanilla attempt count
        // without a growth-clearance test would nearly double the actual stand density.
        BlockPos.MutableBlockPos nearby = new BlockPos.MutableBlockPos();
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            if (dx * dx + dz * dz > 16) continue;
            for (int y = 2; y <= 10; y += 2) {
                nearby.set(origin.getX() + dx, origin.getY() + y, origin.getZ() + dz);
                if (level.getBlockState(nearby).is(TestSpruceBlocks.LOG)) return false;
            }
        }
        // Whole trunk is validated first. Crowns may intermingle without rejecting every
        // neighbouring tree: the original taiga's 10/11 placement attempts remain useful.
        for (int y = 0; y < height; y++) {
            BlockPos pos = origin.above(y);
            if (!level.ensureCanWrite(pos) || !replaceable(level, pos)) return false;
        }
        Map<BlockPos, BlockState> branches = new LinkedHashMap<>();
        int first = habit == 0 ? 4 + random.nextInt(3) : 3 + random.nextInt(habit == 1 ? 3 : 2);
        int phase = random.nextInt(8);
        int shorterSide = random.nextInt(8);
        for (int y = first; y < height;) {
            int remaining = height - y;
            // Long middle boughs and occasional shortened sectors avoid a succession
            // of ever-smaller identical rings. Only the leader consistently narrows.
            int spread = remaining > 5 ? 3 : remaining > 2 ? 2 : 1;
            phase = (phase + 1 + random.nextInt(3)) & 7;
            int arms = remaining <= 3 ? 3 : remaining <= 6 ? 4 : 5 + (habit == 2 ? 1 : 0);
            if (habit == 0 && remaining > 6 && random.nextBoolean()) arms--;
            if (habit == 2 && remaining > 6 && random.nextInt(3) == 0) arms++;
            if (random.nextInt(5) == 0) arms--;
            for (int a = 0; a < arms; a++) {
                int d = (phase + a * 3) & 7;
                int length = Math.max(1, spread - ((d & 1) != 0 && spread > 1 ? 1 : 0));
                int fromShortSide = Math.min((d - shorterSide + 8) & 7, (shorterSide - d + 8) & 7);
                if (length > 1 && (random.nextInt(6) == 0 || (fromShortSide <= 1 && random.nextBoolean()))) length--;
                int offsetY = random.nextInt(5) == 0 ? (random.nextBoolean() ? 1 : -1) : 0;
                int startY = Math.min(height - 1, y + offsetY);
                // Some boughs bend sooner, others carry their weight farther out;
                // a few remain level. Each connection still drops at most one cell.
                int sagAfter = length > 1 && random.nextInt(4) != 0 ? 1 + random.nextInt(length - 1) : -1;
                List<BlockPos> chain = new ArrayList<>(3);
                int currentY = startY;
                for (int step = 1; step <= length; step++) {
                    BlockPos p = origin.offset(DX[d] * step, currentY, DZ[d] * step);
                    if (!level.ensureCanWrite(p) || !replaceable(level, p)
                            || level.getBlockState(p).is(TestSpruceBlocks.BOUGH) || branches.containsKey(p)) break;
                    chain.add(p);
                    if (step == sagAfter) currentY--;
                }
                for (int i = 0; i < chain.size(); i++) {
                    boolean tip = i == chain.size() - 1;
                    int drop = tip ? 0 : chain.get(i).getY() - chain.get(i+1).getY();
                    int variant = remaining <= 3 ? 0 : remaining <= 6 ? random.nextInt(2)
                            : habit == 0 ? 1 + random.nextInt(2) : habit == 1 ? 2 + random.nextInt(2)
                            : random.nextInt(4) == 0 ? 2 : 3;
                    branches.putIfAbsent(chain.get(i), TestSpruceBlocks.BOUGH.defaultBlockState()
                            .setValue(TestSpruceBoughBlock.DIRECTION, d).setValue(TestSpruceBoughBlock.DROP, drop)
                            .setValue(TestSpruceBoughBlock.VARIANT, variant).setValue(TestSpruceBoughBlock.TIP, tip));
                }
            }
            int gap = habit == 0 ? (random.nextInt(5) == 0 ? 1 : 2)
                    : habit == 1 ? (random.nextBoolean() ? 1 : 2)
                    : (random.nextInt(4) == 0 ? 2 : 1);
            y += gap;
        }
        for (int y = 0; y < height; y++) {
            int radius = y < height / 4 ? 5 : y < height / 2 ? 4 : y < height * 7 / 10 ? 3
                    : y < height * 9 / 10 ? 2 : 1;
            level.setBlock(origin.above(y), TestSpruceBlocks.LOG.defaultBlockState().setValue(TestSpruceLogBlock.RADIUS, radius), Block.UPDATE_CLIENTS);
        }
        for (var entry : branches.entrySet()) {
            // Never overwrite another trunk or non-tree terrain. Snow is integral to each mesh.
            if (replaceable(level, entry.getKey()) && !level.getBlockState(entry.getKey()).is(TestSpruceBlocks.BOUGH))
                level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_CLIENTS);
        }
        return true;
    }
    private static boolean replaceable(WorldGenLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return level.getFluidState(pos).isEmpty() && (state.isAir() || state.is(TestSpruceBlocks.BOUGH)
                || state.is(Blocks.SNOW) || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN));
    }
}
