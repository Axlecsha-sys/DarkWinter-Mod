// Captures and restores immutable content-addressed archives of stopped saves.
package com.dark.winter.testing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Immutable, content-addressed save archives. Capture/restore MUST be called only
 * after the integrated server has stopped. Never copies an actively writing save.
 * Blob reuse is file-level; manifests have no mutable references to other versions.
 */
public final class TestingArchive {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long MAX_WORLD_BYTES = 4L * 1024 * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    private static final int MAX_FILES = 100000;
    private static final long MANIFEST_LIMIT = 32L * 1024 * 1024;
    public record Entry(String path, String sha256, long size) {}
    public record Version(int schema, String minecraft, String id, String name, String source,
                          String branch, String parent, String created, boolean protectedVersion,
                          long bytes, List<Entry> files, String description, String category, String thumbnail) {
        public Version {
            description=description==null?"":description;
            category=category==null||category.isBlank()?"general":category;
            thumbnail=thumbnail==null?"":thumbnail;
        }
        public Version(int schema,String minecraft,String id,String name,String source,String branch,String parent,String created,
                       boolean protectedVersion,long bytes,List<Entry> files) {
            this(schema,minecraft,id,name,source,branch,parent,created,protectedVersion,bytes,files,"","general","");
        }
    }
    public static List<String> libraryCategories(){return List.of("general","terrain","survival","construction","scenarios","showcase");}
    private TestingArchive() {}

    public static Path root() {
        return TestingArchiveScope.root().orElseGet(()->FabricLoader.getInstance().getGameDir().resolve("darkwinter-testing/archive").toAbsolutePath().normalize());
    }
    public static String safeName(String value) {
        String name = value == null ? "" : value.strip();
        if (name.isEmpty()) return "Версия " + Instant.now().toString().replace(':', '-');
        if (name.length() > 96 || name.contains("/") || name.contains("\\") || name.contains("..")
                || name.chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException("Недопустимое имя");
        return name;
    }
    public static String worldKey(Path world) {
        return hashBytes(world.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    }
    static Path manifestPath(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f-]{36}")) throw new IOException("Недопустимый идентификатор версии");
        return root().resolve("versions").resolve(id + ".json");
    }
    static Path blobPath(String hash) throws IOException {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IOException("Недопустимый SHA-256");
        return root().resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
    }
    public static synchronized Version capture(Path source,String name,String branch,String parent)throws IOException {
        return capture(source,name,branch,parent,null,null);
    }
    public static synchronized Version capture(Path source,String name,String branch,String parent,String description,String category)throws IOException {
        TestingArchiveScope.requireWorld(source);
        source = checkedSave(source, true);
        Version previous=null;
        if(parent!=null&&!parent.isBlank())try{previous=read(parent);}catch(IOException absentParent){/* An optional history label cannot prevent a fresh safety capture. */}
        description=description==null?(previous==null?"":previous.description()):description;
        category=category==null?(previous==null?"general":previous.category()):category;
        metadata(description,category,"");
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        List<Path> paths;
        Path capturedSource = source;
        try (var stream = Files.walk(source)) { paths = stream.filter(p -> !p.equals(capturedSource)).sorted().toList(); }
        if (paths.size() > MAX_FILES) throw new IOException("Слишком много файлов в мире");
        for (Path file : paths) {
            if (Files.isSymbolicLink(file)) throw new IOException("Ссылки в сохранении не поддерживаются");
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Недопустимый файл");
            String relative = source.relativize(file).toString().replace('\\', '/');
            if (relative.equals("session.lock")) continue;
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES || (total += size) > MAX_WORLD_BYTES) throw new IOException("Архив превышает лимит 4 ГиБ");
            String hash = hash(file);
            Path blob = blobPath(hash);
            Files.createDirectories(blob.getParent());
            if (!Files.exists(blob)) {
                requireSpace(size);
                Path temp = Files.createTempFile(blob.getParent(), "blob-", ".tmp");
                try {
                    Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING);
                    if (!hash.equals(hash(temp))) throw new IOException("Файл изменился во время снимка");
                    atomicMove(temp, blob);
                } finally { Files.deleteIfExists(temp); }
            } else if (Files.size(blob) != size || !hash.equals(hash(blob))) {
                throw new IOException("Повреждён общий объект архива: " + hash);
            }
            entries.add(new Entry(relative, hash, size));
        }
        if (entries.stream().noneMatch(e -> e.path().equals("level.dat"))) throw new IOException("В снимке нет level.dat");
        Version version = new Version(2, "26.3", UUID.randomUUID().toString(), safeName(name), worldKey(source),
                safeName(branch == null ? "main" : branch), parent == null ? "" : parent,
                Instant.now().toString(), false, total, List.copyOf(entries),description,category,capturedThumbnail(entries));
        writeVersion(version);
        return version;
    }
    public static Version read(String id) throws IOException {
        manifestPath(id);if(TestingScenarioDependencies.deleted(id))throw new IOException("Версия удалена");
        Path file=manifestPath(id);
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
            Version version=TestingScenarioDependencies.bundled(id);if(version==null)throw new IOException("Версия отсутствует");
            validate(version);thumbnail(version);return version;
        }
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MANIFEST_LIMIT)throw new IOException("Версия отсутствует или слишком велика");
        try{
            Version version=JSON.fromJson(Files.readString(file),Version.class);validate(version);
            if(!version.id().equals(id))throw new IOException("Идентификатор манифеста не совпадает");
            Version bundled=TestingScenarioDependencies.bundled(id);
            if(bundled!=null&&(!bundled.source().equals(version.source())||!bundled.files().equals(version.files())||bundled.bytes()!=version.bytes()))
                throw new IOException("Метаданные перекрывают другое содержимое версии");
            thumbnail(version);return version;
        }catch(RuntimeException error){throw new IOException("Повреждён манифест версии",error);}
    }
    static void validate(Version version) throws IOException {
        if (version == null || version.schema() != 2 || !"26.3".equals(version.minecraft())
                || version.files() == null || version.files().isEmpty() || version.files().size() > MAX_FILES)
            throw new IOException("Неподдерживаемый формат острова");
        manifestPath(version.id());
        safeName(version.name());metadata(version.description(),version.category(),version.thumbnail());
        if(!version.thumbnail().isEmpty()&&version.files().stream().noneMatch(e->e!=null&&"icon.png".equals(e.path())&&e.size()>0&&e.size()<=262144))
            throw new IOException("Миниатюра не ссылается на допустимый icon.png снимка");
        long total = 0;
        Set<String> unique = new HashSet<>();
        for (Entry entry : version.files()) {
            if (entry == null || entry.path() == null || entry.path().isBlank() || entry.path().contains("\\")
                    || entry.path().startsWith("/") || entry.path().contains(":") || entry.path().contains("..")
                    || entry.path().equals("session.lock") || !unique.add(entry.path())
                    || entry.size() < 0 || entry.size() > MAX_FILE_BYTES || (total += entry.size()) > MAX_WORLD_BYTES)
                throw new IOException("Недопустимый путь или размер в архиве");
            blobPath(entry.sha256());
        }
        if (total != version.bytes() || !unique.contains("level.dat")
                || !unique.contains("data/darkwinter-testing.properties")) throw new IOException("Архив не является тестовым миром");
    }
    public static synchronized Version adopt(Version source,Path copy,String branch)throws IOException {
        return adopt(source,copy,branch,source.name());
    }
    public static synchronized Version adopt(Version source, Path copy, String branch,String name) throws IOException {
        validate(source); checkedSave(copy, true);
        Version fork = new Version(2, "26.3", UUID.randomUUID().toString(), safeName(name), worldKey(copy),
                safeName(branch), source.id(), Instant.now().toString(), false, source.bytes(), source.files(),source.description(),source.category(),source.thumbnail());
        writeVersion(fork); return fork;
    }
    /** All manifests are validated before any unreferenced immutable object is removed. */
    public static synchronized long collectUnused(boolean confirmed) throws IOException {
        if (!confirmed) throw new IOException("Очистка требует подтверждения");
        Set<String> referenced = new HashSet<>();
        for (Version version : versions(null)) for (Entry entry : version.files()) referenced.add(entry.sha256());
        Path directory = root().resolve("objects");
        if (!Files.isDirectory(directory)) return 0;
        List<Path> garbage = new ArrayList<>();
        try (var stream = Files.walk(directory)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Ссылка в хранилище архива");
                String name = path.getFileName().toString();
                if (name.matches("[0-9a-f]{64}") && path.equals(blobPath(name)) && !referenced.contains(name)) garbage.add(path);
            }
        }
        long freed = 0;
        for (Path path : garbage) { freed += Files.size(path); Files.delete(path); }
        return freed;
    }
    /** Actual file differences between two completed snapshots; never samples a live save. */
    public static List<String> differences(Version before, Version after) throws IOException {
        validate(before); validate(after);
        java.util.Map<String,Entry> left = new java.util.TreeMap<>(), right = new java.util.TreeMap<>();
        before.files().forEach(e -> left.put(e.path(), e)); after.files().forEach(e -> right.put(e.path(), e));
        var names = new java.util.TreeSet<String>(); names.addAll(left.keySet()); names.addAll(right.keySet());
        List<String> result = new ArrayList<>();
        for (String name : names) {
            Entry old = left.get(name), next = right.get(name);
            if (old == null) result.add("добавлен: " + name + " (" + next.size() + " байт)");
            else if (next == null) result.add("удалён: " + name + " (" + old.size() + " байт)");
            else if (!old.sha256().equals(next.sha256())) result.add("изменён: " + name + " (" + old.size() + " → " + next.size() + " байт)");
        }
        return List.copyOf(result);
    }
    public static List<Version> versions(Path world) throws IOException {
        TestingArchiveScope.requireWorld(world);
        java.util.Map<String,Version> values=new java.util.TreeMap<>(TestingScenarioDependencies.catalog());
        Path dir=root().resolve("versions");
        if(Files.isDirectory(dir))try(var stream=Files.list(dir)){
            List<Path> files=stream.filter(path->path.getFileName().toString().endsWith(".json")).limit(MAX_FILES+1L).toList();
            if(files.size()>MAX_FILES)throw new IOException("Слишком много манифестов архива");
            for(Path file:files){String id=file.getFileName().toString().replace(".json","");if(!TestingScenarioDependencies.deleted(id))values.put(id,read(id));}
        }
        String key=world==null?null:worldKey(world);List<Version> result=new ArrayList<>();
        for(Version version:values.values())if(key==null||TestingArchiveScope.sameSource(key,version.source()))result.add(version);
        result.sort(Comparator.comparing(Version::created).reversed());return List.copyOf(result);
    }
    /** Verify every object BEFORE creating anything in the destination. */
    public static void materialize(Version version, Path destination, boolean cleanSystems) throws IOException {
        TestingArchiveScope.requireWorld(destination);
        validate(version);
        destination = checkedSave(destination, false);
        if (Files.exists(destination)) throw new IOException("Копия уже существует; выберите новое имя");
        for (Entry entry : version.files()) verifyBlob(entry);
        requireSpace(version.bytes());
        Path staging = destination.resolveSibling(".dw-stage-" + UUID.randomUUID());
        Files.createDirectory(staging);
        for (Entry entry : version.files()) {
            Path file = staging.resolve(entry.path()).normalize();
            if (!file.startsWith(staging)) throw new IOException("Путь вышел за пределы копии");
            Files.createDirectories(file.getParent());
            Files.copy(blobPath(entry.sha256()), file);
        }
        if (cleanSystems) {
            // Only laboratory settings are reset; scenery, weather history and scenarios are preserved.
            Path settings = staging.resolve("data/darkwinter-testing/settings.json");
            if (Files.exists(settings)) {
                Path preserved=staging.resolve("data/darkwinter-testing/imported-settings.json");
                if(Files.exists(preserved))preserved=staging.resolve("data/darkwinter-testing/imported-settings-"+UUID.randomUUID()+".json");
                Files.move(settings,preserved);
            }
        }
        Files.move(staging, destination);
    }
    /** Original is moved to a recoverable sibling only after the staged replacement is complete. */
    public static Path restore(Version version, Path destination) throws IOException {
        TestingArchiveScope.requireWorld(destination);
        destination = checkedSave(destination, true);
        Path stage = destination.resolveSibling(".dw-restore-" + UUID.randomUUID());
        materialize(version, stage, false);
        Path recovery = destination.resolveSibling(".dw-recovery-" + UUID.randomUUID());
        Files.move(destination, recovery);
        try { Files.move(stage, destination); }
        catch (IOException error) { Files.move(recovery, destination); throw error; }
        return recovery;
    }
    public static void protect(String id, boolean protect) throws IOException {
        Version v = read(id);
        writeVersion(new Version(v.schema(), v.minecraft(), v.id(), v.name(), v.source(), v.branch(), v.parent(),
                v.created(), protect, v.bytes(), v.files(),v.description(),v.category(),v.thumbnail()));
    }
    /** Shared blobs remain available to every other version. Garbage collection is explicit. */
    public static void delete(String id, boolean confirmed) throws IOException {
        if(!confirmed)throw new IOException("Удаление требует подтверждения");
        if(read(id).protectedVersion())throw new IOException("Версия защищена");
        TestingScenarioDependencies.deleteVersion(id);
    }
    public static long storedBytes() throws IOException {
        Path objects = root().resolve("objects");
        if (!Files.exists(objects)) return 0;
        try (var stream = Files.walk(objects)) {
            long total = 0;
            for (Path file : stream.filter(Files::isRegularFile).toList()) total += Files.size(file);
            return total;
        }
    }
    public static Path exportVersion(String id) throws IOException {return TestingScenarioDependencies.exportVersion(id);}
    /**
     * Read-only library index: validates manifest and the bounded ZIP directory,
     * without publishing a version or decompressing world payloads. Import still
     * verifies every payload SHA-256 before applying/opening anything.
     */
    /** Compare a stopped author save with its immutable baseline without opening any Minecraft chunk. */
    public static List<String> differencesFromSave(Version expected,Path world)throws IOException {
        TestingArchiveScope.requireWorld(world);
        validate(expected);world=checkedSave(world,true);List<String> result=new ArrayList<>();
        var files=new java.util.HashMap<String,Entry>();for(Entry entry:expected.files())files.put(entry.path(),entry);
        Path rootWorld=world;List<Path> current;
        try(var walk=Files.walk(world)){current=walk.limit(MAX_FILES+1L).toList();}
        if(current.size()>MAX_FILES)throw new IOException("Слишком много файлов для сверки авторского мира");
        var seen=new HashSet<String>();
        for(Path file:current) {
            if(Files.isSymbolicLink(file))throw new IOException("Ссылка в авторском мире");
            if(Files.isDirectory(file,LinkOption.NOFOLLOW_LINKS))continue;
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Недопустимый файл авторского мира");
            String relative=rootWorld.relativize(file).toString().replace('\\','/');
            if(relative.equals("session.lock"))continue;
            seen.add(relative);Entry baseline=files.get(relative);
            if(baseline==null)result.add("Добавлен файл: "+relative);
            else if(Files.size(file)!=baseline.size()||!hash(file).equals(baseline.sha256()))result.add("Изменён файл: "+relative);
        }
        for(String path:files.keySet())if(!seen.contains(path))result.add("Удалён файл: "+path);
        result.sort(String::compareTo);return List.copyOf(result);
    }
    public static Version readExportMetadata(String filename)throws IOException {return TestingScenarioDependencies.readExportMetadata(filename);}

    /** Import is restricted to our exchange directory and validates all bytes before publication. */
    public static synchronized Version importVersion(String filename)throws IOException {return TestingScenarioDependencies.importVersion(filename);}
    private static void metadata(String description,String category,String thumbnail)throws IOException {
        if(description==null||description.length()>1024||description.chars().anyMatch(c->c<32&&c!='\n'&&c!='\t')||description.indexOf(127)>=0)
            throw new IOException("Описание: до 1024 символов без управляющих кодов");
        if(!libraryCategories().contains(category))throw new IOException("Неизвестная категория библиотеки");
        if(!"".equals(thumbnail)&&!"icon.png".equals(thumbnail))throw new IOException("Миниатюра разрешена только из native icon.png снимка");
    }
    private static Entry thumbnailEntry(Version version)throws IOException {
        return version.files().stream().filter(e->e.path().equals("icon.png")).findFirst().orElseThrow(()->new IOException("В снимке нет icon.png"));
    }
    private static String capturedThumbnail(List<Entry> files)throws IOException {
        for(Entry entry:files)if(entry.path().equals("icon.png")&&entry.size()>0&&entry.size()<=262144) {
            try{TestingLibraryMetadata.png(blobPath(entry.sha256()));return "icon.png";}
            catch(IOException invalidIcon){return "";} // A corrupt native icon must never prevent an otherwise exact safety backup.
        }return "";
    }
    /** Only bytes from the SHA-256 verified save icon are exposed. External paths and URLs are forbidden. */
    public static byte[] thumbnail(Version version)throws IOException {
        validate(version);if(version.thumbnail().isEmpty())return new byte[0];
        Entry entry=thumbnailEntry(version);verifyBlob(entry);return TestingLibraryMetadata.png(blobPath(entry.sha256()));
    }
    public static Path verifiedBlob(Entry entry)throws IOException {verifyBlob(entry);return blobPath(entry.sha256());}
    public static synchronized Version copyIndependent(Version version,Path destination,boolean cleanSystems,String branch,String name)throws IOException{
        if(TestingArchiveScope.root().isPresent())throw new IOException("Независимая копия восстановленного острова требует отдельного целевого архива; исходный архив не изменён");
        return TestingScenarioDependencies.copyIndependent(version,destination,cleanSystems,branch,name);
    }
    public static synchronized Version updateMetadata(String id,String description,String category)throws IOException {
        Version version=read(id);metadata(description,category,version.thumbnail());
        Version updated=new Version(version.schema(),version.minecraft(),version.id(),version.name(),version.source(),version.branch(),version.parent(),
                version.created(),version.protectedVersion(),version.bytes(),version.files(),description,category,version.thumbnail());
        writeVersion(updated);return updated;
    }
    public static Path checkedSave(Path path, boolean existing) throws IOException {
        Path saves = FabricLoader.getInstance().getGameDir().resolve("saves").toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(saves) || Files.isSymbolicLink(normalized))
            throw new IOException("Операция разрешена только с отдельным миром внутри saves");
        if (existing && (!Files.isRegularFile(normalized.resolve("level.dat"))
                || !Files.isRegularFile(normalized.resolve("data/darkwinter-testing.properties"))))
            throw new IOException("Мир не помечен как тестовый");
        return normalized;
    }
    static void verifyBlob(Entry entry) throws IOException {
        Path file = blobPath(entry.sha256());
        if (!Files.isRegularFile(file) || Files.size(file) != entry.size() || !entry.sha256().equals(hash(file)))
            throw new IOException("Объект архива повреждён: " + entry.path());
    }
    static void requireSpace(long needed) throws IOException {
        Files.createDirectories(root());
        if (Files.getFileStore(root()).getUsableSpace() < needed + 256L * 1024 * 1024)
            throw new IOException("Недостаточно свободного места для безопасного снимка");
    }
    static void writeVersion(Version version) throws IOException {
        validate(version);thumbnail(version);
        atomicWrite(manifestPath(version.id()), JSON.toJson(version));
    }
    public static void atomicWrite(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "dw-", ".tmp");
        try { Files.writeString(temp, text, StandardCharsets.UTF_8); atomicMove(temp, path); }
        finally { Files.deleteIfExists(temp); }
    }
    static void atomicMove(Path from, Path to) throws IOException {
        TestingArchiveScope.checkCurrent();
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    static String hashBytes(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String hash(Path file) throws IOException {
        if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Проверка SHA-256 отменена");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Проверка SHA-256 отменена");
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
