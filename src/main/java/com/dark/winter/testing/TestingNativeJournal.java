// Publishes durable redo records for committed native editor transactions.
package com.dark.winter.testing;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Committed native EDIT redo tail. Publication uses new immutable files and a new
 * zero-byte marker, never replacement of a file a Windows scanner may be reading.
 * Minecraft snapshots stay on the owning thread; only immutable metadata DTOs use IO.
 */
final class TestingNativeJournal {
    private static final Gson JSON=new Gson();
    private static final int MAX_RECORDS=4096,MAX_CELLS=131072,MAX_FILES=32768;
    private static final long MAX_BYTES=64L*1024*1024;
    private static final ExecutorService METADATA=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"DarkWinter native history checkpoint");thread.setDaemon(true);return thread;});
    private record Record(long sequence,boolean snapshot,String id,String file,String sha,long bytes,TestingEdits.Operation operation) {}
    private record Receipt(ChunkPos chunk,long sequence,CompletableFuture<Void> future) {}
    private static final class Checkpoint {
        final long sequence;final Future<?> metadata;final Map<ChunkPos,Long> required;
        CompletableFuture<Void> flush;
        Checkpoint(long sequence,Future<?> metadata,Map<ChunkPos,Long> required){this.sequence=sequence;this.metadata=metadata;this.required=required;}
    }
    private final TestingEdits owner;private final ServerLevel world;private final Path directory,tail;private final long baseAtLoad;
    private final List<Record> records=new ArrayList<>();
    private final Map<BlockPos,List<TestingEdits.Cell>> observed=new LinkedHashMap<>();
    private final Map<BlockPos,TestingEdits.Cell> first=new LinkedHashMap<>(),latest=new LinkedHashMap<>();
    private final Map<ChunkPos,Long> preparing=new HashMap<>(),written=new HashMap<>();
    private final List<Receipt> receipts=new ArrayList<>();
    private long sequence,generation,bytes,nativeBytes;private int cells,lastGestureTick;
    private boolean draining,loaded,validating,recovering;
    private String latestCommit="",latestMarker="",sceneBefore,sceneAfter;
    private Checkpoint checkpoint;

    TestingNativeJournal(TestingEdits owner,ServerLevel world,Path directory,long baseSequence)throws IOException {
        this.owner=owner;this.world=world;this.directory=directory;this.tail=directory.resolve("native-tail");
        this.baseAtLoad=Math.max(0,baseSequence);this.sequence=baseAtLoad;Files.createDirectories(tail);load();lastGestureTick=world.getServer().getTickCount();
    }
    long sequence(){return sequence;}
    boolean hasPending(){return !records.isEmpty();}
    boolean drained(){return records.isEmpty()&&checkpoint==null;}
    boolean committed(String id){return records.stream().anyMatch(record->!record.snapshot()&&record.id().equals(id));}
    void drain(){draining=true;}
    Set<String> sceneRefs(){Set<String> refs=new HashSet<>();for(Record record:records){if(record.operation().sceneBefore()!=null)refs.add(record.operation().sceneBefore());if(record.operation().sceneAfter()!=null)refs.add(record.operation().sceneAfter());}return Set.copyOf(refs);}

    void commit(TestingEdits.Operation operation)throws IOException {
        requireThread();validateNative(operation);append(operation,false);
        lastGestureTick=world.getServer().getTickCount();
    }
    private void append(TestingEdits.Operation operation,boolean snapshot)throws IOException {
        if(records.size()>=MAX_RECORDS||cells+operation.changes().size()>MAX_CELLS)throw new IOException("Native redo tail exceeds4096 records/131072 cells; wait for checkpoint");
        long next=sequence+1;if(next<=sequence)throw new IOException("Native sequence overflow");
        byte[] payload=JSON.toJson(TestingEdits.encode(operation)).getBytes(StandardCharsets.UTF_8);
        long extraNative=entityBytes(operation);
        if(payload.length>MAX_BYTES||bytes+payload.length>MAX_BYTES||nativeBytes+extraNative>MAX_BYTES)throw new IOException("Native redo tail exceeds64MiB");
        String file="op-"+number(next)+"-"+UUID.randomUUID()+".json",sha=TestingArchive.hashBytes(payload);
        writeNew(tail.resolve(file),payload);
        Record record=new Record(next,snapshot,operation.id(),file,sha,payload.length,operation);
        List<Record> proposed=new ArrayList<>(records);proposed.add(record);
        publish(proposed,next,owner.nativeUndoIds(),owner.nativeRedoIds());
        records.add(record);sequence=next;bytes+=payload.length;nativeBytes+=extraNative;cells+=operation.changes().size();observe(record);
    }
    private void observe(Record record){
        TestingEdits.Operation operation=record.operation();
        for(var change:operation.changes()){
            BlockPos pos=change.pos().immutable();first.putIfAbsent(pos,change.before());
            observed.computeIfAbsent(pos,key->new ArrayList<>()).add(change.before());
            observed.get(pos).add(change.after());latest.put(pos,change.after());
        }
        if(operation.sceneBefore()!=null&&sceneBefore==null)sceneBefore=operation.sceneBefore();
        if(operation.sceneAfter()!=null)sceneAfter=operation.sceneAfter();
    }

    /** Called before native tryMarkSaved/SerializableChunkData.copyOf. */
    void beforeSave(ChunkAccess chunk)throws IOException {
        requireThread();if(records.isEmpty()||recovering)return;
        ChunkPos pos=chunk.getPos();List<TestingEdits.Change> snapshots=new ArrayList<>();
        for(var entry:new ArrayList<>(latest.entrySet()))if(ChunkPos.containing(entry.getKey()).equals(pos)){
            TestingEdits.Cell actual=owner.read(entry.getKey());
            if(!same(entry.getValue(),actual))snapshots.add(new TestingEdits.Change(entry.getKey(),entry.getValue(),actual));
        }
        if(!snapshots.isEmpty())append(new TestingEdits.Operation("Native savepoint",List.copyOf(snapshots)),true);
        preparing.put(pos,sequence);
    }
    /** Original native write future, before Minecraft's exception-swallowing logger continuation. */
    void queued(ChunkPos pos,CompletableFuture<Void> future){
        Long mark=preparing.remove(pos);
        if(mark!=null&&!records.isEmpty()&&future!=null)receipts.add(new Receipt(pos,mark,future));
    }

    void tick()throws IOException {
        requireThread();if(recovering||!loaded)return;
        pollReceipts();
        if(checkpoint!=null){
            finishCheckpoint();
            if(checkpoint!=null){saveOne(checkpoint.required);return;}
        }
        if(records.isEmpty()){draining=false;return;}
        int idle=world.getServer().getTickCount()-lastGestureTick;
        if(!draining&&records.size()<64&&idle<10)return;
        beginCheckpoint();saveOne(checkpoint.required);finishCheckpoint();
    }
    private void beginCheckpoint()throws IOException {
        long mark=sequence;TestingEdits.NativeMetadata metadata=owner.nativeMetadata(mark);Path target=directory;
        Map<ChunkPos,Long> required=new LinkedHashMap<>();
        for(Record record:records)if(record.sequence()<=mark)for(var change:record.operation().changes())
            required.merge(ChunkPos.containing(change.pos()),record.sequence(),Math::max);
        Future<?> writing;
        try{writing=METADATA.submit(()->{TestingEdits.writeNativeMetadata(target,metadata);return null;});}
        catch(RejectedExecutionException failure){throw new IOException("Native metadata worker unavailable",failure);}
        checkpoint=new Checkpoint(mark,writing,Map.copyOf(required));
    }
    private void pollReceipts()throws IOException {
        Iterator<Receipt> iterator=receipts.iterator();
        while(iterator.hasNext()){
            Receipt receipt=iterator.next();if(!receipt.future().isDone())continue;
            completed(receipt.future());written.merge(receipt.chunk(),receipt.sequence(),Math::max);iterator.remove();
        }
    }
    private void saveOne(Map<ChunkPos,Long> required)throws IOException {
        for(var entry:required.entrySet()){
            if(written.getOrDefault(entry.getKey(),-1L)>=entry.getValue())continue;
            if(receipts.stream().anyMatch(receipt->receipt.chunk().equals(entry.getKey())&&receipt.sequence()>=entry.getValue()))continue;
            ChunkPos pos=entry.getKey();
            if(!world.hasChunk(pos.x(),pos.z()))throw new IOException("Native redo chunk unloaded without a save receipt: "+pos);
            ChunkAccess chunk=world.getChunk(pos.x(),pos.z());
            chunk.markUnsaved(); // A tracked current snapshot is required even if an earlier save cleared the dirty bit.
            boolean saved=((TestingNativeChunkAccess)world.getChunkSource().chunkMap).darkwinter$save(chunk);
            if(!saved&&!receipts.stream().anyMatch(receipt->receipt.chunk().equals(pos)&&receipt.sequence()>=entry.getValue())
                    &&written.getOrDefault(pos,-1L)<entry.getValue())
                throw new IOException("Native chunk has no matching queued savepoint: "+pos);
            return; // At most one native chunk snapshot in a server tick.
        }
    }
    private void finishCheckpoint()throws IOException {
        Checkpoint current=checkpoint;if(current==null)return;
        boolean covered=current.required.entrySet().stream().allMatch(entry->written.getOrDefault(entry.getKey(),-1L)>=entry.getValue());
        if(covered&&current.flush==null)current.flush=world.getChunkSource().chunkMap.synchronize(true);
        if(current.flush==null||!current.flush.isDone()||!current.metadata.isDone())return;
        completed(current.flush);completed(current.metadata);
        long mark=current.sequence;
        List<Record> retained=records.stream().filter(record->record.sequence()>mark).toList();
        // Persist per-world terrain hints/settings after the older metadata DTO finished.
        owner.nativeCheckpointMetadata();
        // Both the canonical history and every native chunk snapshot are durable before retirement.
        publish(retained,sequence,owner.nativeUndoIds(),owner.nativeRedoIds());
        List<Record> retired=records.stream().filter(record->record.sequence()<=mark).toList();
        records.clear();records.addAll(retained);owner.nativeBaseSaved(mark);checkpoint=null;rebuild();
        for(Record record:retired)deleteUnused(record.file());
        cleanCommits();
        if(records.isEmpty())draining=false;
    }
    /** Explicit bulk/STOP only: waits for already submitted plain metadata IO, never invokes Minecraft or waits for chunk futures. */
    void awaitCheckpoint()throws IOException {
        Checkpoint current=checkpoint;if(current!=null)completed(current.metadata);
    }

    TestingEdits.Operation recovery(TestingEdits.Operation pending,boolean pendingNative)throws IOException {
        if(records.isEmpty()&&!pendingNative)return null;
        if(pending!=null&&!pendingNative)throw new IOException("Generic WAL overlaps an undrained native redo tail");
        recovering=true;
        if(pending!=null){
            validateNative(pending);
            for(var change:pending.changes()){
                first.putIfAbsent(change.pos(),change.before());observed.computeIfAbsent(change.pos(),key->new ArrayList<>()).add(change.before());
                observed.get(change.pos()).add(change.after());
            }
            if(!committed(pending.id())){
                for(var change:pending.changes())latest.put(change.pos(),change.before());
                if(pending.sceneBefore()!=null)sceneAfter=pending.sceneBefore();
            }
        }
        List<TestingEdits.Change> changes=new ArrayList<>();
        for(var entry:latest.entrySet())changes.add(new TestingEdits.Change(entry.getKey(),first.get(entry.getKey()),entry.getValue()));
        changes.sort(Comparator.comparingLong(change->change.pos().asLong()));
        return new TestingEdits.Operation("Native committed redo recovery",List.copyOf(changes),null,null,null,List.of(),
                UUID.nameUUIDFromBytes((tail.toString()+"|"+latestCommit).getBytes(StandardCharsets.UTF_8)).toString(),sceneBefore,sceneAfter);
    }
    void validateRecovery()throws IOException {
        validating=true;
        try{
            for(Record record:records){validateNative(record.operation());
                TestingScene.validateRef(world.getServer(),record.operation().sceneBefore());TestingScene.validateRef(world.getServer(),record.operation().sceneAfter());}
            for(var entry:observed.entrySet()){
                BlockPos pos=entry.getKey();
                if(world.isOutsideBuildHeight(pos)||!world.hasChunkAt(pos)||!world.getWorldBorder().isWithinBounds(pos))throw new IOException("Unloaded/outside native recovery cell "+pos);
                List<TestingEdits.Cell> normalized=new ArrayList<>();
                for(var cell:entry.getValue())normalized.add(owner.normalize(pos,cell));
                entry.setValue(normalized);
            }
        }finally{validating=false;}
    }
    boolean allowed(BlockPos pos,TestingEdits.Cell cell){
        List<TestingEdits.Cell> values=observed.get(pos);return values!=null&&values.stream().anyMatch(candidate->physical(candidate,cell));
    }
    void recovered()throws IOException {
        requireThread();awaitCheckpoint();
        publish(List.of(),sequence,owner.nativeUndoIds(),owner.nativeRedoIds());
        List<Record> old=List.copyOf(records);records.clear();checkpoint=null;receipts.clear();preparing.clear();written.clear();
        recovering=false;draining=false;owner.nativeBaseSaved(sequence);rebuild();
        for(Record record:old)deleteUnused(record.file());cleanCommits();
    }

    private void load()throws IOException {
        List<Path> paths;
        try(var stream=Files.list(tail)){paths=stream.limit(MAX_FILES+1L).toList();}
        if(paths.size()>MAX_FILES)throw new IOException("Too many native tail files");
        Path selected=null;long selectedGeneration=-1;Set<Long> generations=new HashSet<>();
        for(Path path:paths){
            String name=path.getFileName().toString();if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid native journal entry");
            if(name.matches("commit-[0-9]{20}-[0-9a-f-]{36}-[0-9a-f]{64}\\.commit")){
                if(Files.size(path)!=0)throw new IOException("Invalid native journal publication marker");
                long value=parseNumber(name.substring(7,27));
                if(!generations.add(value))throw new IOException("Duplicate native commit generation");
                if(value>selectedGeneration){selected=path;selectedGeneration=value;}
            }else if(!name.matches("(op|commit)-[0-9]{20}-[0-9a-f-]{36}\\.json"))throw new IOException("Unknown native journal file");
        }
        if(selected==null){loaded=true;return;}
        String markerName=selected.getFileName().toString();String expected=markerName.substring(markerName.length()-71,markerName.length()-7);
        String dataName=markerName.substring(0,markerName.length()-72)+".json";latestCommit=dataName;latestMarker=markerName;generation=selectedGeneration;
        byte[] manifestBytes=readBytes(tail.resolve(dataName),MAX_BYTES);
        if(!TestingArchive.hashBytes(manifestBytes).equals(expected))throw new IOException("Native commit manifest SHA mismatch");
        JsonObject manifest=parseObject(manifestBytes);
        if(integer(manifest,"schema")!=1||number(manifest,"generation")!=generation)throw new IOException("Invalid native journal generation");
        sequence=number(manifest,"sequence");if(sequence<0)throw new IOException("Invalid native journal sequence");
        JsonArray rows=manifest.getAsJsonArray("records");if(rows.size()>MAX_RECORDS)throw new IOException("Too many native redo records");
        Map<String,TestingEdits.Operation> operations=new HashMap<>(owner.nativeOperations());Set<String> ids=new HashSet<>();long previous=-1;
        for(JsonElement element:rows){
            JsonObject row=element.getAsJsonObject();long seq=number(row,"sequence"),size=number(row,"bytes");
            String file=string(row,"file"),sha=string(row,"sha256"),id=string(row,"id");boolean snapshot=row.get("snapshot").getAsBoolean();
            if(seq<=previous||seq>sequence||size<0||size>MAX_BYTES||!file.matches("op-[0-9]{20}-[0-9a-f-]{36}\\.json")||!sha.matches("[0-9a-f]{64}"))throw new IOException("Invalid native record reference");
            if(!file.startsWith("op-"+number(seq)+"-"))throw new IOException("Native record sequence/name mismatch");
            previous=seq;if(!ids.add(id))throw new IOException("Duplicate native operation identity");
            byte[] payload=readBytes(tail.resolve(file),MAX_BYTES);
            if(payload.length!=size||!TestingArchive.hashBytes(payload).equals(sha)||(bytes+=size)>MAX_BYTES)throw new IOException("Native journal SHA/size mismatch");
            TestingEdits.Operation operation=TestingEdits.decode(parseObject(payload));validateNative(operation);
            if(!operation.id().equals(id)||(cells+=operation.changes().size())>MAX_CELLS||(nativeBytes+=entityBytes(operation))>MAX_BYTES)throw new IOException("Native operation reference/cell/NBT budget mismatch");
            Record record=new Record(seq,snapshot,id,file,sha,size,operation);records.add(record);observe(record);
            if(!snapshot){TestingEdits.Operation base=operations.putIfAbsent(id,operation);if(base!=null&&!TestingEdits.encode(base).equals(TestingEdits.encode(operation)))throw new IOException("Native operation identity overrides canonical history");}
        }
        if(sequence>baseAtLoad){
            List<TestingEdits.Operation> undo=history(manifest.getAsJsonArray("undo"),operations),redo=history(manifest.getAsJsonArray("redo"),operations);
            Set<String> historyIds=new HashSet<>();for(var op:undo)if(!historyIds.add(op.id()))throw new IOException("Duplicate native undo reference");
            for(var op:redo)if(!historyIds.add(op.id()))throw new IOException("Duplicate native redo reference");
            owner.nativeRestoreHistory(undo,redo);
        }
        // A newer generic canonical save at the same native watermark owns history ordering.
        loaded=true;recovering=!records.isEmpty();
    }
    private List<TestingEdits.Operation> history(JsonArray ids,Map<String,TestingEdits.Operation> operations)throws IOException {
        if(ids==null||ids.size()>128)throw new IOException("Invalid native history references");
        List<TestingEdits.Operation> result=new ArrayList<>();
        for(JsonElement value:ids){String id=value.getAsString();TestingEdits.Operation operation=operations.get(id);if(operation==null)throw new IOException("Missing native history operation "+id);result.add(operation);}
        return List.copyOf(result);
    }
    private void publish(List<Record> values,long mark,List<String> undo,List<String> redo)throws IOException {
        long next=generation+1;if(next<=generation)throw new IOException("Native generation overflow");
        JsonObject root=new JsonObject();root.addProperty("schema",1);root.addProperty("generation",next);root.addProperty("sequence",mark);
        JsonArray rows=new JsonArray();for(Record record:values){JsonObject row=new JsonObject();row.addProperty("sequence",record.sequence());row.addProperty("snapshot",record.snapshot());row.addProperty("id",record.id());row.addProperty("file",record.file());row.addProperty("sha256",record.sha());row.addProperty("bytes",record.bytes());rows.add(row);}
        root.add("records",rows);root.add("undo",JSON.toJsonTree(List.copyOf(undo)));root.add("redo",JSON.toJsonTree(List.copyOf(redo)));
        String stem="commit-"+number(next)+"-"+UUID.randomUUID();byte[] body=JSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        writeNew(tail.resolve(stem+".json"),body);
        String markerName=stem+"-"+TestingArchive.hashBytes(body)+".commit";
        try(FileChannel marker=FileChannel.open(tail.resolve(markerName),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){marker.force(true);}
        generation=next;latestCommit=stem+".json";latestMarker=markerName;
    }
    private void rebuild(){
        observed.clear();first.clear();latest.clear();bytes=0;nativeBytes=0;cells=0;sceneBefore=null;sceneAfter=null;
        for(Record record:records){bytes+=record.bytes();nativeBytes+=entityBytes(record.operation());cells+=record.operation().changes().size();observe(record);}
    }
    private void deleteUnused(String file){try{Files.deleteIfExists(tail.resolve(file));}catch(IOException ignored){}}
    private void cleanCommits(){
        try(var stream=Files.list(tail)){for(Path path:stream.toList()){String name=path.getFileName().toString();if(name.startsWith("commit-")&&!name.equals(latestCommit)&&!name.equals(latestMarker))try{Files.deleteIfExists(path);}catch(IOException ignored){}}}catch(IOException ignored){}
    }
    private static long entityBytes(TestingEdits.Operation operation){long result=0;for(var change:operation.changes()){if(change.before().entity()!=null)result+=change.before().entity().sizeInBytes();if(change.after().entity()!=null)result+=change.after().entity().sizeInBytes();}return result;}
    private static void validateNative(TestingEdits.Operation operation)throws IOException {
        if(operation.historySource()!=null||operation.resizeId()!=null||operation.biomeBefore()!=null||operation.biomeAfter()!=null||!operation.entities().isEmpty()||operation.documentsBefore()!=null||operation.documentsAfter()!=null)
            throw new IOException("Native gesture ledger contains executable non-native metadata");
        try{if(!UUID.fromString(operation.id()).toString().equals(operation.id()))throw new IOException("Invalid native operation ID");}
        catch(IllegalArgumentException failure){throw new IOException("Invalid native operation ID",failure);}
        if(operation.changes().size()>MAX_CELLS)throw new IOException("Native gesture too large");
    }
    private void requireThread()throws IOException {if(!world.getServer().isSameThread())throw new IOException("Native journal requires owner server thread");}
    private static boolean same(TestingEdits.Cell a,TestingEdits.Cell b){return physical(a,b)&&Objects.equals(a.origin(),b.origin());}
    private static boolean physical(TestingEdits.Cell a,TestingEdits.Cell b){return a.state().equals(b.state())&&Objects.equals(a.entity(),b.entity());}
    private static String number(long value){return String.format(Locale.ROOT,"%020d",value);}
    private static long parseNumber(String value)throws IOException {try{return Long.parseLong(value);}catch(NumberFormatException failure){throw new IOException("Invalid native sequence",failure);}}
    private static long number(JsonObject root,String key)throws IOException {try{return root.get(key).getAsBigDecimal().longValueExact();}catch(RuntimeException failure){throw new IOException("Invalid native integer "+key,failure);}}
    private static int integer(JsonObject root,String key)throws IOException {long value=number(root,key);if(value<0||value>Integer.MAX_VALUE)throw new IOException("Invalid native schema");return (int)value;}
    private static String string(JsonObject root,String key)throws IOException {if(!root.has(key)||!root.get(key).isJsonPrimitive()||!root.get(key).getAsJsonPrimitive().isString())throw new IOException("Missing native string "+key);return root.get(key).getAsString();}
    private static byte[] readBytes(Path file,long limit)throws IOException {if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>limit)throw new IOException("Invalid/oversized native journal file");byte[] bytes=Files.readAllBytes(file);if(bytes.length>limit)throw new IOException("Native journal grew");return bytes;}
    private static JsonObject readObject(Path file,long limit)throws IOException {try{return JsonParser.parseString(new String(readBytes(file,limit),StandardCharsets.UTF_8)).getAsJsonObject();}catch(RuntimeException failure){throw new IOException("Invalid native journal JSON",failure);}}
    private static JsonObject parseObject(byte[] bytes)throws IOException {
        try{
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try(JsonReader reader=new JsonReader(new StringReader(text))){
                reader.setStrictness(Strictness.STRICT);JsonElement value=readJson(reader,0,new int[]{0});
                if(reader.peek()!=JsonToken.END_DOCUMENT||!value.isJsonObject())throw new IOException("Native journal expects one JSON object");
                return value.getAsJsonObject();
            }
        }catch(RuntimeException failure){throw new IOException("Invalid native journal JSON",failure);}
    }
    private static JsonElement readJson(JsonReader reader,int depth,int[] nodes)throws IOException {
        if(depth>128||++nodes[0]>2_000_000)throw new IOException("Native journal JSON complexity limit");
        return switch(reader.peek()){
            case BEGIN_OBJECT->{reader.beginObject();JsonObject object=new JsonObject();while(reader.hasNext()){String name=reader.nextName();if(object.has(name))throw new IOException("Duplicate native journal field");object.add(name,readJson(reader,depth+1,nodes));}reader.endObject();yield object;}
            case BEGIN_ARRAY->{reader.beginArray();JsonArray array=new JsonArray();while(reader.hasNext())array.add(readJson(reader,depth+1,nodes));reader.endArray();yield array;}
            case STRING->new JsonPrimitive(reader.nextString());case NUMBER->JsonParser.parseString(reader.nextString());case BOOLEAN->new JsonPrimitive(reader.nextBoolean());case NULL->{reader.nextNull();yield JsonNull.INSTANCE;}default->throw new IOException("Invalid native journal JSON token");
        };
    }
    private static void writeNew(Path file,byte[] bytes)throws IOException {
        try(FileChannel output=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);
        }
    }
    private static void completed(Future<?> future)throws IOException {
        try{future.get();}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("Native persistence interrupted");}
        catch(ExecutionException failure){throw new IOException("Native persistence failed",failure.getCause());}
        catch(CancellationException failure){throw new IOException("Native persistence cancelled",failure);}
    }
}
