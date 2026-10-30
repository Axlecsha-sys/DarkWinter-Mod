// Authored calibration stand, installed only for a freshly-created TEST_MAP.
package com.dark.winter.testing;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Authored calibration stand, installed only for a freshly-created TEST_MAP. */
public final class TestingSampleMap {
    private TestingSampleMap() {}

    public static void populate(MinecraftServer server) {
        var world = server.overworld();
        if (!(world.getChunkSource().getGenerator() instanceof TestingIslandGenerator generator)) return;
        int y = generator.surfaceHeight(0, 26) + 2;
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (int x = -10; x <= 10; x++) for (int z = 18; z <= 38; z++)
            blocks.put(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        // Four parallel surfaces for walking, friction, footprints and step checks.
        BlockState[] surfaces = {Blocks.STONE.defaultBlockState(), Blocks.SNOW_BLOCK.defaultBlockState(),
                Blocks.PACKED_ICE.defaultBlockState(), Blocks.SOUL_SAND.defaultBlockState()};
        for (int lane = 0; lane < surfaces.length; lane++)
            for (int x = -8 + lane * 4; x < -5 + lane * 4; x++)
                for (int z = 20; z <= 29; z++) blocks.put(new BlockPos(x, y + 1, z), surfaces[lane]);
        for (int step = 0; step < 4; step++) for (int x = -7; x <= -5; x++)
            for (int h = 1; h <= step + 1; h++)
                blocks.put(new BlockPos(x, y + h, 32 + step), Blocks.STONE_BRICKS.defaultBlockState());
        // Opaque and transparent material panels, with a real DarkWinter object.
        for (int x = 1; x <= 8; x++) for (int h = 1; h <= 4; h++)
            blocks.put(new BlockPos(x, y + h, 36), (x < 5 ? Blocks.SPRUCE_PLANKS : Blocks.GLASS).defaultBlockState());
        var chair = BuiltInRegistries.BLOCK.getValue(Identifier.parse("darkwinter:wooden_chair"));
        if (chair != null && chair != Blocks.AIR) blocks.put(new BlockPos(4, y + 1, 33), chair.defaultBlockState());
        // This is not an import into an old world; the creation token already bound the new folder.
        for (var entry : blocks.entrySet()) {
            world.setBlockAndUpdate(entry.getKey(), entry.getValue());
            TestingServer.authored(world, entry.getKey());
        }
    }
}
