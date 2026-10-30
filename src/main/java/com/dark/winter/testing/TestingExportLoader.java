// Prepares existing full chunks for export without generating missing source regions.
package com.dark.winter.testing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

/** Frozen source preparation. Discover saved FULL chunks before loading; never generate an absent source region. */
public final class TestingExportLoader {
    private static final Set<String> CATEGORIES=Set.of("terrain","surface","grass","bushes","trees","building","object","settings","scenarios");
    private static final int MAX_FULL=4096,MAX_CANDIDATES=65536,MAX_REGIONS=8192,IO_PER_TICK=8;
    private static final Pattern REGION=Pattern.compile("r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca");
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"DarkWinter source inventory");thread.setDaemon(true);return thread;});
    private static final Map<MinecraftServer,Job> JOBS=new java.util.concurrent.ConcurrentHashMap<>();
    private record Inventory(Set<ChunkPos> entities) {}
    private static final class Job {
        final MinecraftServer server;final TestingServer.Context context;final Path world;final Set<String> categories;
        final String token="source-"+UUID.randomUUID();final long started=System.nanoTime();
        final Set<ChunkPos> required=new LinkedHashSet<>(),full=new LinkedHashSet<>();
        final Map<ChunkPos,CompletableFuture<Optional<CompoundTag>>> pending=new LinkedHashMap<>();
        final CompletableFuture<Inventory> inventory;
        List<ChunkPos> candidates;Set<ChunkPos> frozenFootprint;int next,checked,lastTick=-1,starts;
        volatile String failure;volatile boolean ready;
        Job(MinecraftServer server,Set<String> categories)throws IOException {
            this.server=server;context=TestingServer.context(server);world=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();this.categories=Set.copyOf(categories);
            boolean natural=categories.contains("terrain")||categories.contains("surface");
            for(var origin:context.edits.origins.entrySet())if(categories.contains(origin.getValue())||natural&&origin.getValue().equals("terrain"))required.add(ChunkPos.containing(BlockPos.of(origin.getKey())));
            if(natural)for(long key:context.heights.keySet())required.add(ChunkPos.containing(BlockPos.of(key)));
            if(categories.contains("object"))for(var entity:server.overworld().getAllEntities())if(TestingEntities.supported(entity))required.add(ChunkPos.containing(entity.blockPosition()));
            if(required.size()>MAX_FULL)throw new IOException("Источник содержит изменения более чем в4096 чанках; используйте полную копию");
            inventory=categories.contains("object")?CompletableFuture.supplyAsync(()->{try{return inventory(world);}catch(IOException e){throw new CompletionException(e);}},IO)
                    :CompletableFuture.completedFuture(new Inventory(Set.of()));
        }
    }
    private TestingExportLoader() {}
    public static boolean busy(MinecraftServer server){return JOBS.containsKey(server);}
    public static String status(MinecraftServer server) {
        Job job=JOBS.get(server);if(job==null)return "Источник не подготавливается";
        if(job.failure!=null)return "Источник отклонён: "+job.failure;
        if(job.ready)return "Источник подготовлен: "+job.full.size()+" FULL чанков";
        return job.candidates==null?"Источник: чтение заголовков сохранённых регионов":"Источник: проверено "+job.checked+" / "+job.candidates.size()+", FULL "+job.full.size();
    }
    /** Call once per server tick. Caller owns freezing and must finish/cancel after all category exports. */
    public static boolean prepare(MinecraftServer server,Set<String> categories)throws IOException {
        var context=authority(server);
        if(categories==null||categories.isEmpty()||!CATEGORIES.containsAll(categories))throw new IOException("Неизвестные категории источника");
        Job job=JOBS.get(server);
        if(job==null) {
            if(TestingServer.busy(server))throw new IOException("Дождитесь текущей операции перед подготовкой источника");
            job=new Job(server,categories);JOBS.put(server,job);
        }
        try {
            if(job.context!=context||!job.world.equals(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize())||!job.categories.equals(categories))throw new IOException("Мир или категории источника изменились");
            if(job.failure!=null)throw new IOException(job.failure);
            if(job.ready)return true;
            if(System.nanoTime()-job.started>180_000_000_000L)throw new IOException("Подготовка источника превысила180 секунд");
            if(job.candidates==null) {
                if(!job.inventory.isDone())return false;Inventory inventory;
                try{inventory=job.inventory.join();}catch(RuntimeException e){throw new IOException("Не удалось проверить сохранённые регионы источника",e);}
                job.required.addAll(inventory.entities());if(job.required.size()>MAX_FULL)throw new IOException("Более4096 чанков сущностей; используйте полную копию");
                Set<ChunkPos> all=new LinkedHashSet<>(job.required);
                if(all.size()>MAX_CANDIDATES)throw new IOException("Слишком много сохранённых исходных чанков; используйте полную копию");
                job.candidates=new ArrayList<>(all);job.candidates.sort(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
            }
            var pending=job.pending.entrySet().iterator();
            while(pending.hasNext()) {
                var entry=pending.next();if(!entry.getValue().isDone())continue;Optional<CompoundTag> stored;
                try{stored=entry.getValue().join();}catch(RuntimeException e){throw new IOException("Не читается исходный чанк "+entry.getKey(),e);}
                boolean full=stored.isPresent()&&stored.get().read("Status",ChunkStatus.CODEC).orElse(ChunkStatus.EMPTY)==ChunkStatus.FULL;
                if(full)job.full.add(entry.getKey());else if(job.required.contains(entry.getKey()))throw new IOException("Изменённый/сущностный исходный чанк отсутствует или неFULL: "+entry.getKey());
                pending.remove();job.checked++;if(job.full.size()>MAX_FULL)throw new IOException("Более4096 FULL чанков; выберите полную копию без выборочного экспорта");
            }
            int tick=server.getTickCount();if(job.lastTick!=tick){job.lastTick=tick;job.starts=0;}
            while(job.next<job.candidates.size()&&job.pending.size()<IO_PER_TICK&&job.starts<IO_PER_TICK) {
                ChunkPos chunk=job.candidates.get(job.next++);job.starts++;
                // A currently resident FULL chunk is already generated; accepting it does not generate missing disk data.
                if(server.overworld().getChunkSource().getChunkNow(chunk.x(),chunk.z())!=null){job.full.add(chunk);job.checked++;}
                else job.pending.put(chunk,server.overworld().getChunkSource().chunkMap.read(chunk));
                if(job.full.size()>MAX_FULL)throw new IOException("Более4096 FULL чанков; используйте полную копию");
            }
            if(job.next<job.candidates.size()||!job.pending.isEmpty())return false;
            if(job.frozenFootprint==null)job.frozenFootprint=Set.copyOf(job.full);
            if(!TestingImportLoader.prepareChunks(server,job.token,job.frozenFootprint))return false;
            job.ready=true;return true;
        }catch(IOException|RuntimeException failure) {
            job.failure=failure.getMessage();TestingImportLoader.releaseChunks(server,job.token);
            throw failure instanceof IOException io?io:new IOException("Источник отклонён до экспорта",failure);
        }
    }
    private static TestingServer.Context authority(MinecraftServer server)throws IOException {
        var context=TestingServer.context(server);
        if(context==null||!server.isSameThread()||!server.isSingleplayer()||(server.isPublished()&&!TestingActors.managedScope(server))||!TestingServer.isTesting(server)
                ||context.readOnlyPreview||!server.tickRateManager().isFrozen()||!Set.of("EDIT","CHARACTER","TEST_PAUSED","TEST_REVIEW").contains(context.settings.mode))
            throw new IOException("Подготовка источника требует замороженного локального авторского/извлекаемого мира");
        return context;
    }
    public static void finish(MinecraftServer server){Job job=JOBS.remove(server);if(job!=null){TestingImportLoader.releaseChunks(server,job.token);if(!job.inventory.isDone())job.inventory.cancel(true);}}
    public static void cancel(MinecraftServer server){finish(server);}
    private static Inventory inventory(Path world)throws IOException {
        // Native 26.3 ServerLevel constructs EntityStorage at <dimension>/entities.
        // EntityStorage.storeEntities writes every nonempty persistent entity chunk there and STORE_EMPTY removes empty records.
        // Unrelated terrain-only FULL chunks cannot contain a missing persistent NPC, so do not load the preloader\'s whole ocean.
        Set<ChunkPos> entities=regions(world.resolve("entities"));
        if(entities.size()>MAX_FULL)throw new IOException("Регионный индекс сущностей превышает предел выборочного переноса");
        return new Inventory(Set.copyOf(entities));
    }
    private static Set<ChunkPos> regions(Path directory)throws IOException {
        if(!Files.exists(directory))return Set.of();if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Недопустимый каталог регионов источника");
        List<Path> files;try(var entries=Files.list(directory)){files=entries.filter(path->path.getFileName().toString().endsWith(".mca")).toList();}
        if(files.size()>MAX_REGIONS)throw new IOException("Слишком много исходных регионов");Set<ChunkPos> positions=new LinkedHashSet<>();
        for(Path file:files) {
            Matcher name=REGION.matcher(file.getFileName().toString());
            if(!name.matches()||Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)<8192)throw new IOException("Повреждённый исходный регион "+file.getFileName());
            int rx,rz;try{rx=Integer.parseInt(name.group(1));rz=Integer.parseInt(name.group(2));}catch(NumberFormatException bad){throw new IOException("Недопустимые координаты региона",bad);}
            long size=Files.size(file);ByteBuffer header=ByteBuffer.allocate(4096);
            try(var input=FileChannel.open(file,StandardOpenOption.READ)){while(header.hasRemaining()){if(input.read(header)<0)throw new IOException("Неполный заголовок региона");}}
            header.flip();
            for(int slot=0;slot<1024;slot++) {
                int location=header.getInt();if(location==0)continue;int sector=location>>>8,count=location&255;
                long x=(long)rx*32+(slot&31),z=(long)rz*32+(slot>>5);
                if(sector<2||count==0||((long)sector+count)*4096>size||Math.abs(x)>1874999||Math.abs(z)>1874999)throw new IOException("Повреждены границы исходного чанка");
                positions.add(new ChunkPos((int)x,(int)z));if(positions.size()>MAX_CANDIDATES)throw new IOException("Слишком большой индекс исходных чанков");
            }
        }return positions;
    }
}
