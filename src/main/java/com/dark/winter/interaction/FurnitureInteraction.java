package com.dark.winter.interaction;

import com.dark.winter.block.CurtainedWindowBlock;
import com.dark.winter.block.DiningChairBlock;
import com.dark.winter.block.PanelDoorBlock;
import com.dark.winter.block.FenceGateFurnitureBlock;
import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.StorageFurnitureBlock;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class FurnitureInteraction {
    private FurnitureInteraction() {}

    /** Validate the shoulder camera's aim point from the actual player, including obstructions. */
    static @Nullable BlockPos visiblePart(Player player, Vec3 aim) {
        if (!Double.isFinite(aim.x) || !Double.isFinite(aim.y) || !Double.isFinite(aim.z)) return null;
        Vec3 eye = player.getEyePosition(1), delta = aim.subtract(eye);
        double distance = delta.length();
        if (distance < 1e-6 || distance > player.blockInteractionRange() + .03) return null;
        var hit = player.level().clip(new ClipContext(eye, aim.add(delta.scale(.03 / distance)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    static boolean setAssemblyState(ServerLevel level, FurnitureBlock block, BlockPos root,
                                    List<BlockPos> parts, BlockState before, BlockState after) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;
        return block.mutate(level, root, () -> {
            // Change companions before the root that owns the transition.
            for (int i = parts.size() - 1; i >= 0; i--) {
                if (level.setBlock(parts.get(i), after.setValue(FurnitureBlock.PART, i), flags)) continue;
                for (int j = i + 1; j < parts.size(); j++) {
                    level.setBlock(parts.get(j), before.setValue(FurnitureBlock.PART, j), flags);
                }
                return false;
            }
            return true;
        });
    }

    public static void initialize() {
        restoreMissingEntities(state -> PanelDoorBlock.isRoot(state) || CurtainedWindowBlock.isRoot(state)
                || FenceGateFurnitureBlock.isRoot(state) || StorageFurnitureBlock.isRoot(state) || DiningChairBlock.isRoot(state));
    }

    private static void restoreMissingEntities(Predicate<BlockState> isRoot) {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            var sections = chunk.getSections();
            var pos = new BlockPos.MutableBlockPos();
            for (int section = 0; section < sections.length; section++) {
                var blocks = sections[section];
                if (!blocks.maybeHas(isRoot)) continue;
                int baseY = chunk.getSectionYFromSectionIndex(section) * 16;
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    if (!isRoot.test(blocks.getBlockState(x, y, z))) continue;
                    pos.set(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z);
                    if (chunk.getBlockEntity(pos) == null) {
                        chunk.getBlockEntity(pos.immutable(), LevelChunk.EntityCreationType.IMMEDIATE);
                        chunk.markUnsaved();
                    }
                }
            }
        });
    }
}
