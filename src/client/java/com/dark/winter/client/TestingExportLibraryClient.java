// Indexes the shared export library without importing or mutating a world.
package com.dark.winter.client;

import com.dark.winter.testing.TestingArchive;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;

/** One shared export library across worlds. Reading this index never imports or changes a save. */
public final class TestingExportLibraryClient {
    public record Entry(String filename,String name,String description,String category,String created,long bytes,boolean hasThumbnail,String error) {
        public boolean valid(){return error.isEmpty();}
    }
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"DarkWinter Export Library");thread.setDaemon(true);return thread;});
    private static volatile List<Entry> entries=List.of();
    private static volatile boolean busy,initialized;
    private static volatile long revision;
    private static volatile String status="Обновите библиотеку файлов .dwisland";
    private static long generation;
    private TestingExportLibraryClient(){}
    public static List<Entry> entries(){return entries;}
    public static boolean busy(){return busy;}
    public static long revision(){return revision;}
    public static String status(){return status;}
    public static void ensure(Minecraft client){if(!initialized)refresh(client);}
    public static void refresh(Minecraft client) {
        if(client==null||busy)return;
        initialized=true;busy=true;long request=++generation;status="Чтение общей библиотеки .dwisland…";
        IO.execute(()->{
            try {
                var directory=TestingArchive.root().resolve("exports");List<java.nio.file.Path> files=List.of();
                if(Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(directory))
                    try(var stream=Files.list(directory)){files=stream.filter(path->path.getFileName().toString().endsWith(".dwisland")).sorted().limit(513).toList();}
                boolean limited=files.size()>512;var found=new ArrayList<Entry>();int rejected=0;
                for(var file:files.stream().limit(512).toList()) {
                    String filename=file.getFileName().toString();
                    try {
                        var version=TestingArchive.readExportMetadata(filename);
                        found.add(new Entry(filename,version.name(),version.description(),version.category(),version.created(),version.bytes(),!version.thumbnail().isEmpty(),""));
                    }catch(Exception error){rejected++;found.add(new Entry(filename,filename,"","general","",0,false,error.getMessage()==null?"Файл отклонён":error.getMessage()));}
                }
                int invalid=rejected;
                client.execute(()->{if(request==generation){entries=List.copyOf(found);busy=false;revision++;
                    status="Файлов: "+found.size()+"; отклонено: "+invalid+(limited?"; показаны первые 512":"")+". Метаданные проверены; полная SHA-проверка — перед импортом.";}});
            }catch(Exception error){client.execute(()->{if(request==generation){busy=false;revision++;status="Общая библиотека недоступна: "+error.getMessage();}});}
        });
    }
}
