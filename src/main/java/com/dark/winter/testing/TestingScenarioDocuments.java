// Portable definition metadata, validated before a Prefab's native geometry transaction.
package com.dark.winter.testing;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/** Portable definition metadata, validated before a Prefab's native geometry transaction. */
public final class TestingScenarioDocuments {
    private TestingScenarioDocuments(){}
    public static JsonObject capture(MinecraftServer server) throws IOException{return TestingScenarios.exportDocuments(server);}
    public static void validate(MinecraftServer server,JsonObject payload) throws IOException{TestingScenarios.validateDocuments(server,payload);}
    public static void apply(MinecraftServer server,JsonObject payload) throws IOException{TestingScenarios.applyDocuments(server,payload);}
    /** Relative coordinates; only spatially bound scenarios whose referenced devices fit are included. */
    public static JsonObject exportArea(MinecraftServer server,BlockPos first,BlockPos second) throws IOException {
        BlockPos min=new BlockPos(Math.min(first.getX(),second.getX()),Math.min(first.getY(),second.getY()),Math.min(first.getZ(),second.getZ()));
        BlockPos max=new BlockPos(Math.max(first.getX(),second.getX()),Math.max(first.getY(),second.getY()),Math.max(first.getZ(),second.getZ()));
        JsonObject result=capture(server),devices=result.getAsJsonObject("devices");
        Set<String> zones=new HashSet<>(),spawners=new HashSet<>();
        JsonArray selectedZones=new JsonArray(),selectedSpawners=new JsonArray();
        for(JsonElement row:devices.getAsJsonArray("triggers")){
            JsonObject zone=row.getAsJsonObject();
            if(contains(zone,min,max)){selectedZones.add(zone.deepCopy());zones.add(zone.get("name").getAsString());}
        }
        for(JsonElement row:devices.getAsJsonArray("spawners")){
            JsonObject spawn=row.getAsJsonObject();double x=spawn.get("x").getAsDouble(),y=spawn.get("y").getAsDouble(),z=spawn.get("z").getAsDouble();
            if(x>=min.getX()&&x<max.getX()+1d&&y>=min.getY()&&y<max.getY()+1d&&z>=min.getZ()&&z<max.getZ()+1d){
                selectedSpawners.add(spawn.deepCopy());spawners.add(spawn.get("name").getAsString());
            }
        }
        devices.add("triggers",selectedZones);devices.add("spawners",selectedSpawners);
        JsonArray scenarios=new JsonArray();
        for(JsonElement row:result.getAsJsonArray("scenarios")){
            JsonObject scenario=row.getAsJsonObject();boolean bound=false,complete=true;
            Set<String> usedZones=new HashSet<>(),usedSpawners=new HashSet<>();
            for(JsonElement eventRow:scenario.getAsJsonArray("events")){
                JsonObject event=eventRow.getAsJsonObject();String when=event.get("when").getAsString();
                if(when.startsWith("enter@")){bound=true;String name=when.substring(6);usedZones.add(name);complete&=zones.contains(name);}
                if(event.get("action").getAsString().equals("spawn")){
                    bound=true;String name=event.get("value").getAsString();usedSpawners.add(name);complete&=spawners.contains(name);
                }
            }
            JsonObject originals=scenario.getAsJsonObject("devices");
            for(JsonElement zone:originals.getAsJsonArray("triggers"))if(usedZones.contains(zone.getAsJsonObject().get("name").getAsString()))
                complete&=contains(zone.getAsJsonObject(),min,max);
            for(JsonElement rowSpawn:originals.getAsJsonArray("spawners"))if(usedSpawners.contains(rowSpawn.getAsJsonObject().get("name").getAsString())){
                JsonObject spawn=rowSpawn.getAsJsonObject();double x=spawn.get("x").getAsDouble(),y=spawn.get("y").getAsDouble(),z=spawn.get("z").getAsDouble();
                complete&=x>=min.getX()&&x<max.getX()+1d&&y>=min.getY()&&y<max.getY()+1d&&z>=min.getZ()&&z<max.getZ()+1d;
            }
            if(bound&&complete){
                JsonObject copy=scenario.deepCopy(),embedded=copy.getAsJsonObject("devices");
                embedded.add("triggers",filter(embedded.getAsJsonArray("triggers"),usedZones));
                embedded.add("spawners",filter(embedded.getAsJsonArray("spawners"),usedSpawners));
                JsonObject replay=copy.has("replay")&&!copy.get("replay").isJsonNull()?copy.getAsJsonObject("replay"):null;
                if(replay!=null&&(!replay.get("baselineVersionId").getAsString().isBlank()||!replay.getAsJsonArray("checkpoints").isEmpty()))
                    throw new IOException("Выбранный сценарий содержит контрольные точки полного мира. Для переноса зависимостей используйте .dwisland; локальный Prefab не заменяет состояние мира.");
                scenarios.add(copy);
            }
        }
        result.add("scenarios",scenarios);
        return TestingScenarios.relocateDocuments(server,result,min,BlockPos.ZERO,0);
    }
    /** Returns a full merged replacement snapshot, ready for the same WAL as blocks/NPCs. No writes. */
    public static JsonObject prepareArea(MinecraftServer server,JsonObject relative,BlockPos target,int sizeX,int sizeZ,int turns) throws IOException {
        return prepareArea(server,relative,target,sizeX,64,sizeZ,turns);
    }
    public static JsonObject prepareArea(MinecraftServer server,JsonObject relative,BlockPos target,int sizeX,int sizeY,int sizeZ,int turns) throws IOException {
        if(sizeX<1||sizeY<1||sizeZ<1||sizeX>64||sizeY>64||sizeZ>64)throw new IOException("Габариты Prefab-устройств 1–64");
        validate(server,relative);bounds(relative.getAsJsonObject("devices"),sizeX,sizeY,sizeZ);
        for(JsonElement scenario:relative.getAsJsonArray("scenarios"))bounds(scenario.getAsJsonObject().getAsJsonObject("devices"),sizeX,sizeY,sizeZ);
        int quarter=Math.floorMod(turns,4);
        BlockPos origin=target.offset(switch(quarter){
            case 1->new BlockPos(sizeZ-1,0,0);case 2->new BlockPos(sizeX-1,0,sizeZ-1);
            case 3->new BlockPos(0,0,sizeX-1);default->BlockPos.ZERO;
        });
        String prefix="pf_"+digest(relative.toString()+"|"+target+"|"+quarter).substring(0,12)+"_";
        JsonObject incoming=TestingScenarios.relocateDocuments(server,relative,BlockPos.ZERO,origin,quarter,prefix);
        JsonObject merged=capture(server);
        merge(merged.getAsJsonObject("devices").getAsJsonArray("triggers"),incoming.getAsJsonObject("devices").getAsJsonArray("triggers"));
        merge(merged.getAsJsonObject("devices").getAsJsonArray("spawners"),incoming.getAsJsonObject("devices").getAsJsonArray("spawners"));
        merge(merged.getAsJsonArray("scenarios"),incoming.getAsJsonArray("scenarios"));
        // Scenario export is name-sorted. Store that same order in the WAL target so
        // its native apply/export round trip cannot create a false metadata conflict.
        List<JsonElement> ordered=new ArrayList<>();merged.getAsJsonArray("scenarios").forEach(ordered::add);
        ordered.sort(Comparator.comparing(row->row.getAsJsonObject().get("name").getAsString()));
        JsonArray scenarios=new JsonArray();ordered.forEach(scenarios::add);merged.add("scenarios",scenarios);
        validate(server,merged);return merged;
    }
    private static void bounds(JsonObject devices,int sx,int sy,int sz) throws IOException {
        BlockPos min=BlockPos.ZERO,max=new BlockPos(sx-1,sy-1,sz-1);
        for(JsonElement row:devices.getAsJsonArray("triggers"))if(!contains(row.getAsJsonObject(),min,max))
            throw new IOException("Зона сценария выходит за границы Prefab");
        for(JsonElement row:devices.getAsJsonArray("spawners")){
            JsonObject spawn=row.getAsJsonObject();double x=spawn.get("x").getAsDouble(),y=spawn.get("y").getAsDouble(),z=spawn.get("z").getAsDouble();
            if(x<0||x>=sx||y<0||y>=sy||z<0||z>=sz)throw new IOException("Спавнер выходит за границы Prefab");
        }
    }
    private static boolean contains(JsonObject zone,BlockPos min,BlockPos max){
        return zone.get("x0").getAsInt()>=min.getX()&&zone.get("y0").getAsInt()>=min.getY()&&zone.get("z0").getAsInt()>=min.getZ()
                &&zone.get("x1").getAsInt()<=max.getX()&&zone.get("y1").getAsInt()<=max.getY()&&zone.get("z1").getAsInt()<=max.getZ();
    }
    private static JsonArray filter(JsonArray source,Set<String> names){
        JsonArray result=new JsonArray();for(JsonElement row:source)if(names.contains(row.getAsJsonObject().get("name").getAsString()))result.add(row.deepCopy());return result;
    }
    private static void merge(JsonArray destination,JsonArray source) throws IOException{
        Set<String> names=new HashSet<>();for(JsonElement row:destination)names.add(row.getAsJsonObject().get("name").getAsString());
        for(JsonElement row:source){
            String name=row.getAsJsonObject().get("name").getAsString();if(!names.add(name))throw new IOException("Конфликт имён устройства / сценария: "+name);
            destination.add(row.deepCopy());
        }
    }
    private static String digest(String text) throws IOException{
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IOException(impossible);}
    }
}
