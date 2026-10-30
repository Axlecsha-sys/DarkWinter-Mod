// Explicit portable Prefab IO stays off the render thread and never mutates the open world.
package com.dark.winter.client;

import com.dark.winter.testing.TestingArchive;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;

/** Explicit portable Prefab IO stays off the render thread and never mutates the open world. */
public final class TestingPrefabTransferClient {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"DarkWinter Prefab Transfer");t.setDaemon(true);return t;});
    private static volatile String status="Пакеты Prefab: .dwprefab";
    private static volatile List<String> imports=List.of();
    private static volatile boolean busy;
    private static volatile long revision;
    private static long refreshed;
    private TestingPrefabTransferClient(){}
    public static String status(){return status;}
    public static boolean busy(){return busy;}
    public static long revision(){return revision;}
    public static List<String> imports(){return imports;}
    public static void refresh(Minecraft client){
        long now=System.nanoTime();if(now-refreshed<2_000_000_000L)return;refreshed=now;
        IO.execute(()->{
            try{
                Path root=TestingArchive.root().resolve("imports");List<String> files=List.of();
                if(Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(root))try(var paths=Files.list(root)){
                    files=paths.filter(path->Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path)&&path.getFileName().toString().endsWith(".dwprefab"))
                            .map(path->path.getFileName().toString()).sorted().limit(128).toList();
                }
                List<String> next=files;client.execute(()->{if(!next.equals(imports)){imports=next;revision++;}});
            }catch(Exception failure){client.execute(()->{status="Список пакетов недоступен: "+failure.getMessage();revision++;});}
        });
    }
    public static void export(Minecraft client,String id){
        if(busy)return;if(id==null||id.isBlank()){status="Выберите сохранённый Prefab в списке";revision++;return;}
        busy=true;status="Проверка и экспорт Prefab…";
        IO.execute(()->{try{Path output=TestingPrefabs.exportPortable(id);client.execute(()->{busy=false;status="Экспортирован: archive/exports/"+output.getFileName();revision++;});}
            catch(Exception failure){client.execute(()->{busy=false;status="Экспорт отклонён: "+failure.getMessage();revision++;});}});
    }
    public static void importPackage(Minecraft client,String filename,java.util.function.Consumer<String> selected){
        if(busy)return;busy=true;status="Проверка импортируемого пакета…";
        IO.execute(()->{try{String id=TestingPrefabs.importPortable(filename);client.execute(()->{busy=false;status="Импортирован независимый Prefab: "+id;revision++;selected.accept(id);});}
            catch(Exception failure){client.execute(()->{busy=false;status="Импорт отклонён: "+failure.getMessage();revision++;});}});
    }
}
