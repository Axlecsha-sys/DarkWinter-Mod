// Pure dependency preparation is I/O-only; native validation consumes one bounded verified level payload at a time.
package com.dark.winter.testing;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelResource;

/** Pure dependency preparation is I/O-only; native validation consumes one bounded verified level payload at a time. */
public final class TestingScenarioTransfers {
    private static final long CATEGORY_LIMIT=128L*1024*1024,LEVEL_LIMIT=64L*1024*1024;
    private TestingScenarioTransfers(){}
    public static final class Prepared {
        private final Path source,target;
        private final String sourceHash,root;
        private final JsonObject original;
        private final TestingScenarioDependencies.Plan plan;
        private final List<TestingArchive.Entry> levels;
        private Prepared(Path source,Path target,String sourceHash,String root,JsonObject original,TestingScenarioDependencies.Plan plan){
            this.source=source;this.target=target;this.sourceHash=sourceHash;this.root=root;this.original=original.deepCopy();this.plan=plan;
            Map<String,TestingArchive.Entry> unique=new LinkedHashMap<>();
            for(var version:plan.versions()){
                var entry=version.files().stream().filter(file->file.path().equals("level.dat")).findFirst()
                        .orElseThrow(()->new IllegalArgumentException("В зависимости нет level.dat: "+version.id()));
                unique.putIfAbsent(entry.sha256(),entry);
            }levels=List.copyOf(unique.values());
        }
        public TestingScenarioDependencies.Plan plan(){return plan;}
        public Path source(){return source;}
        public String sourceHash(){return sourceHash;}
        public int levels(){return levels.size();}
        public boolean matches(Path source,Path target,JsonObject document,String root){
            return this.source.equals(source.toAbsolutePath().normalize())&&this.target.equals(target.toAbsolutePath().normalize())
                    &&this.root.equals(root==null?"":root)&&original.equals(document);
        }
    }
    /** Same exact bytes are hashed and parsed; this method never receives a server or world object. */
    public static Prepared prepare(Path source,Path target)throws IOException {
        TestingArchiveScope.requireWorld(target);
        source=source.toAbsolutePath().normalize();target=target.toAbsolutePath().normalize();
        Path allowed=TestingArchive.root().resolve("transfers");
        if(!source.getParent().equals(allowed)||Files.isSymbolicLink(source)||!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Недопустимый источник сценариев");
        byte[] bytes=read(source,CATEGORY_LIMIT);String hash=TestingArchive.hashBytes(bytes);
        JsonObject root;
        try{root=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();}
        catch(RuntimeException error){throw new IOException("Повреждён источник сценариев",error);}
        if(!root.has("document")||!root.get("document").isJsonObject())throw new IOException("Нет документа сценариев");
        JsonObject document=root.getAsJsonObject("document");
        String dependency=root.has("dependencyRoot")?root.get("dependencyRoot").getAsString():"";
        var plan=TestingScenarioDependencies.planTransfer(document,dependency,target);
        if(!hash.equals(TestingArchive.hash(source)))throw new IOException("Источник изменился во время подготовки");
        try{return new Prepared(source,target,hash,dependency,document,plan);}
        catch(RuntimeException error){throw new IOException("Повреждена зависимость сценария",error);}
    }
    public static final class LevelPayload {
        private final String hash;private final byte[] bytes;
        private LevelPayload(String hash,byte[] bytes){this.hash=hash;this.bytes=bytes;}
    }
    /** Disk reads and payload hashing run on the bounded I/O worker. */
    public static LevelPayload readLevel(Prepared prepared,int index)throws IOException {
        var entry=prepared.levels.get(index);if(entry.size()>LEVEL_LIMIT)throw new IOException("level.dat зависимости превышает 64 МиБ");
        byte[] bytes=read(TestingArchive.verifiedBlob(entry),LEVEL_LIMIT);
        if(!TestingArchive.hashBytes(bytes).equals(entry.sha256()))throw new IOException("Native данные изменились во время чтения");
        return new LevelPayload(entry.sha256(),bytes);
    }
    /** No filesystem I/O; call at most once per server tick. */
    public static void validateLevel(MinecraftServer server,Prepared prepared,int index,LevelPayload payload)throws IOException {
        authority(server,prepared);
        if(!prepared.levels.get(index).sha256().equals(payload.hash))throw new IOException("Перепутан native payload зависимости");
        var nativeRoot=NbtIo.readCompressed(new ByteArrayInputStream(payload.bytes),NbtAccounter.create(16L*1024*1024));
        var data=nativeRoot.getCompound("Data").orElseThrow(()->new IOException("В зависимости нет Data"));
        if(data.getIntOr("DataVersion",-1)!=net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version())
            throw new IOException("DataVersion зависимости сценария не соответствует текущему Minecraft");
    }
    public static void validateDocuments(MinecraftServer server,Prepared prepared)throws IOException {
        authority(server,prepared);TestingScenarios.validateDocuments(server,prepared.plan.document());
    }
    private static void authority(MinecraftServer server,Prepared prepared)throws IOException {
        if(server==null||!server.isSameThread()||!TestingServer.isTesting(server)||!server.isSingleplayer()||(server.isPublished()&&!TestingActors.managedScope(server))
                ||!server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(prepared.target))
            throw new IOException("Native проверка относится к другому авторскому миру");
    }
    private static byte[] read(Path file,long limit)throws IOException {
        if(Files.size(file)>limit)throw new IOException("Источник превышает допустимый размер");
        try(var input=Files.newInputStream(file);var output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[65536];long size=0;for(int count;(count=input.read(buffer))>=0;){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Подготовка отменена");
                if((size+=count)>limit)throw new IOException("Источник вырос сверх допустимого размера");
                if(count>0)output.write(buffer,0,count);
            }return output.toByteArray();
        }
    }
    /** Synchronous full-payload preparation is intentionally unavailable on the server thread. */
    @Deprecated
    public static TestingScenarioDependencies.Plan plan(MinecraftServer server,JsonObject document,String root)throws IOException {
        throw new IOException("Сценарии с зависимостями требуют асинхронной подготовки через библиотеку");
    }
}
