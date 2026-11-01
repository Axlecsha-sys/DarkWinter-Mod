package com.dark.winter.client.fixes.mesh;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class ChunkMeshCommands {
    private ChunkMeshCommands() { }
    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
            dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("dwmesh")
                .requires(FabricClientCommandSource::attended)
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
                    .executes(context -> {
                        context.getSource().sendFeedback(Component.literal("Chunk mesh: " + ChunkMeshRuntime.status()));
                        return 1;
                    }))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("mode")
                    .then(mode("vanilla", ChunkMeshRuntime.Mode.VANILLA))
                    .then(mode("java", ChunkMeshRuntime.Mode.JAVA_PACKED))
                    .then(mode("cpp", ChunkMeshRuntime.Mode.CPP_PACKED)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("rebuild")
                    .executes(context -> {
                        Minecraft client = Minecraft.getInstance();
                        if (client.level == null || ChunkMeshComparison.running()) {
                            context.getSource().sendError(Component.literal("Load a world and wait for the current comparison."));
                            return 0;
                        }
                        client.levelExtractor.allChanged();
                        context.getSource().sendFeedback(Component.literal("Chunk mesh: normal section rebuild requested."));
                        return 1;
                    }))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("compare")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("sections", IntegerArgumentType.integer(1, 4))
                        .executes(context -> {
                            try {
                                String status = ChunkMeshComparison.request(IntegerArgumentType.getInteger(context, "sections"));
                                context.getSource().sendFeedback(Component.literal("Chunk mesh comparison: " + status
                                        + ". This rebuild replays CPU compilation; FPS during it is not a benchmark."));
                                return 1;
                            } catch (IllegalStateException | IllegalArgumentException failure) {
                                context.getSource().sendError(Component.literal("Chunk mesh: " + failure.getMessage()));
                                return 0;
                            }
                        })))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> mode(String name, ChunkMeshRuntime.Mode mode) {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal(name).executes(context -> {
            try {
                context.getSource().sendFeedback(Component.literal("Chunk mesh: " + ChunkMeshRuntime.setMode(mode)
                        + ". Applies to subsequent compiles; /dwmesh rebuild refreshes existing meshes."));
                return 1;
            } catch (IllegalStateException | IllegalArgumentException failure) {
                context.getSource().sendError(Component.literal("Chunk mesh: " + failure.getMessage()));
                return 0;
            }
        });
    }
}
