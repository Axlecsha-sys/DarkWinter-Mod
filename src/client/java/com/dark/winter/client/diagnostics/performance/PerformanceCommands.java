// Registers an attended local diagnostic command without changing game or server settings.
package com.dark.winter.client.diagnostics.performance;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

final class PerformanceCommands {
    private PerformanceCommands() {}
    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
            dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("dwperf")
                .requires(FabricClientCommandSource::attended)
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("start")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource,Integer>argument("seconds", IntegerArgumentType.integer(2,60))
                        .executes(context -> {
                            try {
                                PerformanceClient.start("manual", "unverified", "manual", 65536,
                                        IntegerArgumentType.getInteger(context,"seconds") * 1000L);
                                PerformanceClient.notifyWhenComplete();
                                context.getSource().sendFeedback(Component.literal("DarkWinter: запись начата; закройте чат и продолжайте игру."));
                                return 1;
                            } catch (IllegalArgumentException | IllegalStateException failure) {
                                context.getSource().sendError(Component.literal("DarkWinter: " + failure.getMessage()));
                                return 0;
                            }
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop").executes(context -> {
                    if (!PerformanceClient.isCapturing()) {
                        context.getSource().sendError(Component.literal("DarkWinter: запись не запущена."));
                        return 0;
                    }
                    var report = PerformanceClient.stop("manual");
                    PerformanceClient.export(report);
                    context.getSource().sendFeedback(Component.literal("DarkWinter: запись завершена; отчёт сохраняется в performance-reports."));
                    return 1;
                }))));
    }
}
