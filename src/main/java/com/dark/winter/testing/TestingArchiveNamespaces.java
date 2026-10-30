// Resolves explicit archive namespaces and private restoration workspaces.
package com.dark.winter.testing;

import com.dark.winter.online.authority.*;
import com.dark.winter.online.identity.ProfileFiles;
import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit local archive namespace. This is file access context, never native-world/hosting authority.
 * Installation copies a verified restored archive into its own mutable workspace. Global catalogs are untouched.
 */
public final class TestingArchiveNamespaces {
    private static final int REGISTRY_LIMIT=512*1024,MAX_NAMESPACES=64,MAX_FILES=8192;
    private static final long MAX_BYTES=2L*1024*1024*1024;
    private static final Gson JSON=new Gson();
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    private static final Set<String> ENTRY_FIELDS=Set.of("world","authorityId","islandId","ownerId","workspace","bindingSha256","contentRoot","aliases");
    @FunctionalInterface public interface IO<T>{T run()throws IOException;}
    public record Paths(Path archive,Path prefabs,Path legacyPrefab){}
    private TestingArchiveNamespaces(){}
    public static final class Binding implements AutoCloseable {
        private final Path world,workspace;private final String authority,baseKey;
        private final Map<String,String> aliases=new ConcurrentHashMap<>();
        private final Set<Path> worlds=ConcurrentHashMap.newKeySet();
        private final FileChannel channel;private final FileLock lock;private final AtomicBoolean active=new AtomicBoolean(true);
        private final TestingArchiveScope.Access access=new TestingArchiveScope.Access(){public Path archive(){return paths().archive();}public Path prefabs(){return paths().prefabs();}public Path legacy(){return paths().legacyPrefab();}public String authority(){return authority;}public Map<String,String> aliases(){return Map.copyOf(aliases);}public void check()throws IOException{Binding.this.check();}public boolean owns(Path world){return worlds.contains(world);}public String canonical(String source){return aliases.getOrDefault(source,source);}};
        private Binding(Path world,Path workspace,String authority,Map<String,String> aliases)throws IOException{
            this.world=world;this.workspace=workspace;this.authority=authority;baseKey=TestingArchive.worldKey(world);
            this.aliases.putAll(aliases);this.aliases.put(baseKey,baseKey);worlds.add(world);
            Path marker=workspace.resolve(".namespace.lock");safe(marker);
            channel=FileChannel.open(marker,StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock acquired=null;
            try{ProfileFiles.protect(marker,false);acquired=channel.tryLock();if(acquired==null)throw new IOException("Archive namespace is already in use");}
            catch(IOException|RuntimeException failure){channel.close();if(failure instanceof IOException io)throw io;throw new IOException("Archive namespace is already in use",failure);}
            lock=acquired;
            try{TestingArchiveScope.guard(world,access);}catch(IOException failure){active.set(false);try{lock.release();}finally{channel.close();}throw failure;}
        }
        public String authorityId(){return authority;}
        public Path world(){return world;}
        public Paths paths(){return new Paths(workspace.resolve("archive"),workspace.resolve("prefabs"),workspace.resolve("latest-blocks.json"));}
        public Token token(Path requested)throws IOException{check();Path exact=requested.toAbsolutePath().normalize();if(!worlds.contains(exact))throw new IOException("World has no explicit archive namespace binding");return new Token(this,exact);}
        /** The caller must have staged/validated this actual marked copy through its trusted world lifecycle first. */
        public void bindDerived(Path derived,WorldAuthority.Certificate certificate,LeaseFence.Fence fence)throws IOException{
            check();if(!authority.equals(certificate.authorityId()))throw new IOException("Derived world belongs to another authority");
            TestingArchiveSourceBinding.capture(world,derived,derived,certificate,fence).current();
            Path exact=TestingArchive.checkedSave(derived,true);if(worlds.size()>=32&&!worlds.contains(exact))throw new IOException("Too many derived namespace worlds");
            TestingArchiveScope.guard(exact,access);aliases.put(TestingArchive.worldKey(exact),baseKey);worlds.add(exact);check();
        }
        public Map<String,String> sourceAliases(){return Map.copyOf(aliases);}
        private void check()throws IOException{if(!active.get())throw new IOException("Archive namespace context was detached");}
        @Override public void close()throws IOException{if(active.compareAndSet(true,false)){try{lock.release();}finally{channel.close();}}}
    }
    /** May cross an executor boundary explicitly; revocation of its Binding invalidates every captured token. */
    public static final class Token {
        private final Binding binding;private final Path world;
        private Token(Binding binding,Path world){this.binding=binding;this.world=world;}
        public Path world(){return world;}public String authorityId(){return binding.authority;}
        public Scope enter()throws IOException{binding.check();if(!binding.worlds.contains(world))throw new IOException("Archive world binding was revoked");Scope scope=new Scope(this,CURRENT.get(),TestingArchiveScope.enter(binding.access));CURRENT.set(scope);return scope;}
        public <T>T call(IO<T> operation)throws IOException{try(Scope ignored=enter()){T value=operation.run();binding.check();return value;}}
        public Runnable wrap(Runnable operation){return ()->{try(Scope ignored=enter()){operation.run();binding.check();}catch(IOException failure){throw new UncheckedIOException(failure);}};}
    }
    public static final class Scope implements AutoCloseable {
        private final Token token;private final Scope previous;private final TestingArchiveScope.Scope core;private final Thread thread=Thread.currentThread();private boolean closed;
        private Scope(Token token,Scope previous,TestingArchiveScope.Scope core){this.token=token;this.previous=previous;this.core=core;}
        @Override public void close(){if(closed)return;if(Thread.currentThread()!=thread||CURRENT.get()!=this)throw new IllegalStateException("Archive scopes must unwind on their owner thread");core.close();closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    public static Optional<Paths> currentPaths(){return TestingArchiveScope.root().map(root->new Paths(root,TestingArchiveScope.prefabs().orElseThrow(),TestingArchiveScope.legacy().orElseThrow()));}
    public static Optional<Token> capture(){Scope scope=CURRENT.get();if(scope==null)return Optional.empty();currentPaths();return Optional.of(scope.token);}
    public static void checkCurrent()throws IOException{TestingArchiveScope.checkCurrent();}
    public static void requireWorld(Path requested)throws IOException{TestingArchiveScope.requireWorld(requested);}
    /** Alias only typed world-source keys in the explicit context. */
    public static String canonicalSource(String source){return TestingArchiveScope.canonicalSource(source);}
    public static boolean sameSource(String observed,String expected){return TestingArchiveScope.sameSource(observed,expected);}

    public static Binding install(Path protectedProfileRoot,TestingReplicationArchive.Restored restored,Path actualMarkedWorld,
            WorldAuthority.Certificate certificate,LeaseFence.Fence freshFence)throws IOException{
        Objects.requireNonNull(restored);requireAuthority(actualMarkedWorld,certificate,freshFence);
        if(!certificate.authorityId().equals(restored.authorityId())||!freshFence.checkpointSha256().equals(restored.contentRoot())||
            !TestingArchive.worldKey(actualMarkedWorld).equals(restored.newSourceKey()))throw new IOException("Restored archive is not the exact node-confirmed target");
        Path source=restored.directory().toAbsolutePath().normalize();safe(source);ProfileFiles.requireOutsideWorld(source);
        Map<String,String> expected=restored.verifiedFiles();if(expected.isEmpty()||expected.size()>MAX_FILES||!restored.bindingSha256().equals(expected.get("binding.json")))throw new IOException("Missing verified restore provenance");
        Map<String,Path> files=inventory(source,expected);Path profile=profile(protectedProfileRoot),directory=profile.resolve("replica-archives"),registry=directory.resolve("registry.json");
        Files.createDirectories(directory);ProfileFiles.protect(directory,true);
        synchronized(TestingArchiveNamespaces.class){
            try(FileChannel channel=lock(directory);FileLock ignored=acquire(channel)){
                JsonObject document=registry(registry);String before=digest(document),key=TestingArchive.worldKey(actualMarkedWorld);
                JsonObject entries=document.getAsJsonObject("entries");if(entries.has(key))throw new IOException("This world already has an archive namespace; use explicit reopen");
                if(entries.size()>=MAX_NAMESPACES)throw new IOException("Archive namespace registry limit");
                Path namespaces=directory.resolve("namespaces");safe(namespaces);Files.createDirectories(namespaces);ProfileFiles.protect(namespaces,true);
                Path workspace=namespaces.resolve(UUID.randomUUID().toString()),stage=namespaces.resolve(".install-"+UUID.randomUUID());Binding binding=null;boolean registered=false;
                Files.createDirectory(stage);ProfileFiles.protect(stage,true);
                try{
                    long bytes=0;
                    for(var row:files.entrySet()){
                        checkInterrupted();long size=Files.size(row.getValue());if(size>512L*1024*1024||bytes>MAX_BYTES-size)throw new IOException("Restored archive namespace quota");bytes+=size;
                        String relative=row.getKey();Path output=relative.startsWith("prefabs/")||relative.equals("latest-blocks.json")?stage.resolve(relative):stage.resolve("archive").resolve(relative);
                        copy(row.getValue(),output,expected.get(relative));
                    }
                    requireAuthority(actualMarkedWorld,certificate,freshFence);checkInterrupted();
                    if(Files.exists(workspace,LinkOption.NOFOLLOW_LINKS))throw new IOException("Namespace destination collision");Files.move(stage,workspace,StandardCopyOption.ATOMIC_MOVE);
                    binding=new Binding(actualMarkedWorld.toAbsolutePath().normalize(),workspace,certificate.authorityId(),restored.sourceAliases());
                    JsonObject entry=new JsonObject();entry.addProperty("world",actualMarkedWorld.toAbsolutePath().normalize().toString());
                    entry.addProperty("authorityId",certificate.authorityId());var genesis=WorldAuthority.verify(certificate);entry.addProperty("islandId",genesis.islandId().toString());entry.addProperty("ownerId",genesis.ownerId());
                    entry.addProperty("workspace",workspace.toString());entry.addProperty("bindingSha256",restored.bindingSha256());entry.addProperty("contentRoot",restored.contentRoot());entry.add("aliases",JSON.toJsonTree(new TreeMap<>(restored.sourceAliases())));
                    entries.add(key,entry);publishRegistry(registry,document,before);registered=true;return binding;
                }finally{
                    if(!registered){if(binding!=null)binding.close();removeOwned(stage,namespaces);removeOwned(workspace,namespaces);}
                }
            }
        }
    }
    /** Reopening requires the same permanent authority/current node fence; its checkpoint may since have advanced. */
    public static Binding open(Path protectedProfileRoot,Path actualMarkedWorld,WorldAuthority.Certificate certificate,LeaseFence.Fence freshFence)throws IOException{
        requireAuthority(actualMarkedWorld,certificate,freshFence);Path profile=profile(protectedProfileRoot),directory=profile.resolve("replica-archives"),file=directory.resolve("registry.json");
        safe(directory);if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("No archive namespace registry");
        synchronized(TestingArchiveNamespaces.class){try(FileChannel channel=lock(directory);FileLock ignored=acquire(channel)){
            JsonObject document=registry(file),entry=document.getAsJsonObject("entries").getAsJsonObject(TestingArchive.worldKey(actualMarkedWorld));
            if(entry==null)throw new IOException("World has no registered archive namespace");validateEntry(entry,directory);
            var genesis=WorldAuthority.verify(certificate);
            if(!entry.get("world").getAsString().equals(actualMarkedWorld.toAbsolutePath().normalize().toString())||!entry.get("authorityId").getAsString().equals(certificate.authorityId())||
                !entry.get("islandId").getAsString().equals(genesis.islandId().toString())||!entry.get("ownerId").getAsString().equals(genesis.ownerId()))throw new IOException("Archive registry differs from the actual permanent world authority");
            Path workspace=Path.of(entry.get("workspace").getAsString());Path binding=workspace.resolve("archive/binding.json");safe(binding);
            if(!entry.get("bindingSha256").getAsString().equals(TestingArchive.hash(binding)))throw new IOException("Archive namespace provenance changed");
            Map<String,String> aliases=aliases(entry.getAsJsonObject("aliases"),TestingArchive.worldKey(actualMarkedWorld));
            requireAuthority(actualMarkedWorld,certificate,freshFence);return new Binding(actualMarkedWorld.toAbsolutePath().normalize(),workspace,certificate.authorityId(),aliases);
        }}
    }
    private static void requireAuthority(Path world,WorldAuthority.Certificate certificate,LeaseFence.Fence fence)throws IOException{
        if(world==null||certificate==null||fence==null)throw new IOException("Archive namespace needs an actual world and verified authority");
        TestingArchiveSourceBinding.capture(world,world,world,certificate,fence).current();
    }
    private static Path profile(Path requested)throws IOException{Path path=requested.toAbsolutePath().normalize();ProfileFiles.requireOutsideWorld(path);Path global=net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("darkwinter-testing").toAbsolutePath().normalize();if(path.startsWith(global))throw new IOException("Archive namespace profile cannot be the global Testing archive");safe(path);Files.createDirectories(path);ProfileFiles.protect(path,true);return path;}
    private static FileChannel lock(Path directory)throws IOException{Path path=directory.resolve(".registry.lock");safe(path);FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE);try{ProfileFiles.protect(path,false);return channel;}catch(IOException|RuntimeException failure){channel.close();throw failure;}}
    private static FileLock acquire(FileChannel channel)throws IOException{try{FileLock lock=channel.tryLock();if(lock==null)throw new IOException("Archive registry is in use");return lock;}catch(OverlappingFileLockException busy){throw new IOException("Archive registry is in use",busy);}}
    private static JsonObject registry(Path file)throws IOException{
        try{safe(file);if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)){JsonObject value=new JsonObject();value.addProperty("schema",1);value.add("entries",new JsonObject());value.addProperty("sha256",digest(value));return value;}
        JsonObject value=ProfileFiles.read(file,REGISTRY_LIMIT);ProfileFiles.fields(value,Set.of("schema","entries","sha256"),Set.of());
        if(ProfileFiles.number(value,"schema")!=1||!value.get("entries").isJsonObject()||value.getAsJsonObject("entries").size()>MAX_NAMESPACES||!ProfileFiles.string(value,"sha256",64).equals(digest(value)))throw new IOException("Invalid archive namespace registry");
        for(var row:value.getAsJsonObject("entries").entrySet()){if(!row.getKey().matches("[0-9a-f]{24}")||!row.getValue().isJsonObject())throw new IOException("Invalid archive registry world key");validateEntry(row.getValue().getAsJsonObject(),file.getParent());if(!TestingArchive.worldKey(Path.of(row.getValue().getAsJsonObject().get("world").getAsString())).equals(row.getKey()))throw new IOException("Registry world key mismatch");}
        return value;}catch(RuntimeException invalid){throw new IOException("Invalid archive namespace registry",invalid);}
    }
    private static void validateEntry(JsonObject entry,Path directory)throws IOException{
        ProfileFiles.fields(entry,ENTRY_FIELDS,Set.of());Path workspace=Path.of(ProfileFiles.string(entry,"workspace",4096)).toAbsolutePath().normalize();
        if(!workspace.getParent().equals(directory.resolve("namespaces"))||!workspace.getFileName().toString().matches("[0-9a-f-]{36}"))throw new IOException("Invalid private archive namespace path");
        safe(workspace);if(!ProfileFiles.string(entry,"ownerId",64).matches("dw1_[A-Za-z0-9_-]{43}"))throw new IOException("Invalid namespace permanent owner");
        String island=ProfileFiles.string(entry,"islandId",36);try{if(!UUID.fromString(island).toString().equals(island))throw new IOException("Invalid namespace island UUID");}catch(IllegalArgumentException invalid){throw new IOException("Invalid namespace island UUID",invalid);}
        if(!ProfileFiles.string(entry,"authorityId",64).matches("dwa1_[A-Za-z0-9_-]{43}"))throw new IOException("Invalid archive authority ID");
        for(String field:List.of("bindingSha256","contentRoot"))if(!ProfileFiles.string(entry,field,64).matches("[0-9a-f]{64}"))throw new IOException("Invalid archive registry hash");
        Path world=Path.of(ProfileFiles.string(entry,"world",4096));if(!world.isAbsolute()||!world.equals(world.toAbsolutePath().normalize()))throw new IOException("Invalid registry world path");
        aliases(entry.getAsJsonObject("aliases"),TestingArchive.worldKey(world));
    }
    private static Map<String,String> aliases(JsonObject json,String target)throws IOException{
        if(json==null||json.isEmpty()||json.size()>64)throw new IOException("Missing/big archive source aliases");Map<String,String> values=new TreeMap<>();
        for(var row:json.entrySet()){String value=row.getValue().getAsString();if(!row.getKey().matches("[0-9a-f]{24}")||!target.equals(value))throw new IOException("Foreign archive source alias");values.put(row.getKey(),value);}return Map.copyOf(values);
    }
    private static String digest(JsonObject value){JsonObject body=value.deepCopy();body.remove("sha256");return TestingArchive.hashBytes(ProfileFiles.bytes(body));}
    private static void publishRegistry(Path file,JsonObject next,String before)throws IOException{checkInterrupted();if(!digest(registry(file)).equals(before))throw new IOException("Archive registry changed during installation");next.addProperty("sha256",digest(next));if(ProfileFiles.bytes(next).length>REGISTRY_LIMIT)throw new IOException("Archive registry quota");ProfileFiles.atomic(file,next,Files.exists(file,LinkOption.NOFOLLOW_LINKS));}
    private static Map<String,Path> inventory(Path root,Map<String,String> expected)throws IOException{
        Map<String,Path> files=new TreeMap<>();try(var paths=Files.walk(root)){List<Path> all=paths.limit(MAX_FILES*3L+1).toList();if(all.size()>MAX_FILES*3)throw new IOException("Restored archive directory quota");for(Path path:all){safe(path);if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))continue;if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid restored archive file");String name=root.relativize(path).toString().replace('\\','/');if(!expected.containsKey(name))throw new IOException("Unexpected file added to verified restore");files.put(name,path);if(files.size()>MAX_FILES)throw new IOException("Archive namespace file quota");}}
        if(!files.keySet().equals(expected.keySet()))throw new IOException("Verified restore files missing");return files;
    }
    private static void copy(Path source,Path target,String expected)throws IOException{
        safe(source);Files.createDirectories(target.getParent());ProfileFiles.protect(target.getParent(),true);MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        try(InputStream input=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS);FileChannel output=FileChannel.open(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            ProfileFiles.protect(target,false);byte[] buffer=new byte[65536];long bytes=0;for(int n;(n=input.read(buffer))!=-1;){checkInterrupted();if((bytes+=n)>512L*1024*1024)throw new IOException("Namespace file grew");digest.update(buffer,0,n);ByteBuffer data=ByteBuffer.wrap(buffer,0,n);while(data.hasRemaining())output.write(data);}output.force(true);
        }
        if(!expected.equals(HexFormat.of().formatHex(digest.digest())))throw new IOException("Restored archive bytes changed after verification");
    }
    private static void safe(Path path)throws IOException{for(Path parent=path.toAbsolutePath().normalize();parent!=null;parent=parent.getParent())if(Files.isSymbolicLink(parent)||Files.exists(parent,LinkOption.NOFOLLOW_LINKS)&&!parent.toRealPath().equals(parent))throw new IOException("Linked archive namespace path");}
    private static void checkInterrupted()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Archive namespace operation cancelled");}
    private static void removeOwned(Path path,Path parent)throws IOException{Path target=path.toAbsolutePath().normalize();if(!target.getParent().equals(parent.toAbsolutePath().normalize())||!target.getFileName().toString().matches("(\\.install-)?[0-9a-f-]{36}"))throw new IOException("Unsafe private namespace cleanup");if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))return;safe(target);try(var paths=Files.walk(target)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
}
