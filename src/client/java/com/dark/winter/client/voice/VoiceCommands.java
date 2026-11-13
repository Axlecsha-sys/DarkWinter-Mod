package com.dark.winter.client.voice;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.Arrays;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/** Local debug playback only; no command or sound is broadcast to a server. */
final class VoiceCommands {
    private VoiceCommands() {}
    private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String value) {
        return LiteralArgumentBuilder.literal(value);
    }
    private static RequiredArgumentBuilder<FabricClientCommandSource,String> argument(String value,StringArgumentType type) {
        return RequiredArgumentBuilder.argument(value,type);
    }
    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,access)->dispatcher.register(literal("dwvoice")
            .requires(source->source.attended())
            .executes(context->play(context.getSource(),ProtagonistVoiceClient.randomLine()))
            .then(literal("play").then(argument("replica",StringArgumentType.word())
                .suggests((context,builder)->SharedSuggestionProvider.suggest(Arrays.stream(VoiceLine.values()).map(line->line.id),builder))
                .executes(context->{
                    VoiceLine line=VoiceLine.find(StringArgumentType.getString(context,"replica"));
                    if(line==null){context.getSource().sendError(Component.literal("Неизвестная реплика. Список: /dwvoice list"));return 0;}
                    return play(context.getSource(),line);
                })))
            .then(literal("list").executes(context->{
                context.getSource().sendFeedback(Component.literal("Реплики: "+String.join(", ",Arrays.stream(VoiceLine.values()).map(line->line.id).toList())));
                return 1;
            }))
            .then(literal("stop").executes(context->{ProtagonistVoiceClient.stopDebug();return 1;}))
            .then(literal("status").executes(context->{context.getSource().sendFeedback(Component.literal(ProtagonistVoiceClient.status()));return 1;}))));
    }
    private static int play(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,VoiceLine line) {
        try {ProtagonistVoiceClient.requestDebug(line);return 1;}
        catch(IllegalStateException failure){source.sendError(Component.literal(failure.getMessage()));return 0;}
    }
}
