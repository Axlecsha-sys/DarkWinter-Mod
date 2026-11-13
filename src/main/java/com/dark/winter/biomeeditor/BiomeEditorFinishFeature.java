// Applies explicit editor surface, water and block operations only during fresh native chunk decoration.
package com.dark.winter.biomeeditor;

import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.SnowVariantFurnitureBlock;
import com.dark.winter.worldgen.TestSpruceBlocks;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;

public final class BiomeEditorFinishFeature implements Feature {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("darkwinter", "biome_editor_finish");
    public static final MapCodec<BiomeEditorFinishFeature> CODEC = Codec.STRING.fieldOf("profile")
            .xmap(BiomeEditorFinishFeature::new, BiomeEditorFinishFeature::profileJson);
    public static final int MAX_WRITES = 65_536;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;
    private static final Set<String> NATURE_FURNITURE = Set.of("dry_grass_tuft", "snowy_shrub", "forest_fern",
            "forest_branch_pile", "fallen_spruce_log", "spruce_stump", "rock_scatter", "boulder_small", "boulder_large");
    private static boolean initialized;
    private final BiomeEditorProfile profile;
    private final Map<Integer, BlockState> targetStates;

    public BiomeEditorFinishFeature(String profileJson) {
        profile = BiomeEditorProfile.parse(profileJson);
        Map<Integer, BlockState> targets = new HashMap<>();
        for (int i = 0; i < profile.operations().size(); i++) {
            var op = profile.operations().get(i);
            if (!op.enabled() || op.strength() == 0 || !Set.of("paint", "set_block", "vegetation").contains(op.kind())) continue;
            Identifier id = Identifier.parse(op.block());
            if (!BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("Unknown editor block: " + id);
            Block block = BuiltInRegistries.BLOCK.getValue(id);
            BlockState state = block.defaultBlockState();
            if (state.hasBlockEntity()) throw new IllegalArgumentException("Editor block operations do not supply block-entity data: " + id);
            if (op.kind().equals("paint") && !naturalSoil(state)) {
                throw new IllegalArgumentException("Paint requires a native soil or stone block: " + id);
            }
            if (op.kind().equals("vegetation") && !nativePlant(state) && !natureFurniture(state)) {
                throw new IllegalArgumentException("Vegetation requires a supported native plant or DarkWinter nature assembly: " + id);
            }
            if (op.kind().equals("set_block") && block instanceof FurnitureBlock) {
                throw new IllegalArgumentException("Nature assemblies require the vegetation operation; arbitrary multipart block placement is unsupported");
            }
            targets.put(i, state);
        }
        targetStates = Map.copyOf(targets);
    }

    /** Registration is separate from biome attachment; the exporter installs this as its final TOP_LAYER feature. */
    public static synchronized void init() {
        if (initialized) return;
        Registry.register(BuiltInRegistries.FEATURE_TYPE, ID, CODEC);
        initialized = true;
    }
    public String profileJson() { return profile.canonicalValidatedJson(); }
    public BiomeEditorProfile profile() { return profile; }
    @Override public MapCodec<BiomeEditorFinishFeature> codec() { return CODEC; }

    @Override public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        if (!(level instanceof WorldGenRegion region) || region.getLevel().dimension() != Level.OVERWORLD) return false;
        var center = region.getCenter();
        if ((origin.getX() >> 4) != center.x() || (origin.getZ() >> 4) != center.z()) return false;
        ChunkAccess chunk = region.getChunk(center.x(), center.z());
        if (!(chunk instanceof ProtoChunk) || chunk instanceof ImposterProtoChunk || chunk.isUpgrading()
                || chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) return false;
        Budget budget = new Budget(region, chunk);
        List<BiomeEditorProfile.Operation> operations = profile.operations();
        for (int index = 0; index < operations.size(); index++) {
            var op = operations.get(index);
            if (!op.enabled() || op.strength() == 0) continue;
            switch (op.kind()) {
                case "river", "lake" -> water(budget, op);
                case "paint" -> paint(budget, op, targetStates.get(index), index);
                case "set_block" -> blocks(budget, op, targetStates.get(index));
                case "vegetation" -> vegetation(budget, op, targetStates.get(index), index);
                // These operators are already encoded in BiomeEditorTerrain's native density graph.
                case "raise", "lower", "flatten", "plains", "smooth", "mountain", "plateau", "cave", "tunnel" -> {}
                default -> throw new IllegalArgumentException("Unsupported editor finish operation: " + op.kind());
            }
        }
        return budget.writes != 0;
    }

    private void water(Budget b, BiomeEditorProfile.Operation op) {
        int top = (int) Math.floor(op.waterLevel());
        if (top < b.region.getMinY() || top > b.region.getMaxY()) throw new IllegalArgumentException("Water level exceeds native build height");
        float radius = BiomeEditorTerrain.horizontalRadius(op);
        float shoulder = Math.max(.05f, Math.min(.95f, 1 - op.falloff()));
        for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
            int x = b.chunk.getPos().getMinBlockX() + dx, z = b.chunk.getPos().getMinBlockZ() + dz;
            if (BiomeEditorTerrain.horizontalDistance(op, x, z) >= radius * shoulder) continue;
            BlockPos soil = b.waterFloor(x, z);
            if (soil == null || soil.getY() >= top) continue;
            // Freeze runs before this feature: retain the original column bound before lowering its water/ice cap.
            int columnTop = Math.max(top, Math.min(b.region.getMaxY(),
                    b.chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15)));
            for (int y = soil.getY() + 1; y <= columnTop; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState current = b.chunk.getBlockState(pos);
                boolean hydro = current.is(Blocks.WATER) || current.is(Blocks.ICE) || current.is(Blocks.SNOW);
                if (!hydro && !replaceableCover(current)) break; // Never cross a tree, assembly or unrelated solid.
                if (y > top) {
                    if (hydro) b.set(pos, Blocks.AIR.defaultBlockState());
                    continue;
                }
                if (current.is(Blocks.WATER)) continue;
                if (current.getBlock() instanceof DoublePlantBlock && current.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER) {
                    BlockPos upper = pos.above();
                    BlockState upperState = b.chunk.getBlockState(upper);
                    if (upperState.getBlock() == current.getBlock()
                            && upperState.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) b.set(upper, Blocks.AIR.defaultBlockState());
                }
                b.set(pos, Blocks.WATER.defaultBlockState());
            }
            BlockState support = b.chunk.getBlockState(soil);
            if (b.chunk.getBlockState(soil.above()).is(Blocks.WATER) && support.hasProperty(SnowyBlock.SNOWY)) {
                b.set(soil, support.setValue(SnowyBlock.SNOWY, false));
            }
        }
    }

    private void paint(Budget b, BiomeEditorProfile.Operation op, BlockState target, int index) {
        for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
            int x = b.chunk.getPos().getMinBlockX() + dx, z = b.chunk.getPos().getMinBlockZ() + dz;
            if (!selected(op, index, x, z)) continue;
            BlockPos soil = b.floor(x, z);
            if (soil == null) continue;
            BlockState previous = b.chunk.getBlockState(soil), replacement = target;
            if (replacement.hasProperty(SnowyBlock.SNOWY) && previous.hasProperty(SnowyBlock.SNOWY)) {
                replacement = replacement.setValue(SnowyBlock.SNOWY, previous.getValue(SnowyBlock.SNOWY));
            }
            b.set(soil, replacement);
        }
    }

    private static void blocks(Budget b, BiomeEditorProfile.Operation op, BlockState target) {
        int bottom = Math.max(b.region.getMinY(), (int) Math.ceil(op.y() - op.radius()));
        int top = Math.min(b.region.getMaxY(), (int) Math.floor(op.y() + op.radius()));
        double radiusSquared = (double) op.radius() * op.radius();
        for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
            int x = b.chunk.getPos().getMinBlockX() + dx, z = b.chunk.getPos().getMinBlockZ() + dz;
            double horizontal = (x - op.x()) * (x - op.x()) + (z - op.z()) * (z - op.z());
            if (horizontal > radiusSquared) continue;
            for (int y = bottom; y <= top; y++) {
                if (horizontal + (y - op.y()) * (y - op.y()) > radiusSquared) continue;
                BlockPos pos = new BlockPos(x, y, z);
                if (b.chunk.getBlockState(pos).getBlock() instanceof FurnitureBlock) {
                    throw new IllegalArgumentException("A set_block brush intersects a multipart assembly; remove the assembly explicitly first");
                }
                b.set(pos, target);
            }
        }
    }

    private void vegetation(Budget b, BiomeEditorProfile.Operation op, BlockState target, int index) {
        for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
            int x = b.chunk.getPos().getMinBlockX() + dx, z = b.chunk.getPos().getMinBlockZ() + dz;
            if (!selected(op, index, x, z)) continue;
            BlockPos soil = b.floor(x, z);
            if (soil == null) continue;
            BlockPos pos = soil.above();
            if (natureFurniture(target)) {
                FurnitureBlock furniture = (FurnitureBlock) target.getBlock();
                Direction facing = Direction.NORTH;
                List<BlockPos> positions = furniture.positions(pos, facing);
                boolean allowed = true;
                for (int part = 0; part < positions.size(); part++) {
                    BlockPos cell = positions.get(part);
                    if (!b.owns(cell) || !replaceableCover(b.chunk.getBlockState(cell))) { allowed = false; break; }
                    if (furniture.partOffset(part, facing).getY() == 0
                            && !b.chunk.getBlockState(cell.below()).isFaceSturdy(b.region, cell.below(), Direction.UP)) { allowed = false; break; }
                }
                if (!allowed) continue;
                boolean snowy = b.chunk.getBlockState(pos).is(Blocks.SNOW)
                        || b.chunk.getBlockState(soil).hasProperty(SnowyBlock.SNOWY)
                        && b.chunk.getBlockState(soil).getValue(SnowyBlock.SNOWY);
                for (int part = 0; part < positions.size(); part++) {
                    BlockState state = target.setValue(FurnitureBlock.FACING, facing).setValue(FurnitureBlock.PART, part);
                    if (state.hasProperty(SnowVariantFurnitureBlock.SNOWY)) state = state.setValue(SnowVariantFurnitureBlock.SNOWY, snowy);
                    b.set(positions.get(part), state);
                }
            } else {
                if (!b.owns(pos) || !replaceableCover(b.chunk.getBlockState(pos)) || !target.canSurvive(b.region, pos)) continue;
                if (target.getBlock() instanceof DoublePlantBlock) {
                    BlockPos upper = pos.above();
                    if (!b.owns(upper) || !replaceableCover(b.chunk.getBlockState(upper))) continue;
                    b.set(pos, target.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
                    b.set(upper, target.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
                } else b.set(pos, target);
            }
        }
    }

    private boolean selected(BiomeEditorProfile.Operation op, int index, int x, int z) {
        float distance = BiomeEditorTerrain.horizontalDistance(op, x, z), radius = BiomeEditorTerrain.horizontalRadius(op);
        if (distance >= radius) return false;
        float inner = Math.min(.999f, Math.max(0, 1 - op.falloff()));
        float t = Math.clamp((distance / radius - inner) / (1 - inner), 0f, 1f);
        float weight = (1 - t * t * (3 - 2 * t)) * Math.min(1, op.strength());
        long hash = profile.seed() ^ (long) x * 0x9E3779B97F4A7C15L ^ (long) z * 0xC2B2AE3D27D4EB4FL ^ index;
        hash = (hash ^ hash >>> 30) * 0xBF58476D1CE4E5B9L;
        hash = (hash ^ hash >>> 27) * 0x94D049BB133111EBL;
        return ((hash ^ hash >>> 31) >>> 40) * 0x1.0p-24f < weight;
    }

    private static boolean nativePlant(BlockState state) {
        return state.getBlock() instanceof VegetationBlock
                && BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft");
    }
    private static boolean natureFurniture(BlockState state) {
        if (!(state.getBlock() instanceof SnowVariantFurnitureBlock furniture)) return false;
        Identifier id = BuiltInRegistries.BLOCK.getKey(furniture);
        return id.getNamespace().equals("darkwinter") && NATURE_FURNITURE.contains(id.getPath())
                && !furniture.definition().parts().isEmpty() && furniture.definition().parts().size() <= FurnitureBlock.MAX_PARTS;
    }
    private static boolean replaceableCover(BlockState state) { return state.isAir() || state.is(Blocks.SNOW) || nativePlant(state); }
    private static boolean naturalSoil(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL) || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.STONE)
                || state.is(Blocks.ANDESITE) || state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE)
                || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY);
    }

    private static final class Budget {
        final WorldGenRegion region;
        final ChunkAccess chunk;
        final int[] floor = new int[256];
        int writes;
        Budget(WorldGenRegion region, ChunkAccess chunk) {
            this.region = region; this.chunk = chunk; Arrays.fill(floor, Integer.MIN_VALUE);
        }
        boolean owns(BlockPos pos) {
            return (pos.getX() >> 4) == chunk.getPos().x() && (pos.getZ() >> 4) == chunk.getPos().z()
                    && !region.isOutsideBuildHeight(pos) && region.ensureCanWrite(pos);
        }
        void set(BlockPos pos, BlockState state) {
            if (!owns(pos)) throw new IllegalArgumentException("Editor finish operation exceeds its fresh center chunk");
            if (chunk.getBlockState(pos).equals(state)) return;
            if (writes >= MAX_WRITES) throw new IllegalArgumentException("Editor finish operation exceeds 65536 writes per chunk");
            if (!region.setBlock(pos, state, FLAGS)) throw new IllegalStateException("Native generation rejected an editor block write");
            writes++;
            floor[(pos.getX() & 15) | (pos.getZ() & 15) << 4] = Integer.MIN_VALUE;
        }
        BlockPos floor(int x, int z) {
            int slot = (x & 15) | (z & 15) << 4;
            if (floor[slot] == Integer.MIN_VALUE) floor[slot] = floorY(x, z, false);
            return floor[slot] < region.getMinY() ? null : new BlockPos(x, floor[slot], z);
        }
        BlockPos waterFloor(int x, int z) {
            int y = floorY(x, z, true);
            return y < region.getMinY() ? null : new BlockPos(x, y, z);
        }
        private int floorY(int x, int z, boolean throughIce) {
            int top = Math.min(region.getMaxY(), chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15));
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int y = top; y >= region.getMinY(); y--) {
                cursor.set(x, y, z);
                BlockState state = chunk.getBlockState(cursor);
                if (naturalSoil(state)) return y;
                if (state.isAir() || state.is(Blocks.SNOW) || state.is(Blocks.WATER)
                        || throughIce && state.is(Blocks.ICE) || nativePlant(state)
                        || state.is(TestSpruceBlocks.LOG) || state.is(TestSpruceBlocks.BOUGH)
                        || natureFurniture(state)) continue;
                break;
            }
            return region.getMinY() - 1;
        }
    }
}
