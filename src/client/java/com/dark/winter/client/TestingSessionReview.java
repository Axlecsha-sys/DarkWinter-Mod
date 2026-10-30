// Captures native category comparisons for selective test-session restoration.
package com.dark.winter.client;

import com.dark.winter.testing.TestingArchive;
import com.dark.winter.testing.TestingServer;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;

/** Native category comparisons captured on the owning server thread, outside disposable saves. */
final class TestingSessionReview {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    record Review(List<String> lines,String signature,Set<String> available) {}
    private TestingSessionReview() {}
    static Path directory(String id) throws IOException {
        if(id==null||!id.matches("[0-9a-f-]{36}"))throw new IOException("Нет исходного обзора этой старой сессии");
        return TestingArchive.root().resolve("session-reviews").resolve(id);
    }
    static void baseline(MinecraftServer server,String id)throws IOException {
        Path dir=directory(id);Files.createDirectories(dir);
        JsonObject meta=new JsonObject();meta.addProperty("schema",1);
        for(String category:List.of("building","object")) {
            try {TestingServer.exportCategory(server,category,dir.resolve("baseline-"+category+".json"));meta.addProperty(category,"");}
            catch(Exception error){meta.addProperty(category,error.getMessage()==null?"Данные недоступны":error.getMessage());}
        }
        try {
            meta.add("zones",JSON.toJsonTree(TestingServer.protectedZones(server)));
            Path provenance=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/darkwinter-testing/provenance.json");
            if(!Files.isRegularFile(provenance)||Files.size(provenance)>32L*1024*1024)throw new IOException("Нет исходной карты происхождения");
            meta.add("origins",read(provenance).getAsJsonObject("origins"));
            JsonArray colliders=new JsonArray();
            for(var entity:server.overworld().getAllEntities())if(entity.isAlive()) {
                if(colliders.size()>=4096)throw new IOException("Более 4096 исходных коллизий сущностей");
                var row=new JsonObject();var box=entity.getBoundingBox();row.addProperty("id",entity.getUUID().toString());
                row.add("box",JSON.toJsonTree(new double[]{box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ}));colliders.add(row);
            }
            meta.add("colliders",colliders);
        }catch(Exception error){meta.addProperty("conflictMetadataError",error.getMessage());}
        TestingArchive.atomicWrite(dir.resolve("baseline-settings.json"),JSON.toJson(settings(server)));
        TestingArchive.atomicWrite(dir.resolve("baseline-meta.json"),JSON.toJson(meta));
    }
    static void markBaselineUnavailable(String id,String reason)throws IOException {
        Path file=directory(id).resolve("baseline-meta.json");JsonObject meta=read(file);
        String message=reason==null?"Полный источник не удалось подготовить":reason;
        meta.addProperty("building",message);meta.addProperty("object",message);meta.addProperty("conflictMetadataError",message);
        TestingArchive.atomicWrite(file,JSON.toJson(meta));
    }
    private static JsonObject settings(MinecraftServer server) {
        JsonObject value=JSON.toJsonTree(TestingServer.settings(server)).getAsJsonObject();
        value.remove("mode");value.remove("biome");return value;
    }
    static Review capture(MinecraftServer server,String id)throws IOException {
        Path dir=directory(id);
        JsonObject meta=read(dir.resolve("baseline-meta.json")), signatures=new JsonObject();
        List<String> lines=new ArrayList<>();Set<String> available=new HashSet<>();
        for(String category:List.of("building","object")) {
            String label=category.equals("building")?"Постройки":"Объекты";
            try {
                if(!meta.has(category)||!meta.get(category).getAsString().isEmpty())
                    throw new IOException(meta.has(category)?meta.get(category).getAsString():"Нет исходной категории");
                Path result=dir.resolve("result-"+category+".json");
                TestingServer.exportCategory(server,category,result);
                JsonObject before=read(dir.resolve("baseline-"+category+".json")),after=read(result);
                Map<String,JsonElement> old=cells(before),now=cells(after);
                signatures.add(category,canonical(now));available.add(category);
                lines.add(label+": "+difference(old,now)+" блоков (добавлено / удалено / изменено)");
                if(category.equals("object")) {
                    Map<String,JsonElement> oldNpc=npc(before),newNpc=npc(after);
                    signatures.add("npc",canonical(newNpc));
                    lines.add("Поддерживаемые NPC: "+difference(oldNpc,newNpc)+" (добавлено / удалено / изменено)");
                }
                List<String> conflicts=knownConflicts(server,category,before,after,meta);
                if(!conflicts.isEmpty()){available.remove(category);lines.add(label+": сохранение запрещено — "+conflicts.size()+" известных конфликтов");lines.addAll(conflicts.stream().limit(10).toList());}
            }catch(Exception error) {
                available.remove(category);
                String reason=error.getMessage()==null?"Ошибка чтения":error.getMessage();
                signatures.addProperty(category,"unavailable:"+reason);
                lines.add(label+": выборочное сохранение недоступно — "+reason);
            }
        }
        JsonObject baseSettings=read(dir.resolve("baseline-settings.json")),current=settings(server);
        signatures.add("settings",current);available.add("settings");int count=0;
        for(String key:union(baseSettings.keySet(),current.keySet())) {
            if(Objects.equals(baseSettings.get(key),current.get(key)))continue;
            if(key.equals("modules")&&baseSettings.has(key)&&current.has(key)) {
                JsonObject old=baseSettings.getAsJsonObject(key),now=current.getAsJsonObject(key);
                for(String module:union(old.keySet(),now.keySet()))if(!Objects.equals(old.get(module),now.get(module)))count++;
            }else count++;
        }
        lines.add("Изменённые параметры систем: "+count);
        lines.add("Инвентарь, время и прочая симуляция вернутся к исходному состоянию.");
        lines.add("Нативные чанки, опоры и окончательные коллизии будут дополнительно проверены перед единой записью; этот обзор их не объявляет проверенными.");
        Review review=new Review(List.copyOf(lines),JSON.toJson(signatures),Set.copyOf(available));
        JsonObject report=new JsonObject();report.addProperty("schema",1);report.addProperty("utc",java.time.Instant.now().toString());
        report.add("summary",JSON.toJsonTree(lines));report.add("categories",signatures);
        TestingArchive.atomicWrite(dir.resolve("review.json"),JSON.toJson(report));
        return review;
    }
    /** Read only already captured metadata/native entity payloads; never loads a world chunk. */
    private static List<String> knownConflicts(MinecraftServer server,String category,JsonObject before,JsonObject after,JsonObject meta)throws IOException {
        if(meta.has("conflictMetadataError")||!meta.has("zones")||!meta.has("origins")||!meta.has("colliders"))
            throw new IOException("Исходные сведения для проверки конфликтов недоступны; полный откат разрешён");
        List<String> conflicts=new ArrayList<>();Map<String,JsonElement> old=cells(before),now=cells(after);
        for(String key:union(old.keySet(),now.keySet()))if(!Objects.equals(old.get(key),now.get(key))) {
            var pos=net.minecraft.core.BlockPos.of(Long.parseLong(key));
            for(var element:meta.getAsJsonArray("zones")){
                var zone=element.getAsJsonObject();
                if(zone.get("objects").getAsBoolean()&&inside(zone,pos.getX(),pos.getY(),pos.getZ()))
                    conflicts.add("Защищённая зона «"+zone.get("name").getAsString()+"»: "+pos.toShortString());
            }
            JsonElement owner=meta.getAsJsonObject("origins").get(key);
            if(owner!=null&&!owner.getAsString().equals(category)&&java.util.Set.of("building","object").contains(owner.getAsString()))
                conflicts.add("Другая авторская категория "+owner.getAsString()+": "+pos.toShortString());
        }
        if(category.equals("object")) {
            Map<String,JsonElement> original=npc(before),desired=npc(after);
            var baselineBoxes=new HashMap<String,net.minecraft.world.phys.AABB>();
            for(var element:meta.getAsJsonArray("colliders")){var row=element.getAsJsonObject();baselineBoxes.put(row.get("id").getAsString(),box(row.getAsJsonArray("box")));}
            var changed=new HashSet<String>();
            for(String id:union(original.keySet(),desired.keySet()))if(!Objects.equals(original.get(id),desired.get(id)))changed.add(id);
            // Keep original boxes for protected-zone checks; collision checks use each changed member's final position.
            var placed=new LinkedHashMap<String,net.minecraft.world.phys.AABB>();
            for(String id:changed) {
                net.minecraft.world.phys.AABB destination=null;
                if(desired.containsKey(id))destination=com.dark.winter.testing.TestingEntities.bounds(server.overworld(),
                        com.dark.winter.testing.TestingNbt.decode(desired.get(id).getAsString()));
                var source=baselineBoxes.get(id);
                for(var element:meta.getAsJsonArray("zones")) {
                    var zone=element.getAsJsonObject();if(!zone.get("objects").getAsBoolean())continue;
                    var bounds=new net.minecraft.world.phys.AABB(zone.get("x0").getAsDouble(),zone.get("y0").getAsDouble(),zone.get("z0").getAsDouble(),
                            zone.get("x1").getAsDouble()+1,zone.get("y1").getAsDouble()+1,zone.get("z1").getAsDouble()+1);
                    if(source!=null&&source.intersects(bounds)||destination!=null&&destination.intersects(bounds))
                        conflicts.add("NPC "+id+" затрагивает защищённую зону «"+zone.get("name").getAsString()+"»");
                }
                if(destination!=null) {
                    for(var other:baselineBoxes.entrySet())if(!changed.contains(other.getKey())&&destination.intersects(other.getValue()))
                        conflicts.add("NPC "+id+" пересекает исходную сущность "+other.getKey());
                    for(var other:placed.entrySet())if(destination.intersects(other.getValue()))conflicts.add("NPC "+id+" пересекает выбранного NPC "+other.getKey());
                    placed.put(id,destination);
                }
            }
        }
        return List.copyOf(new LinkedHashSet<>(conflicts));
    }
    private static boolean inside(JsonObject zone,int x,int y,int z){return x>=zone.get("x0").getAsInt()&&x<=zone.get("x1").getAsInt()
            &&y>=zone.get("y0").getAsInt()&&y<=zone.get("y1").getAsInt()&&z>=zone.get("z0").getAsInt()&&z<=zone.get("z1").getAsInt();}
    private static net.minecraft.world.phys.AABB box(JsonArray values)throws IOException {
        if(values.size()!=6)throw new IOException("Нет исходной коллизии");
        return new net.minecraft.world.phys.AABB(values.get(0).getAsDouble(),values.get(1).getAsDouble(),values.get(2).getAsDouble(),
                values.get(3).getAsDouble(),values.get(4).getAsDouble(),values.get(5).getAsDouble());
    }
    static Review withAuthorCheck(Review nativeReview,String baseline,Path author)throws IOException {
        List<String> differences=TestingArchive.differencesFromSave(TestingArchive.read(baseline),author);
        List<String> lines=new ArrayList<>(nativeReview.lines());Set<String> available=new HashSet<>(nativeReview.available());
        if(differences.isEmpty())lines.add("Авторский мир совпадает с исходной полной версией по всем SHA-256.");
        else {
            available.clear();lines.add("Авторский мир изменён после запуска: "+differences.size()+" различий. Выборочное сохранение запрещено; полный откат разрешён.");
            lines.addAll(differences.stream().limit(10).toList());
        }
        return new Review(List.copyOf(lines),nativeReview.signature(),Set.copyOf(available));
    }
    static void writeFinal(String id,Review review)throws IOException {
        JsonObject result=read(directory(id).resolve("review.json"));
        result.add("summary",JSON.toJsonTree(review.lines()));result.add("available",JSON.toJsonTree(review.available()));
        TestingArchive.atomicWrite(directory(id).resolve("review.json"),JSON.toJson(result));
    }
    private static Map<String,JsonElement> cells(JsonObject data) {
        Map<String,JsonElement> result=new TreeMap<>();
        for(JsonElement cell:data.getAsJsonArray("changes")) {
            JsonObject row=cell.getAsJsonObject();result.put(row.get("pos").getAsString(),row.get("after"));
        }return result;
    }
    private static Map<String,JsonElement> npc(JsonObject data) {
        Map<String,JsonElement> result=new TreeMap<>();
        if(data.has("npc"))for(JsonElement cell:data.getAsJsonArray("npc")) {
            JsonObject row=cell.getAsJsonObject();result.put(row.get("id").getAsString(),row.get("afterNbt"));
        }return result;
    }
    private static JsonObject canonical(Map<String,JsonElement> values) {
        JsonObject result=new JsonObject();values.forEach(result::add);return result;
    }
    private static Set<String> union(Set<String> a,Set<String> b){Set<String> all=new TreeSet<>(a);all.addAll(b);return all;}
    private static String difference(Map<String,JsonElement> old,Map<String,JsonElement> now) {
        int added=0,removed=0,changed=0;
        for(String key:union(old.keySet(),now.keySet()))
            if(!old.containsKey(key))added++;else if(!now.containsKey(key))removed++;
            else if(!Objects.equals(old.get(key),now.get(key)))changed++;
        return added+" / "+removed+" / "+changed;
    }
    private static JsonObject read(Path file)throws IOException {
        if(!Files.isRegularFile(file)||Files.size(file)>128L*1024*1024)throw new IOException("Недоступен файл обзора "+file.getFileName());
        try{return JsonParser.parseString(Files.readString(file)).getAsJsonObject();}
        catch(RuntimeException error){throw new IOException("Повреждён файл обзора",error);}
    }
}
