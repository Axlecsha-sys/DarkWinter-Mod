// Client-only attended switch. Never changes the server, gamerules or resource packs.
package com.dark.winter.client.fixes.voxel;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

final class VoxelFaceCommands {
    private VoxelFaceCommands() { }

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("dwvoxel")
                        .requires(FabricClientCommandSource::attended)
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
                                .executes(context -> {
                                    context.getSource().sendFeedback(Component.literal(
                                            "Voxel experiment: " + VoxelFaceRuntime.status()));
                                    return 1;
                                }))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("capture")
                                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument(
                                        "seconds", IntegerArgumentType.integer(5, 60))
                                        .executes(context -> {
                                            try {
                                                VoxelFaceRuntime.captureRebuild(
                                                        IntegerArgumentType.getInteger(context, "seconds"));
                                                context.getSource().sendFeedback(Component.literal(
                                                        "Voxel experiment: DV-032 capture started; a full-section rebuild "
                                                        + "is queued in 1 second after closing chat."));
                                                return 1;
                                            } catch (IllegalArgumentException | IllegalStateException failure) {
                                                context.getSource().sendError(Component.literal(
                                                        "Voxel experiment: " + failure.getMessage()));
                                                return 0;
                                            }
                                        })))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("rebuild")
                                .executes(context -> {
                                    var client = net.minecraft.client.Minecraft.getInstance();
                                    if (client.level == null) {
                                        context.getSource().sendError(Component.literal("Voxel experiment: load a world first."));
                                        return 0;
                                    }
                                    // A one-shot, explicit rebuild for controlled Java/C++ A/B checks.
                                    client.levelExtractor.allChanged();
                                    context.getSource().sendFeedback(Component.literal(
                                            "Voxel experiment: recompilation requested. Wait until chunks are ready before DV-032."));
                                    return 1;
                                }))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("mode")
                                .then(mode("java", VoxelFaceRuntime.Mode.JAVA))
                                .then(mode("cpp", VoxelFaceRuntime.Mode.CPP))
                                .then(mode("auto", VoxelFaceRuntime.Mode.AUTO)))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> mode(
            String literal, VoxelFaceRuntime.Mode mode) {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal(literal)
                .executes(context -> {
                    context.getSource().sendFeedback(Component.literal(
                            "Voxel experiment: " + VoxelFaceRuntime.setMode(mode)));
                    return 1;
                });
    }
}
