// Conservative compatibility contract: gameplay definitions matter; cosmetic UI and build-number strings do not.
package com.dark.winter.online.network;

import com.dark.winter.online.OnlineJson;
import com.dark.winter.online.signaling.IslandJoinFailure;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;

/** Conservative compatibility contract: gameplay definitions matter; cosmetic UI and build-number strings do not. */
public final class OnlineCompatibility {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"dw-compatibility");t.setDaemon(true);return t;});
    private static final String[] GAME_CODE={
        "com/dark/winter/block","com/dark/winter/component","com/dark/winter/equipment",
        "com/dark/winter/registry","com/dark/winter/testing","com/dark/winter/worldgen",
        "com/dark/winter/weather","com/dark/winter/time","com/dark/winter/network",
        "com/dark/winter/online", // Authority, voice, multiplex and replication protocol.
        "com/dark/winter/client/online", // Actual stream ownership and admission must also match.
        "com/dark/winter/client/motion","com/dark/winter/client/equipment"
    };
    private static final String[] MODEL_DATA={"assets/darkwinter/models","assets/darkwinter/animations","data/darkwinter"};
    private OnlineCompatibility(){}
    public static CompletableFuture<JsonObject> build(){
        // Invoked after game registration is complete; built-in registries and state definitions are frozen.
        return CompletableFuture.supplyAsync(()->{
            try{return calculate();}catch(IOException failure){throw new CompletionException(failure);}
        },IO);
    }
    private static JsonObject calculate()throws IOException {
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            TreeSet<String> registry=new TreeSet<>();
            for(var block:BuiltInRegistries.BLOCK){
                String id=BuiltInRegistries.BLOCK.getKey(block).toString();
                registry.add("block:"+id+":"+block.getClass().getName());
                for(var property:block.getStateDefinition().getProperties()){
                    List<String> values=property.getPossibleValues().stream().map(Object::toString).sorted().toList();
                    registry.add("property:"+id+":"+property.getName()+":"+String.join(",",values));
                }
            }
            for(var item:BuiltInRegistries.ITEM)registry.add("item:"+BuiltInRegistries.ITEM.getKey(item)+":"+item.getClass().getName());
            for(var entity:BuiltInRegistries.ENTITY_TYPE)registry.add("entity:"+BuiltInRegistries.ENTITY_TYPE.getKey(entity));
            for(var component:BuiltInRegistries.DATA_COMPONENT_TYPE)registry.add("component:"+BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component));
            for(String entry:registry)part(digest,entry.getBytes(StandardCharsets.UTF_8));
            var mod=FabricLoader.getInstance().getModContainer("dark-winter").orElseThrow(()->new IOException("DarkWinter отсутствует в окружении"));
            SortedMap<String,Path> files=new TreeMap<>();
            for(Path root:mod.getRootPaths()){
                for(String prefix:GAME_CODE)collect(root,prefix,".class",files);
                for(String prefix:MODEL_DATA)collect(root,prefix,".json",files);
            }
            long total=0;
            for(var entry:files.entrySet()){
                long size=Files.size(entry.getValue());
                if(size>4*1024*1024||total+size>128*1024*1024)throw new IOException("Обязательные данные превышают допустимый размер");
                total+=size;part(digest,entry.getKey().getBytes(StandardCharsets.UTF_8));
                // No class loading, native initialization, resource downloads or rendering on this worker.
                part(digest,Files.readAllBytes(entry.getValue()));
            }
            if(files.isEmpty())throw new IOException("Не удалось проверить обязательные файлы DarkWinter");
            JsonObject result=new JsonObject();result.addProperty("schema",1);result.addProperty("protocol",OnlinePayload.VERSION);
            result.addProperty("minecraft",FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString());
            result.addProperty("dataContract",2);result.addProperty("definitionsSha256",HexFormat.of().formatHex(digest.digest()));
            result.addProperty("registryEntries",registry.size());result.addProperty("requiredFiles",files.size());
            result.addProperty("build",mod.getMetadata().getVersion().getFriendlyString());return result;
        }catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}
    }
    private static void collect(Path root,String prefix,String suffix,Map<String,Path> files)throws IOException {
        Path folder=root.resolve(prefix);
        if(!Files.isDirectory(folder))return;
        try(var stream=Files.walk(folder)){
            var paths=stream.limit(20001).toList();
            if(paths.size()>20000)throw new IOException("Слишком много обязательных файлов");
            for(Path path:paths){
                if(Files.isSymbolicLink(path))throw new IOException("Ссылка вместо обязательных данных");
                if(!Files.isRegularFile(path)||!path.getFileName().toString().endsWith(suffix))continue;
                if(Files.size(path)>4*1024*1024)throw new IOException("Обязательный файл слишком большой");
                String key=root.relativize(path).toString().replace('\\','/');
                Path previous=files.putIfAbsent(key,path);
                if(previous!=null&&!Arrays.equals(Files.readAllBytes(previous),Files.readAllBytes(path)))
                    throw new IOException("Конфликт обязательных данных: "+key);
                if(files.size()>20000)throw new IOException("Слишком много обязательных файлов");
            }
        }
    }
    private static void part(MessageDigest digest,byte[] bytes){
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
    }
    public static void requireCompatible(JsonObject local,JsonObject peer)throws IOException {
        if(OnlineJson.integer(peer,"schema",1,1)!=1)throw new IOException("Неизвестная схема совместимости");
        for(String field:List.of("protocol","dataContract"))if(OnlineJson.integer(local,field,1,65535)!=OnlineJson.integer(peer,field,1,65535))throw new IslandJoinFailure(IslandJoinFailure.Kind.PROTOCOL_MISMATCH);
        if(!OnlineJson.string(local,"minecraft",64).equals(OnlineJson.string(peer,"minecraft",64)))throw new IslandJoinFailure(IslandJoinFailure.Kind.MINECRAFT_MISMATCH);
        if(!OnlineJson.string(local,"definitionsSha256",64).equals(OnlineJson.string(peer,"definitionsSha256",64)))throw new IslandJoinFailure(IslandJoinFailure.Kind.BUILD_MISMATCH);
    }
    public static String digest(JsonObject manifest)throws IOException {
        String hash=OnlineJson.string(manifest,"definitionsSha256",64);
        if(!hash.matches("[0-9a-f]{64}"))throw new IOException("Некорректная контрольная сумма совместимости");return hash;
    }
}
