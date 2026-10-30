// Shared preflight for complete multipart selections; never mutates the world.
package com.dark.winter.testing;

import com.dark.winter.block.FurnitureBlock;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Shared preflight for complete multipart selections; never mutates the world. */
public final class TestingStructures {
    private TestingStructures() {}
    public static void validate(ServerLevel world, Set<BlockPos> selection) throws IOException {
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        Map<BlockPos, net.minecraft.nbt.CompoundTag> entities = new LinkedHashMap<>();
        for (BlockPos pos : selection) {
            if (!world.hasChunkAt(pos) || world.isOutsideBuildHeight(pos))
                throw new IOException("Незагруженная часть конструкции: " + pos.toShortString());
            states.put(pos, world.getBlockState(pos));
            var entity = world.getBlockEntity(pos);
            if (entity != null) entities.put(pos, entity.saveWithFullMetadata(world.registryAccess()));
        }
        validate(states, entities);
    }
    /** Relocate only supported absolute block-entity references; preserve the rest of its native payload. */
    public static net.minecraft.nbt.CompoundTag relocate(net.minecraft.nbt.CompoundTag source,
            java.util.function.UnaryOperator<BlockPos> transform) throws IOException {
        if (source == null) return null;
        var tag = source.copy();
        String id = tag.getStringOr("id", "");
        if (id.equals("minecraft:sculk_catalyst") && !tag.getListOrEmpty("cursors").isEmpty())
            throw new IOException("Заряженный скалк-катализатор переносится только с полным островом: активные позиции распространения");
        if (id.equals("minecraft:conduit") && tag.contains("Target"))
            throw new IOException("Морской проводник с активной целью переносится только с полным островом: связь UUID");
        if (Set.of("minecraft:end_gateway", "minecraft:structure_block", "minecraft:moving_piston", "minecraft:beehive").contains(id))
            throw new IOException("Перенос ссылок block entity пока не поддерживается: " + id);
        if (tag.getInt("x").isPresent() && tag.getInt("y").isPresent() && tag.getInt("z").isPresent()) {
            BlockPos position = transform.apply(new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0)));
            tag.putInt("x", position.getX()); tag.putInt("y", position.getY()); tag.putInt("z", position.getZ());
        }
        if (tag.getLong("MasterPos").isPresent()) tag.putLong("MasterPos", transform.apply(BlockPos.of(tag.getLongOr("MasterPos", 0))).asLong());
        return tag;
    }
    public static void validate(Map<BlockPos, BlockState> states, Map<BlockPos, net.minecraft.nbt.CompoundTag> entities) throws IOException {
        validate(states);
        for (var entry : entities.entrySet()) {
            var tag = entry.getValue();
            if (tag == null || tag.getLong("MasterPos").isEmpty()) continue;
            BlockPos master = BlockPos.of(tag.getLongOr("MasterPos", 0));
            String name = tag.getStringOr("WallShape", "1x1");
            com.dark.winter.component.WallShape shape = null;
            for (var value : com.dark.winter.component.WallShape.values()) if (value.getSerializedName().equals(name)) shape = value;
            BlockState state = states.get(entry.getKey());
            if (shape == null || state == null || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
                throw new IOException("Недопустимые данные модуля стены");
            var requiredPositions = shape.getPositions(master, state.getValue(BlockStateProperties.HORIZONTAL_FACING));
            if (!requiredPositions.contains(entry.getKey())) throw new IOException("Часть стены не принадлежит своему модулю");
            for (BlockPos required : requiredPositions) {
                var other = states.get(required); var otherTag = entities.get(required);
                if (other == null || !other.is(state.getBlock()) || otherTag == null
                        || other.getValue(BlockStateProperties.HORIZONTAL_FACING) != state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                        || otherTag.getLongOr("MasterPos", Long.MIN_VALUE) != master.asLong()
                        || !name.equals(otherTag.getStringOr("WallShape", "")))
                    throw new IOException("Выделите весь модуль стены: " + required.toShortString());
            }
        }
    }
    /** Relative coordinates are accepted, so the same validation covers prefab files. */
    public static void validate(Map<BlockPos, BlockState> states) throws IOException {
        var half = BlockStateProperties.DOUBLE_BLOCK_HALF;
        var bed = BlockStateProperties.BED_PART;
        var facing = BlockStateProperties.HORIZONTAL_FACING;
        for (var entry : states.entrySet()) {
            BlockPos pos = entry.getKey(); BlockState state = entry.getValue();
            if (state.hasProperty(half)) {
                boolean lower = state.getValue(half) == DoubleBlockHalf.LOWER;
                BlockState other = states.get(lower ? pos.above() : pos.below());
                if (other == null || !other.is(state.getBlock()) || !other.hasProperty(half)
                        || other.getValue(half) == state.getValue(half))
                    throw new IOException("Выделите целиком двухблочную конструкцию: " + pos.toShortString());
            }
            if (state.hasProperty(bed) && state.hasProperty(facing)) {
                boolean foot = state.getValue(bed) == BedPart.FOOT;
                BlockPos next = pos.relative(foot ? state.getValue(facing) : state.getValue(facing).getOpposite());
                BlockState other = states.get(next);
                if (other == null || !other.is(state.getBlock()) || !other.hasProperty(bed) || !other.hasProperty(facing)
                        || other.getValue(bed) == state.getValue(bed) || other.getValue(facing) != state.getValue(facing))
                    throw new IOException("Выделите обе части кровати: " + pos.toShortString());
            }
            if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos next = ChestBlock.getConnectedBlockPos(pos, state);
                BlockState other = states.get(next);
                if (other == null || !other.is(state.getBlock()) || !other.hasProperty(ChestBlock.TYPE)
                        || other.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                        || other.getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE)
                        || other.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING))
                    throw new IOException("Выделите обе половины двойного сундука: " + pos.toShortString());
            }
            if (state.getBlock() instanceof FurnitureBlock furniture) {
                int part = state.getValue(FurnitureBlock.PART);
                if (part >= furniture.definition().parts().size()) throw new IOException("Недопустимая часть мебели");
                var direction = state.getValue(FurnitureBlock.FACING);
                BlockPos origin = furniture.origin(pos, state);
                for (int index = 0; index < furniture.definition().parts().size(); index++) {
                    BlockPos required = origin.offset(furniture.partOffset(index, direction));
                    BlockState other = states.get(required);
                    if (other == null || !other.is(furniture) || other.getValue(FurnitureBlock.PART) != index
                            || other.getValue(FurnitureBlock.FACING) != direction)
                        throw new IOException("Выделите всю мебель «" + furniture.definition().ru() + "»: " + required.toShortString());
                }
            }
        }
    }
}
