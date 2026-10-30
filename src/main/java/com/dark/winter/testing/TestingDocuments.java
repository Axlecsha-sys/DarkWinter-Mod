// Immutable metadata snapshots participate in the same WAL as blocks, entities and scene nodes.
package com.dark.winter.testing;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Immutable metadata snapshots participate in the same WAL as blocks, entities and scene nodes. */
public final class TestingDocuments {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_BYTES=16*1024*1024;
    record Prepared(String before,String after) {}
    private TestingDocuments() {}
    private static Path directory(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT).resolve("data/darkwinter-testing/document-history");}
    public static JsonObject capture(MinecraftServer server)throws IOException {return capture(server,Set.of("settings","scenarios"));}
    public static JsonObject capture(MinecraftServer server,Set<String> fields)throws IOException {
        if(fields.isEmpty()||!Set.of("settings","scenarios","biomes","zones","spawn").containsAll(fields))throw new IOException("Неизвестная категория документов");
        if(server==null||!server.isSameThread()||TestingServer.context(server)==null)throw new IOException("Нет серверного контекста документов");
        JsonObject root=new JsonObject();root.addProperty("schema",1);
        if(fields.contains("settings")) {
            JsonObject settings=JSON.toJsonTree(TestingServer.settings(server)).getAsJsonObject();
            settings.remove("mode");settings.remove("biome");root.add("settings",settings);
        }
        if(fields.contains("scenarios"))root.add("scenarios",TestingScenarios.exportDocuments(server));
        if(fields.contains("biomes"))root.add("biomes",TestingBiomeDocuments.capture(server));
        if(fields.contains("zones"))root.add("zones",TestingWorldDocuments.captureZones(server));
        if(fields.contains("spawn"))root.add("spawn",TestingWorldDocuments.captureSpawn(server));
        return root;
    }
    public static void validate(MinecraftServer server,JsonObject root)throws IOException {
        try {
            if(root==null||root.get("schema").getAsInt()!=1||root.size()<2||root.size()>6||!Set.of("schema","settings","scenarios","biomes","zones","spawn").containsAll(root.keySet()))
                throw new IOException("Неподдерживаемый формат документов острова");
            if(JSON.toJson(root).getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IOException("Документы превышают 16 МиБ");
            if(root.has("settings")) {
                JsonObject settings=root.getAsJsonObject("settings");
                if(settings.has("mode")||settings.has("biome"))throw new IOException("Режим и биом не являются параметрами переносимого профиля");
                TestingSettings candidate=JSON.fromJson(settings,TestingSettings.class);candidate.validate();
            }
            if(root.has("scenarios"))TestingScenarios.validateDocuments(server,root.getAsJsonObject("scenarios"));
            if(root.has("biomes"))TestingBiomeDocuments.validate(server,root.getAsJsonObject("biomes"));
            if(root.has("zones"))TestingWorldDocuments.validateZones(server,root.getAsJsonObject("zones"));
            if(root.has("spawn"))TestingWorldDocuments.validateSpawn(server,root.getAsJsonObject("spawn"));
        }catch(RuntimeException error){throw new IOException("Документы отклонены до изменения мира",error);}
    }
    public static String store(MinecraftServer server,JsonObject root)throws IOException {
        validate(server,root);
        byte[] bytes=JSON.toJson(canonical(root)).getBytes(StandardCharsets.UTF_8);String hash=hash(bytes);
        Path file=directory(server).resolve(hash+".json");
        if(Files.exists(file)){read(server,hash);return hash;}
        Files.createDirectories(file.getParent());
        Path temporary=Files.createTempFile(file.getParent(),"documents-",".tmp");
        try {
            Files.write(temporary,bytes,StandardOpenOption.TRUNCATE_EXISTING);
            try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
            try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,file);}
            catch(FileAlreadyExistsException collision){read(server,hash);}
        }finally{Files.deleteIfExists(temporary);}
        return hash;
    }
    static JsonObject read(MinecraftServer server,String ref)throws IOException {
        if(ref==null||!ref.matches("[0-9a-f]{64}"))throw new IOException("Неверная ссылка документов");
        Path file=directory(server).resolve(ref+".json");
        if(!Files.isRegularFile(file)||Files.size(file)>MAX_BYTES)throw new IOException("Снимок документов отсутствует или слишком велик");
        byte[] bytes=Files.readAllBytes(file);if(!hash(bytes).equals(ref))throw new IOException("SHA-256 документов не совпадает");
        try {JsonObject data=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();validate(server,data);return data;}
        catch(RuntimeException error){throw new IOException("Повреждён снимок документов",error);}
    }
    static Prepared prepare(MinecraftServer server,String expectedBefore,String after)throws IOException {
        if(after==null){if(expectedBefore!=null)throw new IOException("Неполная операция документов");return new Prepared(null,null);}
        JsonObject desired=read(server,after);
        if(desired.has("scenarios")&&(TestingScenarios.running(server)||TestingScenarios.recording(server)))
            throw new IOException("Остановите сценарий и запись перед заменой определений");
        Set<String> fields=new HashSet<>(desired.keySet());fields.remove("schema");fields.remove("biomes");
        // Biome Undo may intentionally cover an older, smaller footprint.
        // Capture exactly those quart palettes; never load/capture unrelated new chunks first.
        JsonObject currentData=fields.isEmpty()?new JsonObject():capture(server,fields);
        currentData.addProperty("schema",1);
        if(desired.has("biomes"))currentData.add("biomes",TestingBiomeDocuments.captureMatching(server,desired.getAsJsonObject("biomes")));
        String current=store(server,currentData);
        if(expectedBefore!=null&&!current.equals(expectedBefore))throw new IOException("Документы изменились до применения операции");
        return current.equals(after)?new Prepared(null,null):new Prepared(current,after);
    }
    static String withBiomeTarget(MinecraftServer server,String ref,String biome)throws IOException {
        JsonObject desired=ref==null?new JsonObject():read(server,ref);
        desired.addProperty("schema",1);
        if(!desired.has("biomes"))desired.add("biomes",TestingBiomeDocuments.uniform(server,biome));
        return store(server,desired);
    }
    static boolean hasBiomes(MinecraftServer server,String ref)throws IOException{return ref!=null&&read(server,ref).has("biomes");}
    static Set<net.minecraft.world.level.ChunkPos> requiredChunks(MinecraftServer server,String ref)throws IOException {
        if(ref==null)return Set.of();JsonObject data=read(server,ref);
        return data.has("biomes")?TestingBiomeDocuments.requiredChunks(server,data.getAsJsonObject("biomes")):Set.of();
    }
    static void apply(MinecraftServer server,String ref)throws IOException {
        if(ref==null)return;
        JsonObject data=read(server,ref);var context=TestingServer.context(server);
        if(context==null)throw new IOException("Контекст документов недоступен");
        if(data.has("scenarios"))TestingScenarios.applyDocuments(server,data.getAsJsonObject("scenarios"));
        if(data.has("biomes"))TestingBiomeDocuments.apply(server,data.getAsJsonObject("biomes"));
        if(data.has("zones"))TestingWorldDocuments.applyZones(server,data.getAsJsonObject("zones"));
        if(data.has("spawn"))TestingWorldDocuments.applySpawn(server,data.getAsJsonObject("spawn"));
        if(data.has("settings")) {
            TestingSettings current=context.settings,next=JSON.fromJson(data.get("settings"),TestingSettings.class);
            next.validate();next.mode=current.mode;next.biome=current.biome;context.settings=next;
            for(var player:server.getPlayerList().getPlayers()){
                player.getAbilities().setFlyingSpeed(next.flySpeed);player.onUpdateAbilities();
            }
        }
    }
    public static long collectUnused(MinecraftServer server,Set<String> retained,boolean confirmed)throws IOException {
        if(!confirmed)throw new IOException("Подтвердите очистку документов");
        Path dir=directory(server);if(!Files.isDirectory(dir))return 0;long freed=0;
        try(var files=Files.list(dir)){
            for(Path file:files.toList()) {
                String name=file.getFileName().toString();
                if(!name.matches("[0-9a-f]{64}\\.json")||retained.contains(name.substring(0,64)))continue;
                freed+=Files.size(file);Files.delete(file);
            }
        }return freed;
    }
    private static JsonElement canonical(JsonElement value) {
        if(value.isJsonObject()) {
            JsonObject sorted=new JsonObject();
            for(String key:new TreeSet<>(value.getAsJsonObject().keySet()))sorted.add(key,canonical(value.getAsJsonObject().get(key)));
            return sorted;
        }
        if(value.isJsonArray()){JsonArray array=new JsonArray();for(JsonElement child:value.getAsJsonArray())array.add(canonical(child));return array;}
        return value;
    }
    private static String hash(byte[] bytes)throws IOException {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException error){throw new IOException(error);}
    }
}
