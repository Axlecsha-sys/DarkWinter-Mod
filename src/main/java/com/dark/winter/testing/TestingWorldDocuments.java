// Exact manual protection policies and native world respawn data join the same document WAL.
package com.dark.winter.testing;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelData;

/** Exact manual protection policies and native world respawn data join the same document WAL. */
public final class TestingWorldDocuments {
    private TestingWorldDocuments() {}
    private static void authority(MinecraftServer server)throws IOException {
        if(server==null||!server.isSameThread()||!server.isSingleplayer()||(server.isPublished()&&!TestingActors.managedScope(server))||!TestingServer.isTesting(server))
            throw new IOException("World documents require the marked local authoring server");
    }
    public static JsonObject captureZones(MinecraftServer server)throws IOException {
        authority(server);var context=TestingServer.context(server);if(context==null)throw new IOException("Missing editor context");
        return captureZones(context);
    }
    static JsonObject captureZones(TestingServer.Context context) {
        JsonObject root=new JsonObject(),zones=new JsonObject();root.addProperty("schema",1);root.add("zones",zones);
        for(String name:new TreeSet<>(context.zones.keySet())) {
            var z=context.zones.get(name);var policy=context.zoneRules.getOrDefault(name,new TestingServer.ZoneRule(true,true));
            JsonObject row=new JsonObject();row.addProperty("x0",z.x0());row.addProperty("y0",z.y0());row.addProperty("z0",z.z0());
            row.addProperty("x1",z.x1());row.addProperty("y1",z.y1());row.addProperty("z1",z.z1());row.addProperty("natural",policy.natural());row.addProperty("objects",policy.objects());zones.add(name,row);
        }return root;
    }
    public static void validateZones(MinecraftServer server,JsonObject root)throws IOException {
        authority(server);
        try {
            if(root==null||!root.keySet().equals(Set.of("schema","zones"))||integer(root.get("schema"),1,1)!=1)throw new IOException("Invalid zone document");
            JsonObject zones=root.getAsJsonObject("zones");if(zones==null||zones.size()>256)throw new IOException("At most 256 named manual zones");
            for(var entry:zones.entrySet()) {
                TestingServer.safeName(entry.getKey());JsonObject row=entry.getValue().getAsJsonObject();
                if(!row.keySet().equals(Set.of("x0","y0","z0","x1","y1","z1","natural","objects")))throw new IOException("Invalid zone policy fields");
                int x0=integer(row.get("x0"),-30000000,30000000),x1=integer(row.get("x1"),-30000000,30000000);
                int z0=integer(row.get("z0"),-30000000,30000000),z1=integer(row.get("z1"),-30000000,30000000);
                int y0=integer(row.get("y0"),server.overworld().getMinY(),server.overworld().getMaxY()),y1=integer(row.get("y1"),server.overworld().getMinY(),server.overworld().getMaxY());
                if(x0>x1||y0>y1||z0>z1||(long)x1-x0>4096||(long)z1-z0>4096)throw new IOException("Invalid manual zone bounds");
                bool(row.get("natural"));bool(row.get("objects"));
            }
        }catch(RuntimeException failure){throw new IOException("Zones rejected before changing protection",failure);}
    }
    public static void applyZones(MinecraftServer server,JsonObject root)throws IOException {
        validateZones(server,root);var context=TestingServer.context(server);if(context==null)throw new IOException("Missing editor context");
        install(context,root);context.save();
    }
    private static void install(TestingServer.Context context,JsonObject root) {
        Map<String,TestingServer.Zone> zones=new HashMap<>();Map<String,TestingServer.ZoneRule> rules=new HashMap<>();
        for(var entry:root.getAsJsonObject("zones").entrySet()) {
            JsonObject row=entry.getValue().getAsJsonObject();
            zones.put(entry.getKey(),new TestingServer.Zone(row.get("x0").getAsInt(),row.get("y0").getAsInt(),row.get("z0").getAsInt(),row.get("x1").getAsInt(),row.get("y1").getAsInt(),row.get("z1").getAsInt()));
            rules.put(entry.getKey(),new TestingServer.ZoneRule(row.get("natural").getAsBoolean(),row.get("objects").getAsBoolean()));
        }
        context.zones.clear();context.zones.putAll(zones);context.zoneRules.clear();context.zoneRules.putAll(rules);
    }
    /** Missing legacy policies default to both protections; malformed explicit booleans never coerce. */
    static void loadLegacyZones(TestingServer.Context context,JsonObject island)throws IOException {
        JsonObject root=new JsonObject(),zones=new JsonObject();root.addProperty("schema",1);root.add("zones",zones);
        JsonObject legacy=island.getAsJsonObject("zones"),rules=island.has("zoneRules")?island.getAsJsonObject("zoneRules"):new JsonObject();
        if(legacy==null||!legacy.keySet().containsAll(rules.keySet()))throw new IOException("Orphaned zone policy");
        for(var entry:legacy.entrySet()) {
            JsonObject row=entry.getValue().getAsJsonObject().deepCopy();JsonObject policy=rules.has(entry.getKey())?rules.getAsJsonObject(entry.getKey()):null;
            if(policy!=null&&!policy.keySet().equals(Set.of("natural","objects")))throw new IOException("Invalid stored zone policy");
            row.add("natural",policy==null?new JsonPrimitive(true):policy.get("natural"));row.add("objects",policy==null?new JsonPrimitive(true):policy.get("objects"));zones.add(entry.getKey(),row);
        }
        validateZones(context.server,root);install(context,root);
    }
    public static JsonObject captureSpawn(MinecraftServer server)throws IOException {
        authority(server);JsonObject root=new JsonObject();root.addProperty("schema",1);
        root.add("data",LevelData.RespawnData.CODEC.encodeStart(JsonOps.INSTANCE,server.getRespawnData()).getOrThrow());return root;
    }
    public static void validateSpawn(MinecraftServer server,JsonObject root)throws IOException {decodeSpawn(server,root);}
    private static LevelData.RespawnData decodeSpawn(MinecraftServer server,JsonObject root)throws IOException {
        authority(server);
        try {
            if(root==null||!root.keySet().equals(Set.of("schema","data"))||integer(root.get("schema"),1,1)!=1)throw new IOException("Invalid native respawn document");
            var data=LevelData.RespawnData.CODEC.parse(JsonOps.INSTANCE,root.get("data")).getOrThrow();var level=server.getLevel(data.dimension());
            if(level==null||!Float.isFinite(data.yaw())||!Float.isFinite(data.pitch())||data.pitch()<-90||data.pitch()>90
                    ||Math.abs((long)data.pos().getX())>30000000||Math.abs((long)data.pos().getZ())>30000000
                    ||level.isOutsideBuildHeight(data.pos()))throw new IOException("Native respawn dimensions/position/rotation invalid");
            return data;
        }catch(RuntimeException failure){throw new IOException("Native respawn rejected before changing world spawn",failure);}
    }
    public static void applySpawn(MinecraftServer server,JsonObject root)throws IOException {server.setRespawnData(decodeSpawn(server,root));}
    private static boolean bool(JsonElement value)throws IOException {if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new IOException("Zone policies must be JSON booleans");return value.getAsBoolean();}
    private static int integer(JsonElement value,int min,int max)throws IOException {if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IOException("Expected integer zone coordinate");double number=value.getAsDouble();if(!Double.isFinite(number)||number!=Math.rint(number)||number<min||number>max)throw new IOException("Zone coordinate outside bounds");return (int)number;}
}
