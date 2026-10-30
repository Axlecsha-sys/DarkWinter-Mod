// Portable, owner-bound closures of actual immutable world versions.
package com.dark.winter.testing;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

/** Portable, owner-bound closures of actual immutable world versions. No replay state is synthesized. */
public final class TestingScenarioDependencies {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create(),COMPACT=new Gson();
    private static final long WORLD_LIMIT=4L*1024*1024*1024,METADATA_LIMIT=128L*1024*1024,MANIFEST_LIMIT=32L*1024*1024,FILE_LIMIT=512L*1024*1024;
    private static final int VERSION_LIMIT=256,DEPTH_LIMIT=32,OBJECT_LIMIT=100000,BUNDLE_LIMIT=1024,CATALOG_LIMIT=8192;
    private static final String MARKER="data/darkwinter-testing/dependency-root.json";
    @FunctionalInterface private interface Reader {byte[] read(TestingArchive.Entry entry)throws IOException;}
    @FunctionalInterface private interface Provider {TestingArchive.Version read(String id)throws IOException;}
    private record Graph(TestingArchive.Version root,List<TestingArchive.Version> versions,JsonObject timelines,String fingerprint) {}
    private record Fork(Graph source,TestingArchive.Version root,List<TestingArchive.Version> versions,JsonObject timelines,Map<String,byte[]> blobs,Map<String,String> ids,String signature) {}
    private TestingScenarioDependencies(){}

    public static final class Plan {
        private final JsonObject document;private final Fork fork;private final String signature;private final Graph sourceGraph;private final boolean external;
        private final Path target;private final TargetState targetState;
        private final TestingArchiveScope.Token archiveContext=TestingArchiveScope.capture().orElse(null);
        private Plan(JsonObject document,Fork fork,String signature,Graph sourceGraph,boolean external,Path target,TargetState targetState){
            this.document=document.deepCopy();this.fork=fork;this.sourceGraph=sourceGraph;this.external=external;this.target=target;
            this.targetState=targetState;this.signature=TestingArchive.hashBytes((signature+"|"+targetState.fingerprint()).getBytes(StandardCharsets.UTF_8));
        }
        public JsonObject document(){return document.deepCopy();}
        public String signature(){return signature;}
        public List<TestingArchive.Version> versions(){
            Map<String,TestingArchive.Version> versions=new LinkedHashMap<>();
            if(sourceGraph!=null)sourceGraph.versions().forEach(version->versions.put(version.id(),version));
            targetState.graphs().forEach(graph->graph.versions().forEach(version->versions.putIfAbsent(version.id(),version)));
            return List.copyOf(versions.values());
        }
        /** Full streamed SHA proof; call on the bounded dependency-I/O worker, never on the server tick. */
        public Verification verify()throws IOException {return archiveContext==null?verifyScoped():archiveContext.call(this::verifyScoped);}
        private Verification verifyScoped()throws IOException {
            checkCancelled();
            synchronized(TestingArchive.class){checkCancelled();
                VerificationReader reader=new VerificationReader();revalidate(reader);checkCancelled();reader.seal();
                return new Verification(this,reader,System.nanoTime());
            }
        }
        /** Same I/O worker, immediately after verify. No second full native payload hash pass. */
        public void publishVerified(Verification receipt)throws IOException {if(archiveContext==null)publishScoped(receipt);else archiveContext.call(()->{publishScoped(receipt);return null;});}
        private void publishScoped(Verification receipt)throws IOException {
            checkCancelled();
            synchronized(TestingArchive.class){checkCancelled();
                if(receipt==null||receipt.plan!=this||receipt.used||System.nanoTime()-receipt.createdNanos>30_000_000_000L)
                    throw new IOException("Проверка зависимостей отсутствует, устарела или уже использована");
                receipt.used=true;
                // Re-read mutable manifests/timelines and exact metadata; every immutable payload must still match its verified file identity.
                revalidate(receipt.reader);
                if(fork!=null)publishFork(fork,false,receipt.reader);
            }
        }
        private void revalidate(Reader reader)throws IOException {
            if(sourceGraph!=null){
                Graph fresh=collect(TestingArchive.read(sourceGraph.root().id()),
                        new HashSet<>(sourceGraph.versions().stream().map(TestingArchive.Version::id).toList()),external,reader);
                if(!fresh.fingerprint().equals(sourceGraph.fingerprint()))throw new IOException("Зависимости сценария изменились после обзора");
            }
            if(!targetState(document,target,reader).fingerprint().equals(targetState.fingerprint()))
                throw new IOException("История получателя изменилась после обзора");
        }
        /** Compatibility for offline/file-only callers. Native import flow uses the worker receipt explicitly. */
        public void publish()throws IOException {publishVerified(verify());}
    }
    /** Opaque, one-use proof bound to one immutable plan. It grants no native-world write permission. */
    public static final class Verification {
        private final Plan plan;private final VerificationReader reader;private final long createdNanos;private boolean used;
        private Verification(Plan plan,VerificationReader reader,long createdNanos){this.plan=plan;this.reader=reader;this.createdNanos=createdNanos;}
        public int verifiedObjects(){return reader.verified.size();}
        public long verifiedBytes(){return reader.total;}
    }
    public static Set<String> referencedVersions(JsonObject document)throws IOException {
        String path=document.has("events")?"data/darkwinter-testing/scenarios/document.json":"data/darkwinter-testing/scenario-documents.json";
        return TestingScenarioHistoryIO.references(path,bytes(document));
    }
    public static void validateOwned(JsonObject document,Path owner)throws IOException {
        String key=TestingArchive.worldKey(owner);
        for(String id:referencedVersions(document))if(!TestingArchiveScope.sameSource(TestingArchive.read(id).source(),key))
            throw new IOException("Начальная версия или точка сценария принадлежит другому острову: "+id);
    }
    public static String dependencyRoot(Path sourceSave)throws IOException {
        Path marker=sourceSave.resolve(MARKER);
        if(!Files.exists(marker,LinkOption.NOFOLLOW_LINKS))return "";
        if(Files.isSymbolicLink(marker)||!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.size(marker)>16384)
            throw new IOException("Недопустимый маркер зависимостей острова");
        JsonObject value=parse(Files.readAllBytes(marker));
        if(integer(value,"schema")!=1)throw new IOException("Неверный маркер зависимостей");
        String id=string(value,"root"),owner=string(value,"owner");
        TestingArchive.Version version=TestingArchive.read(id);
        if(!TestingArchiveScope.sameSource(version.source(),owner))throw new IOException("Маркер зависимостей не соответствует владельцу версии");
        return id;
    }
    public static void markSource(Path copy,String versionId)throws IOException {
        TestingArchive.checkedSave(copy,true);TestingArchive.Version version=TestingArchive.read(versionId);
        TestingArchive.atomicWrite(copy.resolve(MARKER),JSON.toJson(marker(version.id(),version.source())));
    }
    /** Merge compatible saved cursors without replacing authoring metadata or losing colliding named points. */
    public static JsonObject mergeTimeline(JsonObject current,JsonObject external)throws IOException {
        try {
            JsonObject result=current.deepCopy();
            if(!compatible(current,external))return result;
            JsonObject replay=current.has("replay")&&!current.get("replay").isJsonNull()
                    &&(!current.has("schema")||integer(current,"schema")!=2)?current.getAsJsonObject("replay").deepCopy():new JsonObject();
            if(!replay.has("baselineVersionId"))replay.addProperty("baselineVersionId","");
            if(!replay.has("rollbackPolicy"))replay.addProperty("rollbackPolicy","review");
            LinkedHashMap<String,JsonObject> points=new LinkedHashMap<>();
            if(replay.has("checkpoints"))for(var element:replay.getAsJsonArray("checkpoints")){
                JsonObject point=element.getAsJsonObject().deepCopy();String label=pointLabel(point);
                if(points.putIfAbsent(label,point)!=null)throw new IOException("Повторное имя контрольной точки");
            }
            if(external.has("replay")&&!external.get("replay").isJsonNull()
                    &&(!external.has("schema")||integer(external,"schema")!=2)){
                JsonObject outside=external.getAsJsonObject("replay");
                if(outside.has("checkpoints"))for(var element:outside.getAsJsonArray("checkpoints")){
                    JsonObject point=element.getAsJsonObject().deepCopy();String label=pointLabel(point);
                    JsonObject existing=points.get(label);
                    if(existing!=null&&existing.equals(point))continue;
                    if(existing!=null){
                        String version=string(point,"versionId");UUID.fromString(version);
                        String suffix=" "+version.substring(0,8),prefix="История "+label;
                        String candidate=prefix.substring(0,Math.min(prefix.length(),80-suffix.length()))+suffix;
                        int sequence=2;boolean found=false;
                        while(points.containsKey(candidate)){
                            JsonObject renamed=point.deepCopy();renamed.addProperty("label",candidate);
                            if(points.get(candidate).equals(renamed)){found=true;break;}
                            suffix=" "+version.substring(0,8)+" "+sequence++;
                            candidate=prefix.substring(0,Math.min(prefix.length(),80-suffix.length()))+suffix;
                        }
                        if(found)continue;
                        point.addProperty("label",candidate);label=pointLabel(point);
                    }
                    points.put(label,point);
                }
            }
            if(points.size()>32)throw new IOException("Сценарий содержит более 32 контрольных точек; история не заменена");
            JsonArray list=new JsonArray();points.values().forEach(list::add);replay.add("checkpoints",list);
            result.add("replay",replay);result.addProperty("schema",4);return result;
        }catch(RuntimeException failure){throw new IOException("Повреждена временная шкала сценария",failure);}
    }
    private static String pointLabel(JsonObject point)throws IOException {
        String label=string(point,"label");
        if(label.isBlank()||label.length()>80||label.contains("..")||label.chars().anyMatch(c->c<32||"\\/:*?\"<>|".indexOf(c)>=0))
            throw new IOException("Недопустимое имя контрольной точки");
        return label;
    }
    /** Embed compatible saved cursors so selected scenarios do not depend on an external client-side index. */
    public static JsonObject portableDocuments(Path sourceSave,JsonObject documents)throws IOException {
        String root=dependencyRoot(sourceSave),owner=root.isEmpty()?TestingArchive.worldKey(sourceSave):TestingArchive.read(root).source();
        JsonObject timelines=timelines(owner),result=documents.deepCopy();
        if(!result.has("scenarios"))return result;
        JsonArray scenarios=new JsonArray();
        for(var element:result.getAsJsonArray("scenarios")){
            JsonObject scenario=element.getAsJsonObject();String name=string(scenario,"name");
            scenarios.add(timelines.has(name)?mergeTimeline(scenario,timelines.getAsJsonObject(name)):scenario.deepCopy());
        }
        result.add("scenarios",scenarios);return result;
    }
    public static Plan planTransfer(JsonObject documents,String dependencyRoot,Path target)throws IOException {
        Set<String> refs=referencedVersions(documents);String targetKey=TestingArchive.worldKey(target);
        if(refs.isEmpty())return planned(documents,null,TestingArchive.hashBytes(bytes(documents)),null,false,target);
        if(dependencyRoot==null||dependencyRoot.isBlank()){
            validateOwned(documents,target);
            Graph graph=collect(TestingArchive.read(new TreeSet<>(refs).first()),refs,false);
            return planned(documents,null,TestingArchive.hashBytes((graph.fingerprint()+"|"+COMPACT.toJson(canonical(documents))).getBytes(StandardCharsets.UTF_8)),graph,false,target);
        }
        TestingArchive.Version root=TestingArchive.read(dependencyRoot);
        Graph graph=collect(root,refs,true);
        if(TestingArchiveScope.sameSource(root.source(),targetKey)){
            validateOwned(documents,target);
            return planned(documents,null,TestingArchive.hashBytes((graph.fingerprint()+"|"+COMPACT.toJson(canonical(documents))).getBytes(StandardCharsets.UTF_8)),graph,true,target);
        }
        Fork fork=fork(graph,targetKey,false,root.name(),"scenario-dependency");
        JsonObject mapped=rewriteDocument(documents,fork.ids());
        return planned(mapped,fork,TestingArchive.hashBytes((fork.signature()+"|"+COMPACT.toJson(canonical(mapped))).getBytes(StandardCharsets.UTF_8)),graph,true,target);
    }
    private record TargetState(String fingerprint,List<Graph> graphs){}
    private static Plan planned(JsonObject document,Fork fork,String signature,Graph graph,boolean external,Path target)throws IOException {
        target=target.toAbsolutePath().normalize();
        return new Plan(document,fork,signature,graph,external,target,targetState(document,target));
    }
    /** Recipient history is reviewed too: an import must not introduce an unreadable 33rd point after confirmation. */
    private static TargetState targetState(JsonObject documents,Path target)throws IOException {return targetState(documents,target,ARCHIVE_READER);}
    private static TargetState targetState(JsonObject documents,Path target,Reader reader)throws IOException {
        JsonObject external=timelines(TestingArchive.worldKey(target)),relevant=new JsonObject();Set<String> refs=new TreeSet<>();
        if(documents.has("scenarios"))for(var element:documents.getAsJsonArray("scenarios")){
            JsonObject scenario=element.getAsJsonObject();String name=string(scenario,"name");
            if(!external.has(name)||!compatible(scenario,external.getAsJsonObject(name)))continue;
            JsonObject timeline=external.getAsJsonObject(name);mergeTimeline(scenario,timeline);
            if(relevant.has(name))throw new IOException("Повторный сценарий в переносе");
            relevant.add(name,timeline.deepCopy());
            // Its baseline is not imported: only the retained recipient checkpoints are merged at runtime.
            JsonObject checkpointDocument=timeline.deepCopy();
            if(checkpointDocument.has("replay")&&!checkpointDocument.get("replay").isJsonNull())
                checkpointDocument.getAsJsonObject("replay").addProperty("baselineVersionId","");
            Set<String> points=referencedVersions(checkpointDocument);
            for(String id:points)if(!TestingArchiveScope.sameSource(TestingArchive.read(id).source(),TestingArchive.worldKey(target)))
                throw new IOException("Внешняя точка получателя принадлежит другому острову: "+id);
            refs.addAll(points);
        }
        List<Graph> graphs=refs.isEmpty()?List.of():List.of(collect(TestingArchive.read(refs.iterator().next()),refs,false,reader));
        StringBuilder fingerprint=new StringBuilder(COMPACT.toJson(canonical(relevant)));
        graphs.forEach(graph->fingerprint.append('|').append(graph.fingerprint()));
        return new TargetState(TestingArchive.hashBytes(fingerprint.toString().getBytes(StandardCharsets.UTF_8)),graphs);
    }
    public static TestingArchive.Version copyIndependent(TestingArchive.Version source,Path destination,boolean cleanSystems,String branch,String name)throws IOException {
        destination=TestingArchive.checkedSave(destination,false);
        if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new IOException("Независимая копия уже существует");
        Graph graph=collect(source,Set.of(),true);Fork fork=fork(graph,TestingArchive.worldKey(destination),cleanSystems,name,branch);
        TestingArchive.requireSpace(fork.root().bytes()+sum(fork.blobs().values()));
        // All sources and rewritten history are validated before any destination directory is created.
        publishFork(fork,true);
        TestingArchive.materialize(fork.root(),destination,false);
        return fork.root();
    }
    private static Graph collect(TestingArchive.Version root,Set<String> additional,boolean external)throws IOException {
        return collect(root,additional,external,ARCHIVE_READER);
    }
    private static Graph collect(TestingArchive.Version root,Set<String> additional,boolean external,Reader reader)throws IOException {
        checkCancelled();JsonObject lines=external?timelines(root.source()):new JsonObject();
        return graph(root,TestingArchive::read,reader,lines,additional,null);
    }
    private static Graph graph(TestingArchive.Version root,Provider provider,Reader reader,JsonObject lines,Set<String> extra,Set<String> declared)throws IOException {
        TestingArchive.validate(root);owner(root.source());
        Map<String,TestingArchive.Version> nodes=new LinkedHashMap<>();Map<String,Integer> marks=new HashMap<>();Map<String,Long> sizes=new HashMap<>();
        List<TestingArchive.Version> order=new ArrayList<>();long[] metadata={0},nativeBytes={0};
        visit(root,root.source(),provider,reader,nodes,marks,order,sizes,metadata,nativeBytes,0);
        Set<String> seeds=new TreeSet<>(extra);
        for(var entry:lines.entrySet()){safeScenarioName(entry.getKey());seeds.addAll(TestingScenarioHistoryIO.references("data/darkwinter-testing/scenarios/"+entry.getKey()+".json",bytes(entry.getValue())));}
        for(String id:seeds)if(!nodes.containsKey(id))visit(provider.read(id),root.source(),provider,reader,nodes,marks,order,sizes,metadata,nativeBytes,0);
        if(declared!=null&&!nodes.keySet().equals(declared))throw new IOException("Пакет содержит недостижимые или отсутствующие зависимости");
        JsonObject payload=envelope(root,order,lines);
        byte[] serial=bytes(payload);if(serial.length>MANIFEST_LIMIT)throw new IOException("Манифесты зависимостей превышают 32 МиБ");
        return new Graph(root,List.copyOf(order),lines.deepCopy(),TestingArchive.hashBytes(serial));
    }
    private static void visit(TestingArchive.Version value,String owner,Provider provider,Reader reader,Map<String,TestingArchive.Version> nodes,
                              Map<String,Integer> marks,List<TestingArchive.Version> order,Map<String,Long> sizes,long[] metadata,long[] nativeBytes,int depth)throws IOException {
        checkCancelled();TestingArchive.validate(value);
        if(!TestingArchiveScope.sameSource(value.source(),owner))throw new IOException("Версия зависимости принадлежит другому острову: "+value.id());
        Integer state=marks.get(value.id());if(state!=null){if(state==1)throw new IOException("Цикл зависимостей сценария: "+value.id());return;}
        if(depth>DEPTH_LIMIT||nodes.size()>=VERSION_LIMIT)throw new IOException("Зависимости превышают 256 версий или глубину 32");
        marks.put(value.id(),1);nodes.put(value.id(),value);
        Set<String> refs=new TreeSet<>();
        for(var entry:value.files()){
            checkCancelled();Long old=sizes.putIfAbsent(entry.sha256(),entry.size());
            if(old!=null&&old.longValue()!=entry.size())throw new IOException("Один SHA объявлен с разными размерами");
            if(old==null&&(nativeBytes[0]+=entry.size())>WORLD_LIMIT)throw new IOException("Полная библиотека зависимостей превышает 4 ГиБ");
            if(sizes.size()>OBJECT_LIMIT)throw new IOException("Более 100000 объектов зависимостей");
            // Verify every complete native payload, even files that contain no scenario definitions.
            if(old==null)verify(reader,entry);
            if(known(entry.path())){
                if(entry.size()>METADATA_LIMIT||(metadata[0]+=entry.size())>METADATA_LIMIT)throw new IOException("Метаданные зависимостей превышают 128 МиБ");
                refs.addAll(TestingScenarioHistoryIO.references(entry.path(),reader.read(entry)));
            }
        }
        Map<String,byte[]> completeMetadata=new LinkedHashMap<>();
        for(var entry:value.files())if(known(entry.path()))completeMetadata.put(entry.path(),reader.read(entry));
        Map<String,String> identity=new HashMap<>();for(String id:refs)identity.put(id,id);
        TestingScenarioHistoryIO.rewrite(completeMetadata,identity); // Also verifies every retained WAL document reference before export.
        completeMetadata.clear();
        if(!value.thumbnail().isEmpty()){
            var icon=value.files().stream().filter(entry->entry.path().equals(value.thumbnail())).findFirst().orElseThrow(()->new IOException("Нет миниатюры"));
            if(reader instanceof StagedReader staged)TestingLibraryMetadata.png(staged.path(icon));
            else if(reader instanceof ForkReader rewritten){if(rewritten.original instanceof StagedReader staged)TestingLibraryMetadata.png(staged.path(icon));else TestingLibraryMetadata.png(TestingArchive.verifiedBlob(icon));}
            else TestingArchive.thumbnail(value);
        }
        for(String id:refs)visit(provider.read(id),owner,provider,reader,nodes,marks,order,sizes,metadata,nativeBytes,depth+1);
        marks.put(value.id(),2);order.add(value);
    }
    private static Fork fork(Graph graph,String target,boolean cleanRoot,String rootName,String branch)throws IOException {return fork(graph,target,cleanRoot,rootName,branch,ARCHIVE_READER);}
    private static Fork fork(Graph graph,String target,boolean cleanRoot,String rootName,String branch,Reader sourceReader)throws IOException {
        owner(target);Map<String,String> ids=new LinkedHashMap<>();int generation=0;
        do {
            ids.clear();
            for(var version:graph.versions())ids.put(version.id(),UUID.nameUUIDFromBytes(("DarkWinter/scenario-owner/v1|"+target+"|"+graph.fingerprint()+"|"+generation+"|"+version.id()).getBytes(StandardCharsets.UTF_8)).toString());
            if(new HashSet<>(ids.values()).size()!=ids.size())throw new IOException("Коллизия идентификаторов зависимостей");
            generation++;
            if(generation>32)throw new IOException("Не удалось назначить независимые идентификаторы");
        }while(ids.values().stream().anyMatch(TestingScenarioDependencies::deleted));
        Map<String,byte[]> changes=new LinkedHashMap<>();List<TestingArchive.Version> versions=new ArrayList<>();TestingArchive.Version mappedRoot=null;long totalMetadata=0;
        for(var source:graph.versions()){
            checkCancelled();Map<String,byte[]> files=knownFiles(source,sourceReader);var rewrite=TestingScenarioHistoryIO.rewrite(files,ids);
            Map<String,byte[]> replacements=new LinkedHashMap<>(rewrite.replacements());
            String id=ids.get(source.id());replacements.put(MARKER,bytes(marker(id,target)));
            List<TestingArchive.Entry> entries=new ArrayList<>();
            for(var entry:source.files())if(!com.dark.winter.online.world.OnlineWorldMetadata.isAuthorityPath(entry.path())&&!rewrite.removals().contains(entry.path())&&!replacements.containsKey(entry.path())){
                String path=entry.path();
                if(source.id().equals(graph.root().id())&&cleanRoot&&path.equals("data/darkwinter-testing/settings.json")){
                    path="data/darkwinter-testing/imported-settings.json";
                    String proposed=path;if(source.files().stream().anyMatch(existing->existing.path().equals(proposed)))
                        path="data/darkwinter-testing/imported-settings-"+id+".json";
                }
                entries.add(new TestingArchive.Entry(path,entry.sha256(),entry.size()));
            }
            for(var entry:replacements.entrySet()){
                byte[] data=entry.getValue();totalMetadata+=data.length;if(totalMetadata>METADATA_LIMIT)throw new IOException("Переписанные зависимости превышают 128 МиБ");
                String sha=TestingArchive.hashBytes(data);changes.put(sha,data);entries.add(new TestingArchive.Entry(entry.getKey(),sha,data.length));
            }
            entries.sort(Comparator.comparing(TestingArchive.Entry::path));
            long count=entries.stream().mapToLong(TestingArchive.Entry::size).sum();
            boolean primary=source.id().equals(graph.root().id());
            var result=new TestingArchive.Version(2,source.minecraft(),id,primary?TestingArchive.safeName(rootName):source.name(),target,
                    TestingArchive.safeName(primary?branch:"scenario-dependency"),ids.getOrDefault(source.parent(),source.parent()),source.created(),false,count,List.copyOf(entries),
                    source.description(),source.category(),source.thumbnail());
            TestingArchive.validate(result);versions.add(result);if(primary)mappedRoot=result;
        }
        JsonObject timelines=new JsonObject();
        for(var entry:graph.timelines().entrySet())timelines.add(entry.getKey(),rewriteScenario(entry.getValue().getAsJsonObject(),ids));
        if(mappedRoot==null)throw new IOException("Нет корневой версии зависимости");
        String signature=TestingArchive.hashBytes(bytes(envelope(mappedRoot,versions,timelines)));
        ForkReader verification=new ForkReader(changes,sourceReader);Map<String,TestingArchive.Version> mappedTable=table(versions);
        graph(mappedRoot,id->{var version=mappedTable.get(id);if(version==null)throw new IOException("Переписанная зависимость отсутствует: "+id);return version;},
                verification,timelines,Set.of(),mappedTable.keySet());
        return new Fork(graph,mappedRoot,List.copyOf(versions),timelines,Map.copyOf(changes),Map.copyOf(ids),signature);
    }
    private static JsonObject rewriteDocument(JsonObject value,Map<String,String> ids)throws IOException {
        String path=value.has("events")?"data/darkwinter-testing/scenarios/document.json":"data/darkwinter-testing/scenario-documents.json";
        byte[] before=bytes(value);var rewritten=TestingScenarioHistoryIO.rewrite(Map.of(path,before),ids).replacements().get(path);
        return parse(rewritten==null?before:rewritten);
    }
    private static JsonObject rewriteScenario(JsonObject value,Map<String,String> ids)throws IOException{return rewriteDocument(value,ids);}
    private static Map<String,byte[]> knownFiles(TestingArchive.Version version,Reader reader)throws IOException {
        Map<String,byte[]> result=new LinkedHashMap<>();long total=0;
        for(var entry:version.files())if(known(entry.path())){if((total+=entry.size())>METADATA_LIMIT)throw new IOException("Метаданные версии превышают 128 МиБ");result.put(entry.path(),reader.read(entry));}
        return result;
    }
    private static boolean known(String path)throws IOException{return TestingScenarioHistoryIO.isMetadataPath(path);}
    private static byte[] readBlob(TestingArchive.Entry entry)throws IOException {
        Path file=TestingArchive.verifiedBlob(entry);if(entry.size()>METADATA_LIMIT)throw new IOException("Метаданные слишком велики");return Files.readAllBytes(file);
    }
    private static void verify(Reader reader,TestingArchive.Entry entry)throws IOException {
        if(reader==ARCHIVE_READER){TestingArchive.verifyBlob(entry);return;}
        // Reader implementations used for staged imports perform the same complete streamed verification.
        if(reader instanceof StagedReader staged){staged.verify(entry);return;}
        if(reader instanceof ForkReader fork){fork.verify(entry);return;}
        if(reader instanceof VerificationReader verification){verification.verify(entry);return;}
        TestingArchive.verifyBlob(entry);
    }
    private static final Reader ARCHIVE_READER=TestingScenarioDependencies::readBlob;
    private record FileStamp(long size,java.nio.file.attribute.FileTime modified,java.nio.file.attribute.FileTime created,Object key) {
        static FileStamp read(Path file)throws IOException {
            var attrs=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attrs.isRegularFile()||attrs.isSymbolicLink())throw new IOException("Недопустимый объект зависимости");
            return new FileStamp(attrs.size(),attrs.lastModifiedTime(),attrs.creationTime(),attrs.fileKey());
        }
    }
    /** Full hashing happens once per distinct object; later publication can only consume this sealed proof. */
    private static final class VerificationReader implements Reader {
        private final Map<String,FileStamp> verified=new HashMap<>();private boolean sealed;private long total;
        void seal(){sealed=true;}
        void verify(TestingArchive.Entry entry)throws IOException {
            checkCancelled();Path file=TestingArchive.blobPath(entry.sha256());FileStamp now=FileStamp.read(file),known=verified.get(entry.sha256());
            if(known!=null){
                if(known.size()!=entry.size()||!known.equals(now))throw new IOException("Объект зависимости изменился после SHA-256 проверки");
                return;
            }
            if(sealed)throw new IOException("Публикация требует новой полной проверки зависимости");
            if(now.size()!=entry.size()||!TestingArchive.hash(file).equals(entry.sha256())||!now.equals(FileStamp.read(file)))
                throw new IOException("SHA-256 зависимости не совпадает: "+entry.path());
            if(verified.size()>=OBJECT_LIMIT||(total+=entry.size())>WORLD_LIMIT)throw new IOException("Проверка зависимостей превышает 4 ГиБ / 100000 объектов");
            verified.put(entry.sha256(),now);
        }
        public byte[] read(TestingArchive.Entry entry)throws IOException {
            verify(entry);if(entry.size()>METADATA_LIMIT)throw new IOException("Метаданные слишком велики");
            byte[] data=Files.readAllBytes(TestingArchive.blobPath(entry.sha256()));
            if(data.length!=entry.size()||!TestingArchive.hashBytes(data).equals(entry.sha256()))throw new IOException("Метаданные зависимости изменились после проверки");
            verify(entry);return data;
        }
    }
    private static final class ForkReader implements Reader {
        final Map<String,byte[]> changes;final Reader original;
        ForkReader(Map<String,byte[]> changes,Reader original){this.changes=changes;this.original=original;}
        void verify(TestingArchive.Entry entry)throws IOException{
            byte[] data=changes.get(entry.sha256());if(data==null){TestingScenarioDependencies.verify(original,entry);return;}
            if(data.length!=entry.size()||!TestingArchive.hashBytes(data).equals(entry.sha256()))throw new IOException("Переписанный объект повреждён");
        }
        public byte[] read(TestingArchive.Entry entry)throws IOException{
            verify(entry);byte[] data=changes.get(entry.sha256());return data==null?original.read(entry):data.clone();
        }
    }
    private static final class StagedReader implements Reader {
        final Path objects;StagedReader(Path objects){this.objects=objects;}
        Path path(TestingArchive.Entry entry){return objects.resolve(entry.sha256());}
        void verify(TestingArchive.Entry entry)throws IOException {Path p=path(entry);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(p)||Files.size(p)!=entry.size()||!TestingArchive.hash(p).equals(entry.sha256()))throw new IOException("SHA-256 зависимости не совпадает: "+entry.path());}
        public byte[] read(TestingArchive.Entry entry)throws IOException {verify(entry);if(entry.size()>METADATA_LIMIT)throw new IOException("Метаданные слишком велики");return Files.readAllBytes(path(entry));}
    }

    public static Path exportVersion(String id)throws IOException {
        Graph graph=collect(TestingArchive.read(id),Set.of(),true);JsonObject envelope=envelope(graph.root(),graph.versions(),graph.timelines());
        Path dir=TestingArchive.root().resolve("exports");Files.createDirectories(dir);Path output=dir.resolve(id+".dwisland"),temp=Files.createTempFile(dir,"export-",".tmp");
        try {
            try(var zip=new ZipOutputStream(Files.newOutputStream(temp))){
                zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(bytes(envelope));zip.closeEntry();Set<String> written=new HashSet<>();
                for(var version:graph.versions())for(var entry:version.files())if(written.add(entry.sha256())){
                    zip.putNextEntry(new ZipEntry("objects/"+entry.sha256()));Files.copy(TestingArchive.verifiedBlob(entry),zip);zip.closeEntry();
                }
            }
            TestingArchive.atomicMove(temp,output);return output;
        }finally{Files.deleteIfExists(temp);}
    }
    public static TestingArchive.Version importVersion(String filename)throws IOException {
        Path input=exchange(filename);Path stage=TestingArchive.root().resolve("imports").resolve(UUID.randomUUID().toString());Files.createDirectories(stage);
        try {
        Set<String> names=new HashSet<>();long total=0;int count=0;
        try(var zip=new ZipInputStream(Files.newInputStream(input))){
            ZipEntry entry;byte[] buffer=new byte[65536];
            while((entry=zip.getNextEntry())!=null){
                String name=entry.getName();if(++count>OBJECT_LIMIT+1||entry.isDirectory()||!names.add(name)||!(name.equals("manifest.json")||name.matches("objects/[0-9a-f]{64}")))throw new IOException("Недопустимый объект ZIP");
                Path file=stage.resolve(name);Files.createDirectories(file.getParent());long size=0;
                try(var out=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)){int read;while((read=zip.read(buffer))!=-1){checkCancelled();size+=read;total+=read;if(size>(name.equals("manifest.json")?MANIFEST_LIMIT:FILE_LIMIT)||total>WORLD_LIMIT)throw new IOException("Пакет зависимостей превышает пределы");out.write(buffer,0,read);}}
            }
        }
        JsonObject header=parse(readBounded(stage.resolve("manifest.json"),MANIFEST_LIMIT));Parsed parsed=decodeEnvelope(header);
        Map<String,TestingArchive.Version> table=table(parsed.versions());StagedReader reader=new StagedReader(stage.resolve("objects"));
        Graph graph=graph(parsed.root(),id->{var v=table.get(id);if(v==null)throw new IOException("В пакете отсутствует зависимость "+id);return v;},reader,parsed.timelines(),Set.of(),table.keySet());
        Set<String> expected=new HashSet<>();expected.add("manifest.json");for(var version:graph.versions())for(var entry:version.files())expected.add("objects/"+entry.sha256());
        if(!expected.equals(names))throw new IOException("Пакет содержит неиспользуемые или отсутствующие объекты");
        // Fork only metadata in memory, using the fully validated staging bytes. Publication happens last.
        String alias=TestingArchive.hashBytes(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)).substring(0,24);
        Fork fork=fork(graph,alias,false,graph.root().name(),graph.root().branch(),reader);
        TestingArchive.requireSpace(total+sum(fork.blobs().values()));
        synchronized(TestingArchive.class){
            for(var version:graph.versions())for(var entry:version.files())publishBlobFile(entry.sha256(),reader.path(entry),entry.size());
            publishFork(fork,true);
        }
        return fork.root();
        }finally{cleanImportStage(stage);}
    }
    private static void cleanImportStage(Path stage)throws IOException {
        Path root=TestingArchive.root().resolve("imports").toAbsolutePath().normalize(),target=stage.toAbsolutePath().normalize();
        if(!target.getParent().equals(root)||!target.getFileName().toString().matches("[0-9a-f-]{36}"))
            throw new IOException("Небезопасный путь временного импорта");
        if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))return;
        try(var stream=Files.walk(target)){
            List<Path> paths=bounded(stream,OBJECT_LIMIT+3,"Слишком много временных объектов импорта");
            for(Path path:paths)if(Files.isSymbolicLink(path))throw new IOException("Ссылка во временном импорте");
            for(Path path:paths.stream().sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
        }
    }
    public static TestingArchive.Version readExportMetadata(String filename)throws IOException {
        Path input=exchange(filename);long size=Files.size(input),modified=Files.getLastModifiedTime(input).toMillis();Map<String,Long> entries=new HashMap<>();
        try(var zip=new ZipFile(input.toFile())){
            var enumeration=zip.entries();int count=0;long total=0;
            while(enumeration.hasMoreElements()){var entry=enumeration.nextElement();String name=entry.getName();if(++count>OBJECT_LIMIT+1||entry.isDirectory()||entries.putIfAbsent(name,entry.getSize())!=null||!(name.equals("manifest.json")||name.matches("objects/[0-9a-f]{64}"))||entry.getSize()<0||entry.getSize()>(name.equals("manifest.json")?MANIFEST_LIMIT:FILE_LIMIT)||(total+=entry.getSize())>WORLD_LIMIT)throw new IOException("Недопустимая структура ZIP");}
            var manifest=zip.getEntry("manifest.json");if(manifest==null)throw new IOException("Нет манифеста острова");
            JsonObject header;try(var stream=zip.getInputStream(manifest)){header=parse(readBounded(stream,MANIFEST_LIMIT));}
            Parsed parsed=decodeEnvelope(header);Map<String,TestingArchive.Version> table=table(parsed.versions());long declared=0;Set<String> expected=new HashSet<>();expected.add("manifest.json");
            for(var version:table.values()){TestingArchive.validate(version);for(var entry:version.files()){String name="objects/"+entry.sha256();if(expected.add(name))declared+=entry.size();if(!Objects.equals(entries.get(name),entry.size()))throw new IOException("В ZIP отсутствует заявленный объект");}}
            if(declared>WORLD_LIMIT||!expected.equals(entries.keySet()))throw new IOException("Объекты ZIP не соответствуют манифестам");
            if(Files.size(input)!=size||Files.getLastModifiedTime(input).toMillis()!=modified)throw new IOException("Файл изменился во время индексирования");
            return parsed.root();
        }
    }
    private record Parsed(TestingArchive.Version root,List<TestingArchive.Version> versions,JsonObject timelines){}
    private static Parsed decodeEnvelope(JsonObject value)throws IOException {
        try {
            if(integer(value,"schema")==2){var version=JSON.fromJson(value,TestingArchive.Version.class);TestingArchive.validate(version);return new Parsed(version,List.of(version),new JsonObject());}
            if(integer(value,"schema")!=3||!Set.of("schema","root","versions","timelines").equals(value.keySet()))throw new IOException("Неподдерживаемый формат пакета зависимостей");
            var root=JSON.fromJson(value.get("root"),TestingArchive.Version.class);List<TestingArchive.Version> versions=new ArrayList<>();
            for(var element:value.getAsJsonArray("versions"))versions.add(JSON.fromJson(element,TestingArchive.Version.class));
            if(versions.size()>VERSION_LIMIT)throw new IOException("Слишком много версий пакета");
            owner(root.source());for(var version:versions)if(!version.source().equals(root.source()))throw new IOException("Пакет содержит чужую зависимость");
            Map<String,TestingArchive.Version> table=table(versions);if(!table.containsKey(root.id())||!sameContent(table.get(root.id()),root))throw new IOException("Корневая версия не совпадает с таблицей");
            JsonObject timelines=value.getAsJsonObject("timelines");if(timelines.size()>128)throw new IOException("Слишком много внешних сценариев");
            for(var entry:timelines.entrySet()){safeScenarioName(entry.getKey());TestingScenarioHistoryIO.references("data/darkwinter-testing/scenarios/"+entry.getKey()+".json",bytes(entry.getValue()));}
            return new Parsed(root,List.copyOf(versions),timelines.deepCopy());
        }catch(RuntimeException error){throw new IOException("Повреждён пакет зависимостей",error);}
    }
    private static Map<String,TestingArchive.Version> table(List<TestingArchive.Version> versions)throws IOException {
        Map<String,TestingArchive.Version> result=new LinkedHashMap<>();for(var version:versions){TestingArchive.validate(version);if(result.putIfAbsent(version.id(),version)!=null)throw new IOException("Повторный идентификатор версии");}return result;
    }
    private static JsonObject envelope(TestingArchive.Version root,List<TestingArchive.Version> versions,JsonObject timelines){
        JsonObject result=new JsonObject();result.addProperty("schema",3);result.add("root",JSON.toJsonTree(root));
        JsonArray list=new JsonArray();versions.stream().sorted(Comparator.comparing(TestingArchive.Version::id)).forEach(version->list.add(JSON.toJsonTree(version)));
        result.add("versions",list);result.add("timelines",canonical(timelines));return result;
    }
    private static void publishFork(Fork fork,boolean external)throws IOException {publishFork(fork,external,ARCHIVE_READER);}
    private static void publishFork(Fork fork,boolean external,Reader reader)throws IOException {
        checkCancelled();
        synchronized(TestingArchive.class){checkCancelled();
        // All immutable payload and catalog collisions are checked before atomic bundle publication.
        Set<String> verified=new HashSet<>();
        for(var version:fork.versions())for(var entry:version.files())
            if(!fork.blobs().containsKey(entry.sha256())&&verified.add(entry.sha256()))verify(reader,entry);
        Map<String,TestingArchive.Version> existing=catalog();
        for(var version:fork.versions()){
            TestingArchive.Version old=existing.get(version.id());Path flat=TestingArchive.manifestPath(version.id());
            if(Files.isRegularFile(flat)){old=TestingArchive.read(version.id());}
            if(deleted(version.id())||old!=null&&!sameContent(old,version))throw new IOException("Коллизия независимой версии; существующий архив не изменён");
        }
        for(var entry:fork.blobs().entrySet())publishBlob(entry.getKey(),entry.getValue());
        JsonObject payload=envelope(fork.root(),fork.versions(),fork.timelines());JsonObject signed=new JsonObject();signed.addProperty("schema",1);signed.addProperty("sha256",TestingArchive.hashBytes(bytes(payload)));signed.add("payload",payload);
        Path bundle=TestingArchive.root().resolve("bundles").resolve(fork.root().id()+".json");
        if(Files.exists(bundle)){if(!parse(Files.readAllBytes(bundle)).equals(signed))throw new IOException("Коллизия пакета независимых версий");return;}
        if(external&&!fork.timelines().isEmpty())publishTimelines(fork.root().source(),fork.timelines());
        checkCancelled();TestingArchive.atomicWrite(bundle,JSON.toJson(signed));
            }
    }
    public static Map<String,TestingArchive.Version> catalog()throws IOException {
        synchronized(TestingArchive.class){
        Path directory=TestingArchive.root().resolve("bundles");if(!Files.isDirectory(directory))return Map.of();
        Map<String,TestingArchive.Version> result=new LinkedHashMap<>();long total=0;List<Path> paths;
        try(var stream=Files.list(directory)){
            List<Path> listed=bounded(stream,BUNDLE_LIMIT*2,"Слишком много файлов каталога зависимостей");
            paths=new ArrayList<>();
            for(Path path:listed){
                if(path.getFileName().toString().matches("dw-[0-9]+\\.tmp")
                        &&!Files.isSymbolicLink(path)&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))continue;
                paths.add(path);
            }
        }
        if(paths.size()>BUNDLE_LIMIT)throw new IOException("Более 1024 пакетов версий");
        for(Path path:paths){
            if(!path.getFileName().toString().matches("[0-9a-f-]{36}\\.json")||Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||(total+=Files.size(path))>METADATA_LIMIT)throw new IOException("Недопустимый каталог зависимостей");
            JsonObject signed=parse(readBounded(path,MANIFEST_LIMIT));if(integer(signed,"schema")!=1||!TestingArchive.hashBytes(bytes(signed.get("payload"))).equals(string(signed,"sha256")))throw new IOException("SHA-256 пакета версий не совпадает");
            Parsed parsed=decodeEnvelope(signed.getAsJsonObject("payload"));if(!path.getFileName().toString().equals(parsed.root().id()+".json"))throw new IOException("Имя пакета не совпадает с корнем");
            for(var version:parsed.versions())if(!deleted(version.id())){if(result.putIfAbsent(version.id(),version)!=null)throw new IOException("Повторный ID в каталоге зависимостей");if(result.size()>CATALOG_LIMIT)throw new IOException("Каталог превышает 8192 версии");}
        }
        return Map.copyOf(result);
            }
    }
    public static TestingArchive.Version bundled(String id)throws IOException {return catalog().get(id);}
    public static boolean deleted(String id){return Files.exists(TestingArchive.root().resolve("deleted").resolve(id),LinkOption.NOFOLLOW_LINKS);}
    public static void deleteVersion(String id)throws IOException {
        assertUnreferenced(id);TestingArchive.atomicWrite(TestingArchive.root().resolve("deleted").resolve(id),"deleted");
        Files.deleteIfExists(TestingArchive.manifestPath(id));
    }
    public static void assertUnreferenced(String id)throws IOException {
        long[] total={0};int[] count={0};
        for(var version:TestingArchive.versions(null))if(!version.id().equals(id)){
            for(var entry:version.files())if(known(entry.path())){
                budget(entry.size(),total,count);
                if(TestingScenarioHistoryIO.references(entry.path(),readBlob(entry)).contains(id))throw new IOException("Версия нужна сценарию или Undo/Redo: "+version.name());
            }
        }
        Path timelines=TestingArchive.root().resolve("scenario-timelines");
        if(Files.isDirectory(timelines))try(var stream=Files.walk(timelines,2)){
            List<Path> paths=bounded(stream,8192,"Слишком много временных шкал для безопасного удаления");
            for(Path path:paths){if(Files.isSymbolicLink(path))throw new IOException("Ссылка в истории сценария");
                if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&path.toString().endsWith(".json")){
                    budget(Files.size(path),total,count);
                    if(TestingScenarioHistoryIO.references("data/darkwinter-testing/scenarios/document.json",readBounded(path,1024*1024)).contains(id))throw new IOException("Версия нужна сохранённой временной шкале сценария");
                }
            }
        }
        Path saves=net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("saves").toAbsolutePath().normalize();
        if(Files.isDirectory(saves))try(var worlds=Files.list(saves)){
            for(Path world:bounded(worlds,512,"Слишком много миров для безопасной проверки зависимостей")){
                if(!TestingArchiveScope.includesWorld(world))continue;
                if(Files.isSymbolicLink(world))throw new IOException("Ссылка среди сохранённых миров");
                if(!Files.isRegularFile(world.resolve("data/darkwinter-testing.properties")))continue;
                Path data=world.resolve("data/darkwinter-testing");if(!Files.isDirectory(data))continue;
                try(var stream=Files.walk(data,3)){
                    for(Path path:bounded(stream,8192,"Слишком много метаданных мира для безопасного удаления")){
                        if(Files.isSymbolicLink(path))throw new IOException("Ссылка в метаданных мира");
                        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))continue;
                        String relative=world.relativize(path).toString().replace('\\','/');
                        if(!relative.equals(MARKER)&&!known(relative))continue;
                        budget(Files.size(path),total,count);byte[] bytes=readBounded(path,METADATA_LIMIT);
                        if(relative.equals(MARKER)){if(string(parse(bytes),"root").equals(id))throw new IOException("Версия является корнем независимого острова "+world.getFileName());}
                        else if(TestingScenarioHistoryIO.references(relative,bytes).contains(id))throw new IOException("Версия нужна сохранённому сценарию мира "+world.getFileName());
                    }
                }
            }
        }
    }
    private static void budget(long bytes,long[] total,int[] count)throws IOException {
        if(bytes>METADATA_LIMIT||(total[0]+=bytes)>256L*1024*1024||++count[0]>16384)throw new IOException("Проверка зависимостей превышает безопасный бюджет метаданных");
    }
    private static List<Path> bounded(java.util.stream.Stream<Path> stream,int limit,String reason)throws IOException {
        List<Path> values=stream.limit(limit+1L).toList();if(values.size()>limit)throw new IOException(reason);return values;
    }
    private static void publishBlob(String hash,byte[] data)throws IOException {
        checkCancelled();if(!TestingArchive.hashBytes(data).equals(hash))throw new IOException("SHA переписанного объекта не совпадает");
        Path target=TestingArchive.blobPath(hash);if(Files.exists(target)){if(!TestingArchive.hash(target).equals(hash))throw new IOException("Повреждён общий объект");return;}
        Files.createDirectories(target.getParent());Path temp=Files.createTempFile(target.getParent(),"dependency-",".tmp");
        try{Files.write(temp,data);TestingArchive.atomicMove(temp,target);}finally{Files.deleteIfExists(temp);}
    }
    private static void publishBlobFile(String hash,Path source,long size)throws IOException {
        checkCancelled();Path target=TestingArchive.blobPath(hash);if(Files.exists(target)){if(Files.size(target)!=size||!TestingArchive.hash(target).equals(hash))throw new IOException("Повреждён общий объект");return;}
        Files.createDirectories(target.getParent());Path temp=Files.createTempFile(target.getParent(),"dependency-",".tmp");
        try{
            try(var output=Files.newOutputStream(temp,StandardOpenOption.TRUNCATE_EXISTING)){copyChecked(source,output);}
            if(Files.size(temp)!=size||!TestingArchive.hash(temp).equals(hash))throw new IOException("Объект изменился после проверки");
            checkCancelled();TestingArchive.atomicMove(temp,target);
        }finally{Files.deleteIfExists(temp);}
    }
    private static JsonObject timelines(String owner)throws IOException {
        owner(owner);JsonObject result=new JsonObject();Path directory=TestingArchive.root().resolve("scenario-timelines").resolve(TestingArchiveScope.canonicalSource(owner));
        if(!Files.isDirectory(directory))return result;List<Path> paths;try(var stream=Files.list(directory)){paths=stream.limit(129).toList();}
        if(paths.size()>128)throw new IOException("Слишком много внешних сценариев");
        for(Path path:paths){if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!path.getFileName().toString().endsWith(".json"))throw new IOException("Недопустимый файл временной шкалы");JsonObject doc=parse(readBounded(path,1024*1024));String name=string(doc,"name");safeScenarioName(name);if(!path.getFileName().toString().equals(name+".json")||result.has(name))throw new IOException("Имя временной шкалы не совпадает");result.add(name,doc);}
        return result;
    }
    private static void publishTimelines(String owner,JsonObject timelines)throws IOException {
        Path directory=TestingArchive.root().resolve("scenario-timelines").resolve(TestingArchiveScope.canonicalSource(owner));
        for(var entry:timelines.entrySet()){safeScenarioName(entry.getKey());Path file=directory.resolve(entry.getKey()+".json");if(Files.exists(file)&&!parse(Files.readAllBytes(file)).equals(entry.getValue()))throw new IOException("Конфликт временной шкалы независимого острова");}
        for(var entry:timelines.entrySet())TestingArchive.atomicWrite(directory.resolve(entry.getKey()+".json"),JSON.toJson(entry.getValue()));
    }
    private static void copyChecked(Path source,OutputStream output)throws IOException {
        checkCancelled();
        try(var input=Files.newInputStream(source)){
            byte[] buffer=new byte[65536];int count;
            while((count=input.read(buffer))!=-1){checkCancelled();output.write(buffer,0,count);}
        }
    }
    private static void checkCancelled()throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Подготовка зависимостей отменена");
    }
    private static Path exchange(String filename)throws IOException {
        if(filename==null||!filename.matches("[a-zA-Z0-9_-]{1,128}\\.dwisland"))throw new IOException("Укажите имя .dwisland из archive/exports");
        Path path=TestingArchive.root().resolve("exports").resolve(filename);if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>WORLD_LIMIT)throw new IOException("Файл острова отсутствует или слишком велик");return path;
    }
    private static JsonObject marker(String id,String owner){JsonObject result=new JsonObject();result.addProperty("schema",1);result.addProperty("root",id);result.addProperty("owner",owner);return result;}
    private static void owner(String value)throws IOException {if(value==null||!value.matches("[0-9a-f]{24}"))throw new IOException("Неверный владелец версий");}
    private static void safeScenarioName(String value)throws IOException {if(value==null||value.isBlank()||value.length()>96||value.contains("/")||value.contains("\\")||value.contains("..")||value.chars().anyMatch(c->c<32))throw new IOException("Недопустимое имя сценария");}
    private static boolean compatible(JsonObject a,JsonObject b)throws IOException {
        a=a.deepCopy();b=b.deepCopy();
        for(String key:List.of("replay","schema","measurements")){a.remove(key);b.remove(key);}
        // A live Gson tree contains Float wrappers; parsed files contain decimal wrappers.
        // Compare their exact serialized definitions, so 0.005f equals its persisted 0.005 without filling defaults or changing script content.
        return parse(bytes(a)).equals(parse(bytes(b)));
    }
    private static boolean sameContent(TestingArchive.Version a,TestingArchive.Version b){return a.id().equals(b.id())&&a.schema()==b.schema()&&a.minecraft().equals(b.minecraft())&&a.source().equals(b.source())&&a.bytes()==b.bytes()&&a.files().equals(b.files());}
    private static long sum(Collection<byte[]> values){long n=0;for(byte[] bytes:values)n+=bytes.length;return n;}
    private static byte[] bytes(JsonElement value){return JSON.toJson(canonical(value)).getBytes(StandardCharsets.UTF_8);}
    private static JsonElement canonical(JsonElement value){if(value.isJsonObject()){JsonObject result=new JsonObject();new TreeSet<>(value.getAsJsonObject().keySet()).forEach(key->result.add(key,canonical(value.getAsJsonObject().get(key))));return result;}if(value.isJsonArray()){JsonArray result=new JsonArray();value.getAsJsonArray().forEach(item->result.add(canonical(item)));return result;}return value.deepCopy();}
    private static String string(JsonObject value,String key)throws IOException {if(!value.has(key)||!value.get(key).isJsonPrimitive()||!value.get(key).getAsJsonPrimitive().isString())throw new IOException("Отсутствует строка "+key);return value.get(key).getAsString();}
    private static int integer(JsonObject value,String key)throws IOException {try{if(!value.has(key)||!value.get(key).isJsonPrimitive()||!value.get(key).getAsJsonPrimitive().isNumber())throw new IOException("Отсутствует число "+key);return value.get(key).getAsBigDecimal().intValueExact();}catch(ArithmeticException error){throw new IOException("Неверное целое "+key,error);}}
    private static byte[] readBounded(Path path,long limit)throws IOException {if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>limit)throw new IOException("Файл превышает предел");try(var input=Files.newInputStream(path)){return readBounded(input,limit);}}
    private static byte[] readBounded(InputStream input,long limit)throws IOException {var output=new ByteArrayOutputStream();byte[] buffer=new byte[65536];int n;long size=0;while((n=input.read(buffer))!=-1){checkCancelled();if((size+=n)>limit)throw new IOException("Данные превышают предел");output.write(buffer,0,n);}return output.toByteArray();}
    private static JsonObject parse(byte[] bytes)throws IOException {
        try{var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            var reader=new JsonReader(new StringReader(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()));reader.setStrictness(Strictness.STRICT);
            JsonElement value=readJson(reader,0,new int[]{0});if(reader.peek()!=JsonToken.END_DOCUMENT||!value.isJsonObject())throw new IOException("Ожидается один JSON объект");return value.getAsJsonObject();
        }catch(RuntimeException error){throw new IOException("Повреждён JSON зависимости",error);}
    }
    private static JsonElement readJson(JsonReader reader,int depth,int[] count)throws IOException {
        if(depth>128||++count[0]>1000000)throw new IOException("Слишком сложный JSON");
        return switch(reader.peek()){
            case BEGIN_OBJECT->{reader.beginObject();JsonObject result=new JsonObject();while(reader.hasNext()){String key=reader.nextName();if(result.has(key))throw new IOException("Повторное поле JSON");result.add(key,readJson(reader,depth+1,count));}reader.endObject();yield result;}
            case BEGIN_ARRAY->{reader.beginArray();JsonArray result=new JsonArray();while(reader.hasNext())result.add(readJson(reader,depth+1,count));reader.endArray();yield result;}
            case STRING->new JsonPrimitive(reader.nextString());case NUMBER->new JsonPrimitive(new BigDecimal(reader.nextString()));case BOOLEAN->new JsonPrimitive(reader.nextBoolean());case NULL->{reader.nextNull();yield JsonNull.INSTANCE;}default->throw new IOException("Неверный JSON токен");
        };
    }
}
