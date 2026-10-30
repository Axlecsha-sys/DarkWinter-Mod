// Bounded native thumbnail uploads and asynchronous archive metadata reads; no world mutations.
package com.dark.winter.client;

import com.dark.winter.testing.TestingArchive;
import com.dark.winter.testing.TestingArchiveScope;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.storage.LevelResource;

/** Bounded native thumbnail uploads and asynchronous archive metadata reads; no world mutations. */
public final class TestingLibraryClient {
    public record Entry(String id,String name,String description,String category,long bytes,boolean hasThumbnail) {}
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread thread=new Thread(r,"DarkWinter Library Metadata");thread.setDaemon(true);return thread;});
    private static volatile List<Entry> entries=List.of();
    private static volatile String status="Обновите список библиотеки";
    private static volatile long revision;
    private static volatile boolean busy;
    private static Path current;
    private static long request;
    private static final LinkedHashMap<String,Identifier> textures=new LinkedHashMap<>(16,.75f,true);
    private static final Set<String> iconRequests=new HashSet<>(),missingIcons=new HashSet<>();
    private TestingLibraryClient(){}
    public static List<Entry> entries(){return entries;}
    public static String status(){return status;}
    public static long revision(){return revision;}
    public static boolean busy(){return busy;}
    public static void ensure(Minecraft client){
        if(client==null||client.getSingleplayerServer()==null)return;
        Path world=client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if(!world.equals(current))refresh(client);
    }
    public static void refresh(Minecraft client){
        if(client==null||client.getSingleplayerServer()==null)return;
        Path world=client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if(!world.equals(current)){entries=List.of();for(var texture:textures.values())client.getTextureManager().release(texture);textures.clear();iconRequests.clear();missingIcons.clear();revision++;}
        current=world;long generation=++request;busy=true;status="Чтение метаданных версий…";
        background(client,()->{
            try{
                List<Entry> next=TestingArchive.versions(world).stream()
                        .map(version->new Entry(version.id(),version.name(),version.description(),version.category(),version.bytes(),!version.thumbnail().isEmpty())).toList();
                reply(client,()->{if(generation==request){entries=List.copyOf(next);busy=false;status="Версий: "+next.size();revision++;}});
            }catch(Exception failure){reply(client,()->{if(generation==request){busy=false;status="Библиотека недоступна: "+failure.getMessage();revision++;}});}
        });
    }
    public static void update(Minecraft client,String id,String description,String category){
        if(busy){status="Дождитесь чтения библиотеки";return;}
        busy=true;status="Сохранение описания и категории…";
        background(client,()->{
            try{
                TestingArchive.updateMetadata(id,description,category);
                reply(client,()->{busy=false;status="Метаданные сохранены";refresh(client);});
            }catch(Exception failure){reply(client,()->{busy=false;status="Метаданные отклонены: "+failure.getMessage();revision++;});}
        });
    }
    public static String thumbnailStatus(Entry entry){
        if(missingIcons.contains(entry.id()))return "Миниатюра недоступна или отклонена проверкой.";
        return entry.hasThumbnail()?"Загрузка настоящей миниатюры icon.png…":"Снимок не содержит icon.png; доступен визуальный предпросмотр.";
    }
    public static Identifier thumbnail(Minecraft client,Entry entry){
        if(!entry.hasThumbnail()||missingIcons.contains(entry.id()))return null;
        Identifier existing=textures.get(entry.id());if(existing!=null)return existing;
        if(!iconRequests.add(entry.id()))return null;
        Path iconWorld=current;
        background(client,()->{
            try{
                byte[] bytes=TestingArchive.thumbnail(TestingArchive.read(entry.id()));
                reply(client,()->{
                    if(!Objects.equals(iconWorld,current))return;iconRequests.remove(entry.id());
                    if(bytes.length==0){missingIcons.add(entry.id());revision++;return;}
                    try{
                        Identifier id=Identifier.fromNamespaceAndPath("darkwinter","testing/library/"+entry.id());
                        client.getTextureManager().registerAndLoad(id,new SnapshotIcon(id,bytes));
                        textures.put(entry.id(),id);
                        while(textures.size()>8){var iterator=textures.entrySet().iterator();var oldest=iterator.next();client.getTextureManager().release(oldest.getValue());iterator.remove();}
                        revision++;
                    }catch(Exception failure){missingIcons.add(entry.id());status="Миниатюра недоступна: "+failure.getMessage();revision++;}
                });
            }catch(Exception failure){reply(client,()->{if(!Objects.equals(iconWorld,current))return;iconRequests.remove(entry.id());missingIcons.add(entry.id());status="Миниатюра отклонена: "+failure.getMessage();revision++;});}
        });
        return null;
    }
    private static void background(Minecraft client,Runnable task){
        try{
            Path world=client.getSingleplayerServer()==null?current:client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            TestingArchiveScope.requireWorld(world);TestingArchiveScope.Token context=TestingArchiveScope.capture().orElse(null);
            IO.execute(()->{try{if(context==null)task.run();else context.call(()->{task.run();return null;});}
                catch(Exception failure){client.execute(()->{busy=false;status="Контекст библиотеки недоступен";revision++;});}});
        }catch(IOException|java.io.UncheckedIOException denied){busy=false;status="Требуется контекст архива этого острова";revision++;}
    }
    private static void reply(Minecraft client,Runnable task){
        TestingArchiveScope.Token context=TestingArchiveScope.capture().orElse(null);
        client.execute(()->{try{if(context==null)task.run();else context.call(()->{task.run();return null;});}
            catch(IOException|java.io.UncheckedIOException denied){busy=false;status="Отложенный ответ библиотеки отменён";revision++;}});
    }
    private static final class SnapshotIcon extends ReloadableTexture {
        private final byte[] bytes;
        SnapshotIcon(Identifier id,byte[] bytes){super(id);this.bytes=bytes.clone();}
        @Override public TextureContents loadContents(ResourceManager resources)throws IOException {
            NativeImage image=NativeImage.read(new ByteArrayInputStream(bytes));
            if(image.getWidth()!=64||image.getHeight()!=64){image.close();throw new IOException("Размер native icon не 64×64");}
            return new TextureContents(image,new TextureMetadataSection(false,false,MipmapStrategy.MEAN,0));
        }
    }
}
