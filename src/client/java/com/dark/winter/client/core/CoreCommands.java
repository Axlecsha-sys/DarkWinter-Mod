// Explicit development entry point. No native library, thread or world copy is created during normal play.
package com.dark.winter.client.core;

import com.dark.winter.core.CoreBridge;
import com.dark.winter.core.CoreImport;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class CoreCommands {
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private static final ExecutorService IO=new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,new SynchronousQueue<>(),action->{
        Thread thread=new Thread(action,"DarkWinter core control");thread.setDaemon(true);return thread;
    });
    private static volatile Map<String,Object> result=Map.of();
    private static volatile String status="Готово к явной проверке /dwcore inspect <файл.dwv>";
    private CoreCommands(){}
    public static Map<String,Object> lastResult(){return result;}

    public static void initialize() {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->IO.shutdownNow());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,access)->
            dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("dwcore")
                .requires(FabricClientCommandSource::attended)
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status").executes(context->{
                    context.getSource().sendFeedback(Component.literal("C++ ядро: "+status));return 1;
                }))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("inspect")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("file",StringArgumentType.word())
                        .executes(context->inspect(context.getSource(),StringArgumentType.getString(context,"file")))))));
    }
    private static int inspect(FabricClientCommandSource source,String name) {
        if(!name.matches("[a-zA-Z0-9_.-]+\\.dwv")||name.contains("..")) {
            source.sendError(Component.literal("Укажи имя .dwv из каталога core-worlds."));return 0;
        }
        if(!BUSY.compareAndSet(false,true)){source.sendError(Component.literal("Проверка ядра уже выполняется."));return 0;}
        Minecraft client=Minecraft.getInstance();var expectedPlayer=client.player;
        String configured=System.getProperty("darkwinter.core.worldRoot");
        Path folder=(configured==null?client.gameDirectory.toPath().resolve("core-worlds"):Path.of(configured)).toAbsolutePath().normalize();
        Path file=folder.resolve(name).normalize();
        result=Map.of();status="Проверяется "+name;
        try {
            IO.execute(()->{
                try {
                    if(!file.getParent().equals(folder)||Files.isSymbolicLink(file)||!Files.isRegularFile(file))throw new java.io.IOException("Файл ядра не найден");
                    long started=System.nanoTime();
                    Map<String,Object> completed;String completedStatus;
                    try(CoreBridge core=CoreBridge.open(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-2)),4096,4096)) {
                        core.load(file);core.prepareAll();var digest=MessageDigest.getInstance("SHA-256");
                        for(var key:core.index())digest.update(core.prepared(key));
                        var data=new LinkedHashMap<String,Object>();data.put("completed",true);data.put("file",name);
                        data.put("worldSha256",CoreImport.sha(file));data.put("occupancySha256",HexFormat.of().formatHex(digest.digest()));
                        data.put("native",core.statistics());data.put("wallMs",(System.nanoTime()-started)/1e6);
                        data.put("scope","Native geometry snapshot verification; Minecraft simulation and renderer are active");
                        completed=Collections.unmodifiableMap(data);
                        completedStatus="Проверено участков: "+core.stat(5)+", рабочих потоков: "+core.stat(0);
                    }
                    status=completedStatus;result=completed;
                    client.execute(()->{if(client.player==expectedPlayer)source.sendFeedback(Component.literal("C++ ядро: "+completedStatus));});
                } catch(Exception failure) {
                    String message="Ошибка: "+failure.getMessage();status=message;
                    com.dark.winter.DarkWinter.LOGGER.warn("Native core inspection failed",failure);
                    client.execute(()->{if(client.player==expectedPlayer)source.sendError(Component.literal("C++ ядро: "+message));});
                } finally {BUSY.set(false);}
            });
        } catch(RejectedExecutionException stopped) {
            BUSY.set(false);status="Проверка недоступна после закрытия клиента";
            source.sendError(Component.literal(status));return 0;
        }
        source.sendFeedback(Component.literal("Проверка C++ ядра начата."));return 1;
    }
}
