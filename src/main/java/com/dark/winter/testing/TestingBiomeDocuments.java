// Exact native quart-biome palettes, stored inside the ordinary document WAL.
package com.dark.winter.testing;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Exact native quart-biome palettes, stored inside the ordinary document WAL. No terrain is touched. */
public final class TestingBiomeDocuments {
    private static final int MAX_CHUNKS=1369,MAX_PALETTE=1024;
    private record Chunk(int x,int z,int[][] sections) {}
    private record Prepared(List<Holder<Biome>> palette,List<Chunk> chunks) {}
    private TestingBiomeDocuments() {}

    private static ServerLevel world(MinecraftServer server)throws IOException {
        if(server==null||!server.isSameThread()||!server.isSingleplayer()||(server.isPublished()&&!TestingActors.managedScope(server))||!TestingServer.isTesting(server)
                ||TestingServer.context(server)==null||!(server.overworld().getChunkSource().getGenerator() instanceof TestingIslandGenerator))
            throw new IOException("Native biome documents require an actual marked island on its local server thread");
        return server.overworld();
    }
    private static List<ChunkAccess> footprint(ServerLevel world)throws IOException {
        var generator=(TestingIslandGenerator)world.getChunkSource().getGenerator();var context=TestingServer.context(world.getServer());
        if(context==null)throw new IOException("Нет контекста биомов");
        Set<net.minecraft.world.level.ChunkPos> required=TestingTerrain.chunks(TestingTerrain.extent(context,generator));TestingTerrain.requireLoaded(context,required);
        List<ChunkAccess> chunks=new ArrayList<>();for(var position:required)chunks.add(world.getChunk(position.x(),position.z()));
        chunks.sort(Comparator.comparingInt((ChunkAccess value)->value.getPos().x()).thenComparingInt(value->value.getPos().z()));return List.copyOf(chunks);
    }
    public static JsonObject capture(MinecraftServer server)throws IOException {
        return captureChunks(server,footprint(world(server)));
    }
    /** Compare/replay only the chunks actually touched by the original operation, ignoring later loading elsewhere. */
    public static JsonObject captureMatching(MinecraftServer server,JsonObject selected)throws IOException {
        Prepared prepared=prepare(server,selected);ServerLevel world=server.overworld();List<ChunkAccess> chunks=new ArrayList<>();
        for(Chunk row:prepared.chunks())chunks.add(world.getChunk(row.x(),row.z()));
        return captureChunks(server,List.copyOf(chunks));
    }
    private static JsonObject captureChunks(MinecraftServer server,List<ChunkAccess> chunks)throws IOException {
        try {
            ServerLevel world=world(server);TreeSet<String> names=new TreeSet<>();
            int minimum=world.getMinY()>>2,count=world.getHeight()>>2;
            for(var chunk:chunks)for(int y=minimum;y<minimum+count;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++)
                names.add(biome(world,chunk,x,y,z));
            List<String> palette=List.copyOf(names);Map<String,Integer> indices=new HashMap<>();
            for(int i=0;i<palette.size();i++)indices.put(palette.get(i),i);
            JsonObject root=header(world,palette);JsonArray encoded=root.getAsJsonArray("chunks");
            for(var chunk:chunks) {
                JsonObject row=row(chunk.getPos().x(),chunk.getPos().z());JsonArray sections=row.getAsJsonArray("sections");
                for(int section=0;section<world.getHeight()/16;section++) {
                    JsonArray values=new JsonArray();int quartStart=minimum+section*4;
                    for(int y=quartStart;y<quartStart+4;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++)values.add(indices.get(biome(world,chunk,x,y,z)));
                    sections.add(values);
                }encoded.add(row);
            }
            validate(server,root);return root;
        }catch(RuntimeException failure){throw new IOException("Could not capture exact native biome palettes",failure);}
    }
    /** Same chunk footprint as TestingTerrain.applyBiome; complete native palettes, with no geometry writes. */
    public static JsonObject uniform(MinecraftServer server,String key)throws IOException {
        try {
            ServerLevel world=world(server);String name=key.contains(":")?key:"minecraft:"+key;
            world.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse(name)));
            JsonObject root=header(world,List.of(name));JsonArray encoded=root.getAsJsonArray("chunks");
            for(var chunk:footprint(world)) {
                JsonObject row=row(chunk.getPos().x(),chunk.getPos().z());JsonArray sections=row.getAsJsonArray("sections");
                for(int section=0;section<world.getHeight()/16;section++){JsonArray values=new JsonArray();for(int i=0;i<64;i++)values.add(0);sections.add(values);}
                encoded.add(row);
            }
            validate(server,root);return root;
        }catch(RuntimeException failure){throw new IOException("Could not build native biome target",failure);}
    }
    public static void validate(MinecraftServer server,JsonObject payload)throws IOException {prepare(server,payload,false);}
    public static Set<net.minecraft.world.level.ChunkPos> requiredChunks(MinecraftServer server,JsonObject payload)throws IOException {
        Set<net.minecraft.world.level.ChunkPos> result=new LinkedHashSet<>();
        for(var chunk:prepare(server,payload,false).chunks())result.add(new net.minecraft.world.level.ChunkPos(chunk.x(),chunk.z()));
        return Set.copyOf(result);
    }
    public static void apply(MinecraftServer server,JsonObject payload)throws IOException {
        Prepared prepared=prepare(server,payload);ServerLevel world=server.overworld();int minimum=world.getMinY()>>4;
        List<ChunkAccess> changed=new ArrayList<>();
        // prepare has already checked EVERY resource, coordinate, loaded chunk and array before the first write.
        for(Chunk source:prepared.chunks()) {
            var chunk=world.getChunk(source.x(),source.z());
            chunk.fillBiomesFromNoise((x,y,z)->prepared.palette().get(source.sections()[(y>>2)-minimum][(y&3)*16+(z&3)*4+(x&3)]));
            chunk.markUnsaved();changed.add(chunk);
        }
        world.getChunkSource().chunkMap.resendBiomesForChunks(changed);
    }
    private static Prepared prepare(MinecraftServer server,JsonObject root)throws IOException {return prepare(server,root,true);}
    private static Prepared prepare(MinecraftServer server,JsonObject root,boolean requireLoaded)throws IOException {
        try {
            ServerLevel world=world(server);var generator=(TestingIslandGenerator)world.getChunkSource().getGenerator();
            int radius=12,sections=world.getHeight()/16; // Natural island metadata is bounded to [-192,192], independent of current view and Undo order.
            if(root==null||!root.keySet().equals(Set.of("schema","minSection","sectionCount","palette","chunks"))
                    ||integer(root.get("schema"),1,1)!=1||integer(root.get("minSection"),-128,128)!=(world.getMinY()>>4)
                    ||integer(root.get("sectionCount"),1,128)!=sections)throw new IOException("Native biome dimensions/schema mismatch");
            JsonArray dictionary=root.getAsJsonArray("palette"),rows=root.getAsJsonArray("chunks");
            if(dictionary==null||dictionary.isEmpty()||dictionary.size()>MAX_PALETTE||rows==null||rows.isEmpty()||rows.size()>MAX_CHUNKS)
                throw new IOException("Native biome palette/chunk limit exceeded");
            var registry=world.registryAccess().lookupOrThrow(Registries.BIOME);List<Holder<Biome>> palette=new ArrayList<>();String previous=null;
            for(JsonElement value:dictionary) {
                if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IOException("Native biome ID must be a registry key");
                String name=value.getAsString();
                if(name.length()>256||previous!=null&&previous.compareTo(name)>=0)throw new IOException("Native biome dictionary must be sorted and unique");
                Identifier id=Identifier.parse(name);if(!id.toString().equals(name))throw new IOException("Native biome key must be canonical");
                palette.add(registry.getOrThrow(ResourceKey.create(Registries.BIOME,id)));previous=name;
            }
            Set<Long> unique=new HashSet<>();List<Chunk> chunks=new ArrayList<>();
            for(JsonElement value:rows) {
                JsonObject row=value.getAsJsonObject();if(!row.keySet().equals(Set.of("x","z","sections")))throw new IOException("Invalid native biome chunk fields");
                int x=integer(row.get("x"),-radius,radius),z=integer(row.get("z"),-radius,radius);
                if(!unique.add(net.minecraft.world.level.ChunkPos.pack(x,z))||requireLoaded&&!world.hasChunk(x,z))throw new IOException("Native biome chunk duplicated or unloaded");
                JsonArray encoded=row.getAsJsonArray("sections");if(encoded==null||encoded.size()!=sections)throw new IOException("Native biome section count mismatch");
                int[][] indices=new int[sections][64];
                for(int section=0;section<sections;section++) {
                    JsonArray cells=encoded.get(section).getAsJsonArray();if(cells.size()!=64)throw new IOException("Native biome section needs 64 quart cells");
                    for(int cell=0;cell<64;cell++)indices[section][cell]=integer(cells.get(cell),0,palette.size()-1);
                }chunks.add(new Chunk(x,z,indices));
            }return new Prepared(List.copyOf(palette),List.copyOf(chunks));
        }catch(RuntimeException failure){throw new IOException("Native biome document rejected before changing any chunk",failure);}
    }
    private static JsonObject header(ServerLevel world,List<String> palette) {
        JsonObject root=new JsonObject();root.addProperty("schema",1);root.addProperty("minSection",world.getMinY()>>4);root.addProperty("sectionCount",world.getHeight()/16);
        JsonArray names=new JsonArray();palette.forEach(names::add);root.add("palette",names);root.add("chunks",new JsonArray());return root;
    }
    private static JsonObject row(int x,int z){JsonObject row=new JsonObject();row.addProperty("x",x);row.addProperty("z",z);row.add("sections",new JsonArray());return row;}
    private static String biome(ServerLevel world,ChunkAccess chunk,int x,int y,int z){return world.getNoiseBiome(chunk.getPos().x()*4+x,y,chunk.getPos().z()*4+z).unwrapKey().orElseThrow().identifier().toString();}
    private static int integer(JsonElement value,int min,int max)throws IOException {
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IOException("Expected an integer biome index");
        double number=value.getAsDouble();if(!Double.isFinite(number)||number!=Math.rint(number)||number<min||number>max)throw new IOException("Biome index/coordinate out of bounds");return (int)number;
    }
}
