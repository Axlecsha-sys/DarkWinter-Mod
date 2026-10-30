// Plans composite island geometry edits with durable journals and stopped-save permits.
package com.dark.winter.testing;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Composite geometry edits. Plans and immutable per-chunk journals precede every
 * write. A stopped-world archive permit is mandatory. Undo/redo use the same
 * journals, and recovery reconciles the durable editor-history commit.
 */
public final class TestingResize {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final Map<MinecraftServer,Job> JOBS=new ConcurrentHashMap<>();
    private static final Map<MinecraftServer,String> STATUS=new ConcurrentHashMap<>();
    private static final long MAX_BYTES=512L*1024*1024;
    private static final long MAX_CELLS=8_000_000;
    private static final int BUDGET=2048;
    private record Part(int chunkX,int chunkZ,String file,String sha256,int cells) {}
    private record ChunkData(List<TestingEdits.Change> changes,List<Holder<Biome>> beforePalette,int[] indices,Holder<Biome> after,boolean preserveBiomes) {}
    private record Group(int schema,String id,String source,String backup,String created,
                         TestingIslandGenerator.Configuration before,TestingIslandGenerator.Configuration after,
                         Map<Long,Integer> heightsBefore,Map<Long,Integer> heightsAfter,List<Part> parts,long cells,
                         String biomeBefore,String biomeAfter,String label) {}
    private record Pending(int schema,String id,String phase,String mode,boolean wasFrozen) {}
    private TestingResize() {}

    private static final Set<MinecraftServer> BLOCKED=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static boolean busy(MinecraftServer server) { return JOBS.containsKey(server)||BLOCKED.contains(server); }
    public static boolean recoveryBlocked(MinecraftServer server) {
        Job job=JOBS.get(server);return BLOCKED.contains(server)||job!=null&&job.phase.equals("HALTED");
    }
    public static boolean writing(MinecraftServer server) {
        Job job=JOBS.get(server);return job!=null&&!job.phase.equals("HALTED");
    }
    public static String status(MinecraftServer server) { return STATUS.getOrDefault(server,"Изменение геометрии не выполняется"); }
    public static void forget(MinecraftServer server) { JOBS.remove(server);STATUS.remove(server);BLOCKED.remove(server); }
    private static Path pending(TestingServer.Context c) { return c.directory.resolve("resize-pending.json"); }
    private static Path directory(TestingServer.Context c,String id) throws IOException {
        if(id==null||!id.matches("[0-9a-f-]{36}"))throw new IOException("Недопустимый идентификатор изменения острова");
        return c.directory.resolve("resize-history").resolve(id);
    }
    private static TestingIslandGenerator generator(TestingServer.Context c) throws IOException {
        if(c.server.overworld().getChunkSource().getGenerator() instanceof TestingIslandGenerator g)return g;
        throw new IOException("Размер и форма изменяются только у островного генератора");
    }
    private static TestingServer.Context authority(MinecraftServer server) throws IOException {
        TestingServer.Context c=TestingServer.context(server);
        if(c==null||!server.isSameThread()||!server.isSingleplayer()||(server.isPublished()&&!TestingActors.managedScope(server))||!TestingServer.isTesting(server))
            throw new IOException("Нет полномочий на изменение тестового острова");
        if(!Set.of("EDIT","CHARACTER").contains(c.settings.mode))throw new IOException("Сначала завершите испытание");
        return c;
    }

    /** Called only after TestingSessions captured a stopped-world backup and reopened the author save. */
    public static void begin(MinecraftServer server,int radius,String shape,double hills) throws IOException {
        TestingServer.Context c=authority(server);TestingIslandGenerator g=generator(c);
        if(busy(server)||c.edits.busy())throw new IOException("Дождитесь текущей операции редактора");
        if(radius<32||radius>128||g.radius()>128||!Double.isFinite(hills)||hills<0||hills>2)
            throw new IOException("Размер существующего острова: радиус 32–128 м; холмы 0–2");
        var config=new TestingIslandGenerator.Configuration(radius,shape,hills);
        if(config.equals(g.configuration())) {c.message="Размер, форма и холмы уже соответствуют запросу";return;}
        String backup=backupPermit(c);
        TestingServer.rebuildSupport(c);
        Job job=new Job(c,g,config,backup);
        start(job,null);
        c.event("resize_begin",radius+"|"+shape+"|"+hills+"|backup="+backup);
        report(job,"Предпросмотр: подготовка природных столбцов");
    }

    private static String backupPermit(TestingServer.Context c)throws IOException {
        Path permitFile=c.directory.resolve("resize-permit.json");
        if(!Files.isRegularFile(permitFile)||Files.size(permitFile)>16384)throw new IOException("Требуется новая резервная версия перед изменением геометрии");
        String backup;
        try {
            JsonObject permit=JsonParser.parseString(Files.readString(permitFile)).getAsJsonObject();
            if(permit.get("schema").getAsInt()!=2)throw new IOException("Неподдерживаемое разрешение резервной версии");
            Path save=c.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            if(!save.equals(Path.of(permit.get("source").getAsString()).toAbsolutePath().normalize()))throw new IOException("Резервная версия принадлежит другому миру");
            Instant created=Instant.parse(permit.get("created").getAsString()),now=Instant.now();
            if(created.isBefore(now.minusSeconds(900))||created.isAfter(now.plusSeconds(30)))throw new IOException("Резервная версия устарела; повторите изменение через панель");
            backup=permit.get("version").getAsString();
            var version=TestingArchive.read(backup);
            if(!version.source().equals(TestingArchive.worldKey(save)))throw new IOException("Архив не принадлежит текущему острову");
        } catch(RuntimeException error) {throw new IOException("Повреждён файл разрешения резервной версии",error);}

        return backup;
    }
    private static void start(Job job,JsonObject continuation)throws IOException {
        var c=job.c;Files.createDirectories(job.dir);
        if(continuation!=null){continuation.addProperty("resizeId",job.id);TestingEdits.atomic(c.directory.resolve("terrain-followup.json"),continuation);}
        TestingEdits.atomic(pending(c),JSON.toJsonTree(new Pending(2,job.id,"PLAN","INITIAL",job.wasFrozen)));
        Files.delete(c.directory.resolve("resize-permit.json"));
        JOBS.put(c.server,job);c.server.tickRateManager().setFrozen(true);
    }

    static final class TerrainData {
        final TestingIslandGenerator.Configuration configuration;final int extent;final int[] heights;
        final Map<Long,Boolean> water;final Map<Long,int[]> waterBounds=new HashMap<>();
        final Map<Long,Boolean> blocked=new HashMap<>();
        final Map<Long,BlockState> cover=new HashMap<>(),caps=new HashMap<>();
        TerrainData(TestingIslandGenerator.Configuration config,int extent,int[] heights,Map<Long,Boolean> water){this.configuration=config;this.extent=extent;this.heights=heights;this.water=Map.copyOf(water);water.keySet().forEach(p->waterBound(waterBounds,BlockPos.of(p)));}
        int height(TestingIslandGenerator g,TestingIslandGenerator.Configuration config,int x,int z){
            return Math.abs(x)<=extent&&Math.abs(z)<=extent?heights[(z+extent)*(extent*2+1)+(x+extent)]:g.surfaceHeight(config,x,z);
        }
    }
    /** Full geometry category, including surface heights whose blocks have 'surface' provenance. */
    public static JsonObject captureTerrain(MinecraftServer server)throws IOException {
        var c=authority(server);var g=generator(c);if(busy(server)||c.edits.busy())throw new IOException("Дождитесь окончания изменения острова");
        if(g.radius()>128)throw new IOException("Категория рельефа поддерживает острова радиусом до 128 м");
        int extent=(int)Math.ceil(g.radius()*1.45)+4;
        for(long key:c.heights.keySet()){BlockPos p=BlockPos.of(key);extent=Math.max(extent,Math.max(Math.abs(p.getX()),Math.abs(p.getZ())));}
        JsonArray water=new JsonArray();
        for(var entry:c.edits.origins.entrySet())if("terrain".equals(entry.getValue())) {
            BlockPos pos=BlockPos.of(entry.getKey());if(!server.overworld().hasChunkAt(pos))throw new IOException("Загрузите изменённый рельеф перед экспортом: "+pos.toShortString());
            BlockState cell=server.overworld().getBlockState(pos);
            if(cell.is(Blocks.WATER)||cell.isAir()&&pos.getY()<TestingIslandGenerator.SEA_LEVEL) {
                extent=Math.max(extent,Math.max(Math.abs(pos.getX()),Math.abs(pos.getZ())));
                JsonObject row=new JsonObject();row.addProperty("pos",entry.getKey());row.addProperty("water",cell.is(Blocks.WATER));water.add(row);
                if(water.size()>262144)throw new IOException("Слишком много отдельных водных изменений для одной категории");
            }
        }
        if(extent>192)throw new IOException("Природные изменения за пределами 192 м переносите полным островом");
        JsonArray heights=new JsonArray();
        for(int z=-extent;z<=extent;z++)for(int x=-extent;x<=extent;x++) {
            long key=TestingTerrain.column(x,z);
            if(c.heights.containsKey(key)&&!server.overworld().hasChunkAt(new BlockPos(x,64,z)))throw new IOException("Загрузите изменённые столбцы перед экспортом");
            heights.add(c.heights.containsKey(key)?TestingTerrain.height(c,g,x,z):g.surfaceHeight(x,z));
        }
        JsonObject out=new JsonObject();out.addProperty("schema",2);out.addProperty("kind","natural_geometry");
        out.add("configuration",JSON.toJsonTree(g.configuration()));out.addProperty("extent",extent);
        out.add("heights",heights);out.add("water",water);return out;
    }
    static TerrainData validateTerrain(MinecraftServer server,JsonObject payload)throws IOException {return validateTerrain(server,payload,true);}
    static TerrainData validateTerrainSource(MinecraftServer server,JsonObject payload)throws IOException {return validateTerrain(server,payload,false);}
    private static TerrainData validateTerrain(MinecraftServer server,JsonObject payload,boolean inspectExisting)throws IOException {
        var c=authority(server);var g=generator(c);
        try {
            if(integer(payload.get("schema"),2,2)!=2||!"natural_geometry".equals(payload.get("kind").getAsString()))throw new IOException("Неподдерживаемый формат полного рельефа");
            JsonObject configJson=payload.getAsJsonObject("configuration");
            if(configJson==null||!configJson.has("hills")||!configJson.getAsJsonPrimitive("hills").isNumber())throw new IOException("Неполная конфигурация исходного острова");
            var config=new TestingIslandGenerator.Configuration(integer(configJson.get("radius"),32,128),configJson.get("shape").getAsString(),configJson.get("hills").getAsDouble());
            if(config==null||config.radius()<32||config.radius()>128||config.hills()>2||g.radius()>128)throw new IOException("Недопустимые параметры исходного острова");
            int extent=integer(payload.get("extent"),0,192);
            if(extent<(int)Math.ceil(config.radius()*1.45)+4)throw new IOException("Область рельефа не покрывает исходный остров");
            JsonArray cells=payload.getAsJsonArray("heights"),encodedWater=payload.getAsJsonArray("water");
            if(cells==null||cells.size()!=(extent*2+1)*(extent*2+1)||encodedWater==null||encodedWater.size()>262144)throw new IOException("Неполная карта высот или слишком много воды");
            int[] heights=new int[cells.size()];for(int i=0;i<heights.length;i++)heights[i]=integer(cells.get(i),server.overworld().getMinY()+2,server.overworld().getMaxY()-3);
            Map<Long,Boolean> water=new HashMap<>();
            for(JsonElement value:encodedWater) {
                JsonObject row=value.getAsJsonObject();String coordinate=row.get("pos").getAsString();
                if(!coordinate.matches("-?[0-9]+")||!row.getAsJsonPrimitive("water").isBoolean())throw new IOException("Недопустимое изменение воды");
                long packed=Long.parseLong(coordinate);BlockPos p=BlockPos.of(packed);boolean wet=row.get("water").getAsBoolean();
                if(Math.abs((long)p.getX())>extent||Math.abs((long)p.getZ())>extent||server.overworld().isOutsideBuildHeight(p)
                        ||p.getY()<=server.overworld().getMinY()||!wet&&p.getY()>=TestingIslandGenerator.SEA_LEVEL||water.put(packed,wet)!=null)
                    throw new IOException("Повторяющаяся или недопустимая водная ячейка");
            }
            TerrainData data=new TerrainData(config,extent,heights,water);
            if(!inspectExisting)return data;
            TestingServer.rebuildSupport(c);
            for(var entry:c.edits.origins.entrySet())if("terrain".equals(entry.getValue())) {
                BlockPos p=BlockPos.of(entry.getKey());if(!server.overworld().hasChunkAt(p))throw new IOException("Загрузите изменённую воду перед восстановлением");
                BlockState cell=server.overworld().getBlockState(p);
                if(cell.is(Blocks.WATER)||cell.isAir()&&p.getY()<TestingIslandGenerator.SEA_LEVEL)waterBound(data.waterBounds,p);
            }
            return data;
        }catch(RuntimeException error){throw new IOException("Повреждён полный рельеф; мир не изменён",error);}
    }
    private record Coverage(TerrainData geometry,Map<Long,BlockState> top,Map<Long,BlockState> caps) {}
    private static boolean groundCover(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)||state.is(net.minecraft.tags.BlockTags.DIRT)||state.is(Blocks.STONE)||state.is(Blocks.DEEPSLATE)||state.is(Blocks.SAND)
                ||state.is(Blocks.SANDSTONE)||state.is(Blocks.GRAVEL)||state.is(Blocks.SNOW_BLOCK)||state.is(Blocks.MOSS_BLOCK);
    }
    private static Coverage coverage(MinecraftServer server,JsonObject category)throws IOException {return coverage(server,category,true);}
    static TerrainData validateSurfaceSource(MinecraftServer server,JsonObject category)throws IOException {return coverage(server,category,false).geometry();}
    static String validateTerrainBackup(MinecraftServer server)throws IOException {return backupPermit(authority(server));}
    private static Coverage coverage(MinecraftServer server,JsonObject category,boolean inspectExisting)throws IOException {
        if(!category.has("surfaceGeometry"))throw new IOException("Старое покрытие без карты высот не гарантирует сохранение рельефа; экспортируйте категорию заново");
        TerrainData source=validateTerrain(server,category.getAsJsonObject("surfaceGeometry"),inspectExisting);TestingIslandGenerator g=generator(authority(server));
        Map<Long,BlockState> top=new HashMap<>(),caps=new HashMap<>();
        for(var row:TestingEdits.decode(category).changes()) {
            BlockPos p=row.pos();BlockState state=row.after().state();
            if(!"surface".equals(row.after().origin())||row.after().entity()!=null||state.hasBlockEntity()
                    ||Math.abs((long)p.getX())>source.extent||Math.abs((long)p.getZ())>source.extent)
                throw new IOException("Повреждённое природное покрытие");
            if(state.is(Blocks.WATER))continue; // Water belongs to the terrain/water category.
            int h=source.height(g,source.configuration,p.getX(),p.getZ()),relative=p.getY()-h;
            long key=TestingTerrain.column(p.getX(),p.getZ());
            if(state.isAir()&&relative!=1)continue; // Historical vacancies never remove solid geometry.
            if(relative==0){if(!groundCover(state))throw new IOException("Покрытие не может удалять или заменять геометрию объектом");top.put(key,state);}
            else if(relative==1){if(!state.isAir()&&!state.is(Blocks.SNOW))throw new IOException("Неподдерживаемый верхний слой покрытия");caps.put(key,state);}
            else if(!state.isAir()&&!state.is(Blocks.SNOW)&&!groundCover(state))throw new IOException("Недопустимый материал покрытия");
        }
        return new Coverage(source,Map.copyOf(top),Map.copyOf(caps));
    }
    static void attachCoverage(MinecraftServer server,TerrainData terrain,JsonObject category)throws IOException {
        Coverage source=coverage(server,category);terrain.cover.putAll(source.top());terrain.caps.putAll(source.caps());
    }
    /** Maps material layers to CURRENT natural heights; absolute source Y is never pasted into terrain. */
    static List<TestingEdits.Change> surfaceChanges(MinecraftServer server,JsonObject category)throws IOException {
        var c=authority(server);var g=generator(c);Coverage source=coverage(server,category);
        String biome=category.get("biome").getAsString();server.overworld().registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse(biome.contains(":")?biome:"minecraft:"+biome)));
        int extent=(int)Math.ceil(g.radius()*1.45)+4;
        for(long key:c.heights.keySet()){BlockPos p=BlockPos.of(key);extent=Math.max(extent,Math.max(Math.abs(p.getX()),Math.abs(p.getZ())));}
        if(extent>192)throw new IOException("Область покрытия превышает безопасные границы острова");
        TestingServer.rebuildSupport(c);List<TestingEdits.Change> changes=new ArrayList<>();
        for(int z=-extent;z<=extent;z++)for(int x=-extent;x<=extent;x++) {
            BlockPos sample=new BlockPos(x,64,z);if(!server.overworld().hasChunkAt(sample))continue;
            int h=TestingTerrain.height(c,g,x,z);BlockPos top=new BlockPos(x,h,z),above=top.above();
            if(TestingServer.naturalProtectedAt(c,top)||server.overworld().getBlockEntity(top)!=null)continue;
            var old=c.edits.read(top);if(!(old.origin()==null&&g.naturalState(x,h,z).equals(old.state())||Set.of("terrain","surface").contains(old.origin()==null?"":old.origin())))continue;
            long key=TestingTerrain.column(x,z);BlockState wanted=h<TestingIslandGenerator.SEA_LEVEL?Blocks.SAND.defaultBlockState():source.top().getOrDefault(key,material(h,h,biome));
            if(!old.state().equals(wanted))changes.add(c.edits.change(top,wanted,"surface"));
            var cap=c.edits.read(above);BlockState desired=source.caps().getOrDefault(key,material(h,h+1,biome));
            if(h>=TestingIslandGenerator.SEA_LEVEL&&!TestingServer.naturalProtectedAt(c,above)&&!cap.state().equals(desired)
                    &&(cap.state().isAir()||cap.state().is(Blocks.SNOW))&&(desired.isAir()||desired.is(Blocks.SNOW))
                    &&(cap.origin()==null||Set.of("terrain","surface").contains(cap.origin())))
                changes.add(c.edits.change(above,desired,"surface"));
        }
        return changes;
    }

    private static int integer(JsonElement value,int min,int max)throws IOException {
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IOException("Ожидалось целое число");
        double number=value.getAsDouble();if(!Double.isFinite(number)||number!=Math.rint(number)||number<min||number>max)throw new IOException("Число вне разрешённых границ");
        return (int)number;
    }
    private static void waterBound(Map<Long,int[]> bounds,BlockPos pos) {
        bounds.compute(TestingTerrain.column(pos.getX(),pos.getZ()),(key,old)->old==null?new int[]{pos.getY(),pos.getY()}:new int[]{Math.min(old[0],pos.getY()),Math.max(old[1],pos.getY())});
    }
    private static boolean terrainBlocked(TestingServer.Context c,TestingIslandGenerator g,TerrainData data,int x,int z) {
        long column=TestingTerrain.column(x,z);Boolean cached=data.blocked.get(column);if(cached!=null)return cached;
        boolean keep=c.support.containsKey(column);
        if(TestingServer.naturalZoneColumn(c,x,z))keep=true;
        int old=g.surfaceHeight(x,z),current=TestingTerrain.height(c,g,x,z),target=data.height(g,data.configuration,x,z),next=g.surfaceHeight(data.configuration,x,z);
        int bottom=Math.max(c.server.overworld().getMinY()+1,Math.min(Math.min(old,next),Math.min(current,target))-4);
        int top=Math.min(c.server.overworld().getMaxY()-1,Math.max(TestingIslandGenerator.SEA_LEVEL,Math.max(Math.max(old,next),Math.max(current,target)))+1);
        int[] water=data.waterBounds.get(column);if(water!=null){bottom=Math.min(bottom,water[0]);top=Math.max(top,water[1]);}
        for(int y=bottom;y<=top&&!keep;y++) {
            BlockPos p=new BlockPos(x,y,z);String origin=c.edits.origins.get(p.asLong());
            if(c.server.overworld().getBlockEntity(p)!=null||!(origin==null||origin.equals("terrain")||origin.equals("surface"))
                    ||origin==null&&!c.server.overworld().getBlockState(p).equals(g.naturalState(x,y,z)))keep=true;
        }
        data.blocked.put(column,keep);return keep;
    }
    /** Conservative preflight for later selected content, before any geometry mutation. */
    static boolean destinationFreeAfterTerrain(TestingServer.Context c,TerrainData data,BlockPos pos)throws IOException {
        var g=generator(c);if(!c.server.overworld().hasChunkAt(pos)||c.server.overworld().isOutsideBuildHeight(pos))return false;
        if(terrainBlocked(c,g,data,pos.getX(),pos.getZ()))return c.server.overworld().getBlockState(pos).canBeReplaced();
        if(data.water.containsKey(pos.asLong()))return true;
        return pos.getY()>data.height(g,data.configuration,pos.getX(),pos.getZ());
    }
    public static String beginTerrainRestore(MinecraftServer server,JsonObject payload)throws IOException {return beginTerrainRestore(server,payload,null);}
    public static String beginTerrainRestore(MinecraftServer server,JsonObject payload,String materialBiome)throws IOException {
        return beginTerrainRestore(server,validateTerrain(server,payload),materialBiome,null);
    }
    static String beginTerrainRestore(MinecraftServer server,TerrainData data,String materialBiome,JsonObject continuation)throws IOException {
        var c=authority(server);var g=generator(c);if(busy(server)||c.edits.busy())throw new IOException("Дождитесь текущей операции");
        if(materialBiome!=null)server.overworld().registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse(materialBiome.contains(":")?materialBiome:"minecraft:"+materialBiome)));
        String backup=backupPermit(c);Job job=new Job(c,g,data.configuration,backup);job.terrain=data;
        job.label=materialBiome==null?"Восстановить полный рельеф":"Восстановить полный рельеф и покрытие";
        job.preserveCover=materialBiome==null;job.preserveBiomes=materialBiome==null;
        if(materialBiome!=null)job.materialBiome=materialBiome;
        job.extent=Math.max(job.extent,data.extent);
        for(long key:c.heights.keySet()){BlockPos p=BlockPos.of(key);job.extent=Math.max(job.extent,Math.max(Math.abs(p.getX()),Math.abs(p.getZ())));}
        for(long key:data.waterBounds.keySet()){BlockPos p=BlockPos.of(key);job.extent=Math.max(job.extent,Math.max(Math.abs(p.getX()),Math.abs(p.getZ())));}
        if(job.extent>192)throw new IOException("Изменения за пределами 192 м восстанавливайте полным островом");
        job.minChunk=Math.floorDiv(-job.extent,16);job.side=Math.floorDiv(job.extent,16)-job.minChunk+1;
        job.waterBounds.putAll(data.waterBounds);start(job,continuation);c.event("terrain_restore_begin",job.id+"|backup="+backup);report(job,"Предпросмотр полного восстановления рельефа");return job.id;
    }
    private static BlockState materialKeepingCover(int top,int y,String biome,int oldHeight,BlockState cover,BlockState cap) {
        BlockState ordinary=material(top,y,biome);
        if(top<TestingIslandGenerator.SEA_LEVEL||oldHeight<TestingIslandGenerator.SEA_LEVEL)return ordinary;
        if(y==top&&!cover.isAir()&&cover.getFluidState().isEmpty()&&!cover.hasBlockEntity())return cover;
        if(y==top+1)return cap.is(Blocks.SNOW)?cap:Blocks.AIR.defaultBlockState();
        return ordinary;
    }

    /** One logical editor-history operation; no individual chunk entries enter Undo. */
    public static void replay(MinecraftServer server,String id,boolean forward) throws IOException {
        TestingServer.Context c=authority(server);TestingIslandGenerator g=generator(c);
        if(busy(server)||c.edits.busy())throw new IOException("Дождитесь текущей операции");
        Group group=readGroup(c,id);
        var expected=forward?group.before():group.after();
        if(!g.configuration().equals(expected))throw new IOException("Конфликт конфигурации острова; используйте резервную версию");
        Job job=new Job(c,g,group,forward,forward?"REDO":"UNDO",false);
        TestingEdits.atomic(pending(c),JSON.toJsonTree(new Pending(2,id,"VERIFY",job.mode,job.wasFrozen)));
        JOBS.put(server,job);server.tickRateManager().setFrozen(true);report(job,"Проверка журнала геометрии");
    }

    /** Called after the Context is inserted and edit/provenance recovery has completed. */
    public static void recover(MinecraftServer server) throws IOException {
        try{recoverPending(server);}
        catch(IOException|RuntimeException error){
            BLOCKED.add(server);server.tickRateManager().setFrozen(true);
            String message="Геометрия заблокирована повреждённым журналом; восстановите полную версию из Истории: "+error.getMessage();
            STATUS.put(server,message);var context=TestingServer.context(server);if(context!=null)context.message=message;
            org.slf4j.LoggerFactory.getLogger("DarkWinter/Testing").error(message,error);
        }
    }
    private static void recoverPending(MinecraftServer server) throws IOException {
        TestingServer.Context c=TestingServer.context(server);
        if(c==null||!Files.isRegularFile(pending(c)))return;
        Pending p;
        try {
            if(Files.size(pending(c))>16384)throw new IOException("Слишком большой журнал восстановления");
            p=JSON.fromJson(Files.readString(pending(c)),Pending.class);
            if(p==null||p.schema()!=2||!Set.of("PLAN","VERIFY","APPLY","RECOVER","DONE").contains(p.phase())
                    ||!Set.of("INITIAL","UNDO","REDO").contains(p.mode()))throw new IOException("Повреждён журнал изменения острова");
        } catch(RuntimeException e) {throw new IOException("Не удалось прочитать журнал изменения острова",e);}
        if(p.phase().equals("PLAN")) {
            // Planning never writes world blocks or generator configuration.
            Files.delete(pending(c));server.tickRateManager().setFrozen(p.wasFrozen());
            c.message="Прерванный предпросмотр геометрии отменён; мир сохранён";return;
        }
        if(p.phase().equals("DONE")) {Files.delete(pending(c));server.tickRateManager().setFrozen(p.wasFrozen());return;}
        Group group=readGroup(c,p.id());
        boolean applied=c.edits.resizeApplied(group.id());
        boolean targetCommitted=p.mode().equals("UNDO")?!applied:applied;
        boolean originalForward=!p.mode().equals("UNDO");
        boolean recoveryForward=targetCommitted?originalForward:!originalForward;
        Job job=new Job(c,generator(c),group,recoveryForward,"RECOVERY",true);
        job.wasFrozen=p.wasFrozen();
        JOBS.put(server,job);server.tickRateManager().setFrozen(true);
        TestingEdits.atomic(pending(c),JSON.toJsonTree(new Pending(2,job.id,"RECOVER",p.mode(),p.wasFrozen())));
        job.headerMode=p.mode();report(job,targetCommitted?"Восстановление завершённой геометрии":"Откат прерванного изменения острова");
    }

    public static void tick(MinecraftServer server) {
        Job job=JOBS.get(server);if(job==null)return;
        try(var actor=job.recovery||job.phase.equals("HALTED")?new TestingActors.Scope(null):TestingActors.enterCaptured(server,job.actor)) {
            if(job.phase.equals("HALTED"))return;
            if(job.phase.equals("PLAN")) {plan(job);return;}
            if(job.phase.equals("VERIFY")) {verify(job);return;}
            apply(job);
        } catch(Exception error) {fail(job,error);}
    }

    private static final class Job {
        final TestingServer.Context c;final TestingIslandGenerator g;final String id;final Path dir;
        final TestingActors.Captured actor=TestingActors.capture();
        final String mode;String headerMode;boolean wasFrozen;boolean forward=true,recovery;
        volatile String phase="PLAN";Group group;
        final List<Part> parts=new ArrayList<>();
        Map<Long,Integer> beforeHints,afterHints;
        TestingIslandGenerator.Configuration before,after;
        String backup,materialBiome,label="Размер/форма/холмы острова";boolean preserveBiomes,preserveCover;TerrainData terrain;
        final Map<Long,int[]> waterBounds=new HashMap<>();int extent,minChunk,side,planIndex,partIndex,cellIndex;
        long cells,bytes,appliedCells;int skipped;
        List<TestingEdits.Change> loaded;ChunkData loadedData;
        final Set<Long> protectedColumns=new HashSet<>();
        Job(TestingServer.Context c,TestingIslandGenerator g,TestingIslandGenerator.Configuration next,String backup) throws IOException {
            this.c=c;this.g=g;this.id=UUID.randomUUID().toString();dir=directory(c,id);mode="INITIAL";headerMode=mode;
            before=g.configuration();after=next;this.backup=backup;materialBiome=c.settings.biome;wasFrozen=c.server.tickRateManager().isFrozen();
            beforeHints=new LinkedHashMap<>(c.heights);afterHints=new LinkedHashMap<>(c.heights);
            extent=(int)Math.ceil(Math.max(before.radius(),after.radius())*1.45)+4;
            minChunk=Math.floorDiv(-extent,16);side=Math.floorDiv(extent,16)-minChunk+1;
            protectedColumns.addAll(c.support.keySet());
            for(var entry:c.edits.origins.entrySet())if(Set.of("building","object","grass","bushes","trees").contains(entry.getValue())) {
                BlockPos p=BlockPos.of(entry.getKey());if(c.server.overworld().hasChunkAt(p)&&c.server.overworld().getBlockState(p).isAir())continue;
                for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)protectedColumns.add(TestingTerrain.column(p.getX()+dx,p.getZ()+dz));
            }
        }
        Job(TestingServer.Context c,TestingIslandGenerator g,Group group,boolean forward,String mode,boolean recovery) throws IOException {
            this.c=c;this.g=g;this.group=group;id=group.id();dir=directory(c,id);this.forward=forward;this.mode=mode;headerMode=mode;
            this.recovery=recovery;wasFrozen=c.server.tickRateManager().isFrozen();phase="VERIFY";
            before=group.before();after=group.after();backup=group.backup();
        }
    }
    private static void report(Job job,String message) {
        STATUS.put(job.c.server,message);job.c.message=message;
    }
    private static void plan(Job job) throws IOException {
        if(job.planIndex>=job.side*job.side) {
            job.group=new Group(2,job.id,TestingArchive.worldKey(job.c.server.getWorldPath(LevelResource.ROOT)),job.backup,Instant.now().toString(),
                    job.before,job.after,Map.copyOf(job.beforeHints),Map.copyOf(job.afterHints),List.copyOf(job.parts),job.cells,
                    job.c.settings.biome,job.materialBiome,job.label);
            TestingEdits.atomic(job.dir.resolve("manifest.json"),JSON.toJsonTree(job.group));
            TestingEdits.atomic(pending(job.c),JSON.toJsonTree(new Pending(2,job.id,"APPLY",job.mode,job.wasFrozen)));
            job.phase="APPLY";report(job,"Геометрия: применение 0 / "+job.cells+"; защищённых столбцов "+job.skipped);return;
        }
        int cx=job.minChunk+job.planIndex%job.side,cz=job.minChunk+job.planIndex/job.side;
        job.c.server.overworld().getChunk(cx,cz); // bounded: one full chunk per planning tick
        List<TestingEdits.Change> changes=new ArrayList<>();
        for(int dx=0;dx<16;dx++)for(int dz=0;dz<16;dz++) {
            int x=cx*16+dx,z=cz*16+dz;if(Math.abs(x)>job.extent||Math.abs(z)>job.extent)continue;
            planColumn(job,x,z,changes);
        }
        if(!changes.isEmpty()) {
            String name=String.format(Locale.ROOT,"part-%05d.json.gz",job.planIndex);
            Path file=job.dir.resolve(name);
            writePart(job,file,cx,cz,new TestingEdits.Operation("Геометрия "+cx+","+cz,changes));
            long size=Files.size(file);job.bytes+=size;job.cells+=changes.size();
            if(job.bytes>MAX_BYTES||job.cells>MAX_CELLS)throw new IOException("Журнал геометрии превышает безопасный лимит; используйте меньший остров");
            job.parts.add(new Part(cx,cz,name,hash(file),changes.size()));
        }
        job.planIndex++;report(job,"Предпросмотр геометрии: чанки "+job.planIndex+" / "+(job.side*job.side)+", блоки "+job.cells+", защищено "+job.skipped);
    }
    private static void planColumn(Job job,int x,int z,List<TestingEdits.Change> changes) {
        var c=job.c;var world=c.server.overworld();long key=TestingTerrain.column(x,z);
        int original=job.g.surfaceHeight(job.before,x,z),nextBase=job.g.surfaceHeight(job.after,x,z);
        int current=TestingTerrain.height(c,job.g,x,z);
        int target=job.terrain==null?Math.clamp(nextBase+(current-original),world.getMinY()+5,world.getMaxY()-3):job.terrain.height(job.g,job.after,x,z);
        boolean keep=job.terrain==null?job.protectedColumns.contains(key):terrainBlocked(c,job.g,job.terrain,x,z);
        if(TestingServer.naturalZoneColumn(c,x,z))keep=true;
        int bottom=Math.max(world.getMinY()+1,Math.min(Math.min(original,nextBase),Math.min(current,target))-4);
        int top=Math.min(world.getMaxY()-1,Math.max(TestingIslandGenerator.SEA_LEVEL,Math.max(Math.max(original,nextBase),Math.max(current,target)))+1);
        int[] water=job.waterBounds.get(key);if(water!=null){bottom=Math.min(bottom,water[0]);top=Math.max(top,water[1]);}
        // Unknown legacy content is preserved as an entire column, including its support.
        for(int y=bottom;y<=top&&!keep;y++) {
            BlockPos p=new BlockPos(x,y,z);String origin=c.edits.origins.get(p.asLong());
            if(world.getBlockEntity(p)!=null||!(origin==null||origin.equals("terrain")||origin.equals("surface"))
                    ||(origin==null&&!world.getBlockState(p).equals(job.g.naturalState(job.before,x,y,z))))keep=true;
        }
        if(keep){target=current;job.skipped++;}
        BlockState keptCover=world.getBlockState(new BlockPos(x,current,z));
        BlockState keptCap=world.getBlockState(new BlockPos(x,current+1,z));
        if(target==nextBase)job.afterHints.remove(key);else job.afterHints.put(key,target);
        for(int y=bottom;y<=top;y++) {
            BlockPos p=new BlockPos(x,y,z);var cell=c.edits.read(p);
            boolean natural=cell.entity()==null&&(Set.of("terrain","surface").contains(cell.origin()==null?"":cell.origin())
                    ||(cell.origin()==null&&cell.state().equals(job.g.naturalState(job.before,x,y,z))));
            if(!natural)continue;
            BlockState desired=keep?cell.state():job.preserveCover?materialKeepingCover(target,y,job.materialBiome,current,keptCover,keptCap):material(target,y,job.materialBiome);
            if(!keep&&job.terrain!=null&&!job.preserveCover&&target>=TestingIslandGenerator.SEA_LEVEL){if(y==target&&job.terrain.cover.containsKey(key))desired=job.terrain.cover.get(key);if(y==target+1&&job.terrain.caps.containsKey(key))desired=job.terrain.caps.get(key);}
            if(!keep&&job.terrain!=null&&job.terrain.water.containsKey(p.asLong()))desired=job.terrain.water.get(p.asLong())?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState();
            String origin=desired.equals(job.g.naturalState(job.after,x,y,z))?null:(y==target||y==target+1?"surface":"terrain");
            if(!desired.equals(cell.state())||!Objects.equals(origin,cell.origin()))
                changes.add(new TestingEdits.Change(p,cell,new TestingEdits.Cell(desired,null,origin)));
        }
    }
    private static BlockState material(int top,int y,String biome) {
        boolean dry=top>=TestingIslandGenerator.SEA_LEVEL,snow=biome.endsWith("snowy_taiga"),sand=biome.endsWith("desert");
        if(y>top) {
            if(dry&&snow&&y==top+1)return Blocks.SNOW.defaultBlockState();
            return y<TestingIslandGenerator.SEA_LEVEL?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState();
        }
        if(y==top)return !dry||sand?Blocks.SAND.defaultBlockState():Blocks.GRASS_BLOCK.defaultBlockState().setValue(SnowyBlock.SNOWY,snow);
        if(y>top-4)return dry&&!sand?Blocks.DIRT.defaultBlockState():Blocks.SAND.defaultBlockState();
        return y<0?Blocks.DEEPSLATE.defaultBlockState():Blocks.STONE.defaultBlockState();
    }

    private static void verify(Job job) throws IOException {
        if(job.partIndex<job.group.parts().size()) {
            readPart(job,job.group.parts().get(job.partIndex++));
            report(job,"Проверка геометрии: "+job.partIndex+" / "+job.group.parts().size());return;
        }
        job.partIndex=0;job.phase="APPLY";job.loaded=null;
        TestingEdits.atomic(pending(job.c),JSON.toJsonTree(new Pending(2,job.id,job.recovery?"RECOVER":"APPLY",job.headerMode,job.wasFrozen)));
    }
    private static void apply(Job job) throws IOException {
        int remaining=BUDGET;
        while(remaining>0&&job.partIndex<job.group.parts().size()) {
            if(job.loaded==null) {
                job.loadedData=readPart(job,job.group.parts().get(job.partIndex));job.loaded=job.loadedData.changes();
                if(!job.loaded.isEmpty()) {
                    BlockPos p=job.loaded.getFirst().pos();job.c.server.overworld().getChunk(p.getX()>>4,p.getZ()>>4);
                }job.cellIndex=0;
            }
            while(remaining>0&&job.cellIndex<job.loaded.size()) {
                var change=job.loaded.get(job.cellIndex);
                var from=job.forward?change.before():change.after();
                var to=job.forward?change.after():change.before();
                var actual=job.c.edits.read(change.pos());
                boolean known=same(actual,from)||job.recovery&&same(actual,to);
                // A crash can save provenance and chunk data at different moments.
                if(job.recovery)known=actual.entity()==null&&(actual.state().equals(from.state())||actual.state().equals(to.state()))
                        &&(Objects.equals(actual.origin(),from.origin())||Objects.equals(actual.origin(),to.origin()));
                if(!known)throw new IOException("Конфликт геометрии в "+change.pos()+"; доступна резервная версия "+job.backup);
                if(!job.recovery&&!from.state().equals(to.state())&&TestingServer.naturalProtectedAt(job.c,change.pos()))
                    throw new IOException("Постройка или защищённая зона изменилась в "+change.pos());
                write(job,change.pos(),to);job.cellIndex++;job.appliedCells++;remaining--;
            }
            if(job.cellIndex==job.loaded.size()){applyBiomes(job,job.group.parts().get(job.partIndex),job.loadedData);job.partIndex++;job.loaded=null;job.loadedData=null;}
        }
        report(job,(job.recovery?"Восстановление":"Геометрия")+": "+job.appliedCells+" / "+job.group.cells());
        if(job.partIndex==job.group.parts().size())finish(job);
    }
    private static boolean same(TestingEdits.Cell a,TestingEdits.Cell b) {
        return a.state().equals(b.state())&&Objects.equals(a.entity(),b.entity())&&Objects.equals(a.origin(),b.origin());
    }
    private static void write(Job job,BlockPos pos,TestingEdits.Cell cell) throws IOException {
        if(cell.entity()!=null||job.c.server.overworld().getBlockEntity(pos)!=null)throw new IOException("Контейнер не допускается в природном изменении "+pos);
        job.c.server.overworld().setBlock(pos,cell.state(),2|16);
        if(cell.origin()==null)job.c.edits.origins.remove(pos.asLong());else job.c.edits.origins.put(pos.asLong(),cell.origin());
    }
    private static void finish(Job job) throws IOException {
        var target=job.forward?job.group.after():job.group.before();
        job.g.install(target);job.c.server.getWorldGenSettings().setDirty();
        job.c.heights.clear();job.c.heights.putAll(job.forward?job.group.heightsAfter():job.group.heightsBefore());
        String biome=job.forward?job.group.biomeAfter():job.group.biomeBefore();if(biome!=null)job.c.settings.biome=biome;
        job.c.save();
        if(!job.recovery) {
            if(job.mode.equals("INITIAL"))job.c.edits.recordResize(job.group.label()==null?"Размер/форма/холмы острова":job.group.label(),job.id);
            else job.c.edits.replayCompleted(job.id,job.forward);
        }
        // Flush chunks + saved world-generation settings BEFORE retiring the recovery journal.
        job.c.server.saveEverything(false,true,true);
        TestingEdits.atomic(pending(job.c),JSON.toJsonTree(new Pending(2,job.id,"DONE",job.headerMode,job.wasFrozen)));
        Files.delete(pending(job.c));
        JOBS.remove(job.c.server);job.c.server.tickRateManager().setFrozen(job.wasFrozen);
        report(job,"Геометрия завершена: радиус "+target.radius()+", "+target.shape()+", холмы "+target.hills()+"; резервная версия "+job.backup);
        job.c.event(job.recovery?"resize_recovered":"resize_complete",job.id);
    }
    private static void fail(Job job,Exception error) {
        try {
            if(job.phase.equals("PLAN")) {
                Files.deleteIfExists(pending(job.c));JOBS.remove(job.c.server);
                job.c.server.tickRateManager().setFrozen(job.wasFrozen);job.c.edits.replayFailed();
                report(job,"Предпросмотр геометрии отменён: "+error.getMessage());return;
            }
            if(!job.recovery) {
                // Recovery chooses the durable history result; it never guesses from a partial cell counter.
                JOBS.remove(job.c.server);job.c.edits.replayFailed();recover(job.c.server);
                report(JOBS.getOrDefault(job.c.server,job),"Изменение прервано; выполняется восстановление: "+error.getMessage());
            } else {
                // Keep the journal and freeze on an unknown external conflict; never overwrite foreign content.
                report(job,"Восстановление остановлено: "+error.getMessage()+". Резервная версия "+job.backup);
                job.phase="HALTED";
            }
        } catch(Exception recoveryError) {
            job.phase="HALTED";JOBS.put(job.c.server,job);job.c.server.tickRateManager().setFrozen(true);
            report(job,"Нужна резервная версия "+job.backup+": "+recoveryError.getMessage());
        }
    }

    /** Explicit cleanup removes only composites no longer referenced by Undo/Redo or recovery. */
    public static long collectUnused(MinecraftServer server,boolean confirmed) throws IOException {
        TestingServer.Context c=authority(server);
        if(!confirmed)throw new IOException("Очистка требует подтверждения");
        if(busy(server)||c.edits.busy())throw new IOException("Дождитесь окончания изменения мира");
        c.save();
        Set<String> referenced=new HashSet<>();
        Path history=c.directory.resolve("undo.json");
        JsonObject saved=JsonParser.parseString(Files.readString(history)).getAsJsonObject();
        for(String stack:List.of("undo","redo"))for(JsonElement value:saved.getAsJsonArray(stack)) {
            var operation=TestingEdits.decode(value.getAsJsonObject());
            if(operation.resizeId()!=null){directory(c,operation.resizeId());referenced.add(operation.resizeId());}
        }
        if(Files.isRegularFile(pending(c))) {
            Pending value=JSON.fromJson(Files.readString(pending(c)),Pending.class);
            if(value==null||value.schema()!=2)throw new IOException("Повреждён журнал восстановления");
            directory(c,value.id());referenced.add(value.id());
        }
        for(String id:referenced)readGroup(c,id);
        Path root=c.directory.resolve("resize-history");if(!Files.isDirectory(root))return 0;
        List<Path> garbage=new ArrayList<>();
        try(var groups=Files.list(root)) {
            for(Path group:groups.toList()) {
                String id=group.getFileName().toString();
                if(Files.isSymbolicLink(group)||!Files.isDirectory(group)||!group.equals(directory(c,id)))throw new IOException("Недопустимый путь истории геометрии");
                if(referenced.contains(id)){readGroup(c,id);continue;}
                List<Path> files;try(var list=Files.list(group)){files=list.toList();}
                for(Path file:files) {
                    String name=file.getFileName().toString();
                    if(Files.isSymbolicLink(file)||!Files.isRegularFile(file)
                            ||!(name.equals("manifest.json")||name.matches("part-[0-9]{5}\\.json\\.gz")||name.matches("resize-.*\\.tmp")))
                        throw new IOException("Неизвестный файл истории геометрии; очистка отменена");
                }
                garbage.addAll(files);garbage.add(group);
            }
        }
        long bytes=0;
        for(Path file:garbage){if(Files.isRegularFile(file))bytes+=Files.size(file);Files.delete(file);}
        c.event("resize_history_cleanup",Long.toString(bytes));return bytes;
    }
    public static long storedBytes(MinecraftServer server) throws IOException {
        TestingServer.Context c=TestingServer.context(server);if(c==null)return 0;
        Path root=c.directory.resolve("resize-history");if(!Files.isDirectory(root))return 0;
        long size=0;
        try(var paths=Files.walk(root)) {
            for(Path path:paths.toList()) {
                if(Files.isSymbolicLink(path))throw new IOException("Ссылка в истории геометрии");
                if(Files.isRegularFile(path))size+=Files.size(path);
            }
        }return size;
    }

    private static Group readGroup(TestingServer.Context c,String id) throws IOException {
        Path file=directory(c,id).resolve("manifest.json");
        if(!Files.isRegularFile(file)||Files.size(file)>32L*1024*1024)throw new IOException("Журнал геометрии отсутствует или слишком велик");
        try {
            Group group=JSON.fromJson(Files.readString(file),Group.class);
            if(group==null||group.schema()!=2||!id.equals(group.id())||!group.source().equals(TestingArchive.worldKey(c.server.getWorldPath(LevelResource.ROOT)))
                    ||group.before()==null||group.after()==null||group.parts()==null||group.parts().size()>2500
                    ||group.heightsBefore()==null||group.heightsAfter()==null||group.cells()<0||group.cells()>MAX_CELLS)
                throw new IOException("Недопустимый манифест геометрии");
            new TestingIslandGenerator.Configuration(group.before().radius(),group.before().shape(),group.before().hills());
            new TestingIslandGenerator.Configuration(group.after().radius(),group.after().shape(),group.after().hills());
            for(String biome:new String[]{group.biomeBefore(),group.biomeAfter()})if(biome!=null) c.server.overworld().registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse(biome.contains(":")?biome:"minecraft:"+biome)));
            long count=0;Set<String> unique=new HashSet<>();
            for(Part part:group.parts()) {
                if(part==null||!part.file().matches("part-[0-9]{5}\\.json\\.gz")||!unique.add(part.file())
                        ||part.sha256()==null||!part.sha256().matches("[0-9a-f]{64}")||part.cells()<0||part.cells()>TestingEdits.LIMIT
                        ||Math.abs((long)part.chunkX()*16)>400||Math.abs((long)part.chunkZ()*16)>400)
                    throw new IOException("Недопустимая часть журнала геометрии");
                count+=part.cells();
            }
            if(count!=group.cells())throw new IOException("Размер журнала геометрии не совпадает");
            return group;
        } catch(RuntimeException e) {throw new IOException("Повреждён журнал геометрии",e);}
    }
    private static ChunkData readPart(Job job,Part part) throws IOException {
        Path file=job.dir.resolve(part.file());
        if(!Files.isRegularFile(file)||Files.size(file)>32L*1024*1024||!hash(file).equals(part.sha256()))throw new IOException("Повреждена часть журнала "+part.file());
        try(var gzip=new GZIPInputStream(Files.newInputStream(file));var reader=new InputStreamReader(new LimitedInputStream(gzip,64L*1024*1024),StandardCharsets.UTF_8)) {
            JsonObject root=JsonParser.parseReader(reader).getAsJsonObject();
            var op=TestingEdits.decode(root);
            if(op.changes().size()!=part.cells())throw new IOException("Число блоков не совпадает");
            int extent=(int)Math.ceil(Math.max(job.group.before().radius(),job.group.after().radius())*1.45)+4;
            for(var cell:op.changes())if(cell.before().entity()!=null||cell.after().entity()!=null||job.c.server.overworld().isOutsideBuildHeight(cell.pos())
                    ||Math.abs((long)cell.pos().getX())>extent||Math.abs((long)cell.pos().getZ())>extent
                    ||(cell.pos().getX()>>4)!=part.chunkX()||(cell.pos().getZ()>>4)!=part.chunkZ()
                    ||!Set.of("","terrain","surface").contains(cell.before().origin()==null?"":cell.before().origin())
                    ||!Set.of("","terrain","surface").contains(cell.after().origin()==null?"":cell.after().origin()))
                throw new IOException("Журнал природной геометрии содержит недопустимый блок");
            JsonArray ids=root.getAsJsonArray("biomePalette"),encoded=root.getAsJsonArray("biomeIndices");
            int expected=job.c.server.overworld().getHeight()/4*16;
            if(ids==null||ids.isEmpty()||ids.size()>expected||encoded==null||encoded.size()!=expected)
                throw new IOException("Недопустимая палитра биомов");
            var registry=job.c.server.overworld().registryAccess().lookupOrThrow(Registries.BIOME);
            List<Holder<Biome>> palette=new ArrayList<>();
            for(JsonElement id:ids)palette.add(registry.getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse(id.getAsString()))));
            int[] indices=new int[expected];
            for(int i=0;i<indices.length;i++){indices[i]=encoded.get(i).getAsInt();if(indices[i]<0||indices[i]>=palette.size())throw new IOException("Недопустимый индекс биома");}
            Holder<Biome> after=registry.getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse(root.get("biomeAfter").getAsString())));
            return new ChunkData(op.changes(),List.copyOf(palette),indices,after,root.has("preserveBiomes")&&root.get("preserveBiomes").getAsBoolean());
        } catch(RuntimeException e) {throw new IOException("Повреждён JSON части геометрии",e);}
    }
    private static void applyBiomes(Job job,Part part,ChunkData data) {
        var world=job.c.server.overworld();var chunk=world.getChunk(part.chunkX(),part.chunkZ());
        int min=world.getMinY()>>2;
        chunk.fillBiomesFromNoise((qx,qy,qz)->job.forward&&!data.preserveBiomes()?data.after():data.beforePalette().get(data.indices()[(qy-min)*16+(qz&3)*4+(qx&3)]));
        chunk.markUnsaved();world.getChunkSource().chunkMap.resendBiomesForChunks(List.of(chunk));
    }
    private static void writePart(Job job,Path file,int cx,int cz,TestingEdits.Operation operation) throws IOException {
        String afterBiome=job.materialBiome.contains(":")?job.materialBiome:"minecraft:"+job.materialBiome;
        JsonObject root=TestingEdits.encode(new TestingEdits.Operation(operation.name(),operation.changes(),operation.biomeBefore(),afterBiome,
                operation.resizeId(),operation.entities(),operation.id(),operation.sceneBefore(),operation.sceneAfter(),operation.documentsBefore(),operation.documentsAfter()));
        JsonArray palette=new JsonArray(),indices=new JsonArray();Map<String,Integer> unique=new LinkedHashMap<>();
        var world=job.c.server.overworld();
        for(int qy=world.getMinY()>>2;qy<((world.getMaxY()+1)>>2);qy++)for(int dz=0;dz<4;dz++)for(int dx=0;dx<4;dx++) {
            String id=world.getNoiseBiome(cx*4+dx,qy,cz*4+dz).unwrapKey().orElseThrow().identifier().toString();
            Integer index=unique.get(id);
            if(index==null){index=unique.size();unique.put(id,index);palette.add(id);}indices.add(index);
        }
        root.add("biomePalette",palette);root.add("biomeIndices",indices);root.addProperty("preserveBiomes",job.preserveBiomes);
        root.addProperty("biomeAfter",job.materialBiome.contains(":")?job.materialBiome:"minecraft:"+job.materialBiome);
        if(Files.getFileStore(file.getParent()).getUsableSpace()<32L*1024*1024)throw new IOException("Недостаточно места для журнала геометрии");
        Path temp=Files.createTempFile(file.getParent(),"resize-", ".tmp");
        try {
            try(var gzip=new GZIPOutputStream(Files.newOutputStream(temp));var writer=new OutputStreamWriter(gzip,StandardCharsets.UTF_8)) {JSON.toJson(root,writer);}
            try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file);}
        } finally {Files.deleteIfExists(temp);}
    }
    private static String hash(Path file) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(file)){byte[] buffer=new byte[65536];for(int n;(n=input.read(buffer))>=0;)if(n>0)digest.update(buffer,0,n);}
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream in,long limit){super(in);remaining=limit;}
        @Override public int read() throws IOException {if(remaining<=0)throw new IOException("Развёрнутая часть журнала слишком велика");int n=super.read();if(n>=0)remaining--;return n;}
        @Override public int read(byte[] bytes,int offset,int length) throws IOException {
            if(remaining<=0)throw new IOException("Развёрнутая часть журнала слишком велика");
            int n=super.read(bytes,offset,(int)Math.min(length,remaining));if(n>0)remaining-=n;return n;
        }
    }
}
