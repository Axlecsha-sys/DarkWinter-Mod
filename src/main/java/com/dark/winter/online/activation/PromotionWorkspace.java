// Prepares a private native world copy for an attested host promotion.
package com.dark.winter.online.activation;

import com.dark.winter.online.authority.*;
import com.dark.winter.online.identity.*;
import com.dark.winter.online.replica.*;
import com.dark.winter.online.world.*;
import com.dark.winter.testing.TestingArchiveNamespaces;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;

/** A new, private native copy only. No existing world, original, READY/cache blob or actor payload is modified. */
public final class PromotionWorkspace {
    private PromotionWorkspace(){}
    private static final int LEVEL_LIMIT=8*1024*1024;
    private static final Set<Path> REQUIRES_SCOPE=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static boolean requiresScopedOpen(Path world){return world!=null&&REQUIRES_SCOPE.contains(world.toAbsolutePath().normalize());}
    public static final class Ticket implements AutoCloseable {
        private final Path world,saves;private final IdentityStore identity;private final String actor;
        private final OnlineHostPermit permit;private final NativeActorAliases aliases;
        private final TestingArchiveNamespaces.Token reopenContext;
        private final Path pinFile;private final String pinHash;
        private final Map<String,ReplicaProtocol.Entry> expected;private final Map<String,String> metadata=new HashMap<>();
        private byte[] oldLevel;private CompoundTag preparedLevel;private String modifiedLevel;private UUID account;private long deadline,armedAtMillis;
        private boolean prepared,armed,consumed,adopted,closed;
        private Ticket(Path world,Path saves,IdentityStore identity,OnlineHostPermit permit,NativeActorAliases aliases,Map<String,ReplicaProtocol.Entry> expected,PinWitness pin,TestingArchiveNamespaces.Token reopenContext){
            this.world=world;this.saves=saves;this.identity=identity;actor=identity.profile().id();this.permit=permit;this.aliases=aliases;this.expected=Map.copyOf(expected);pinFile=pin.file();pinHash=pin.sha256();this.reopenContext=reopenContext;
        }
        public Path world(){return world;}public Path saves(){return saves;}public String actor(){return actor;}
        public UUID nativePlayer(){return aliases.localNativePlayer();}public NativeActorAliases aliases(){return aliases;}
        public OnlineHostPermit permit(){return permit;}public IdentityStore identity(){return identity;}
        public synchronized boolean consumed(){return consumed;}public synchronized boolean adopted(){return adopted;}
        private void current()throws IOException{
            if(closed||!identity.profile().id().equals(actor))throw new IOException("Promotion ticket closed or actual profile changed");
            permit.requireUsableFor(actor,permit.nativeSessionId());aliases.requireFor(permit,actor,nativePlayer());safe(world);
            if(reopenContext!=null)reopenContext.call(()->null);
            if(!pinHash.equals(hash(pinFile)))throw new IOException("External authority pin changed during promotion");
        }
        /** Worker-only. Rehashes all actual new copy bytes, then changes exactly the native owner pointer. */
        public synchronized void prepareOwnerPointer()throws IOException {
            current();if(prepared||armed||consumed)throw new IOException("Promotion ticket is one-shot");
            verifyNative(world,expected);
            Path level=world.resolve("level.dat");byte[] original=read(level,LEVEL_LIMIT);
            if(!hash(original).equals(expected.get("level.dat").sha256()))throw new IOException("Native level differs from verified READY");
            CompoundTag root=NbtIo.readCompressed(level,NbtAccounter.create(LEVEL_LIMIT));
            CompoundTag data=root.getCompound("Data").orElseThrow(()->new IOException("Missing native level Data"));
            UUID previous=data.read("singleplayer_uuid",UUIDUtil.CODEC).orElseThrow(()->new IOException("Missing native singleplayer owner pointer"));
            if(!aliases.aliases().containsValue(previous))throw new IOException("Native owner pointer is outside the verified actor table");
            data.store("singleplayer_uuid",UUIDUtil.CODEC,nativePlayer());
            Path temporary=Files.createTempFile(world,".promotion-level-",".tmp");boolean installed=false;
            try {
                ProfileFiles.protect(temporary,false);NbtIo.writeCompressed(root,temporary);
                if(Files.size(temporary)>LEVEL_LIMIT||!NbtIo.readCompressed(temporary,NbtAccounter.create(LEVEL_LIMIT)).equals(root))throw new IOException("Native pointer write did not round-trip");
                try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
                if(!Arrays.equals(original,read(level,LEVEL_LIMIT)))throw new IOException("Native level changed before pointer CAS");
                current();Files.move(temporary,level,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);installed=true;
                oldLevel=original;preparedLevel=root.copy();modifiedLevel=hash(read(level,LEVEL_LIMIT));
                for(String path:List.of("data/darkwinter-testing.properties","data/darkwinter-online/access.json","data/darkwinter-online/authority.json"))
                    metadata.put(path,hash(read(world.resolve(path),path.endsWith("access.json")?512*1024:65536)));
                prepared=true;
            }catch(IOException|RuntimeException failed){if(installed&&oldLevel!=null){try{if(modifiedLevel.equals(hash(read(level,LEVEL_LIMIT))))replace(level,oldLevel);}catch(IOException restore){failed.addSuppressed(restore);}oldLevel=null;closed=true;}throw failed;}finally{if(!installed)Files.deleteIfExists(temporary);}
        }
        /** Client owner thread supplies the actual unchanged account UUID. No key/account/profile is rewritten. */
        public synchronized void arm(Path actualSaves,IdentityStore actualIdentity,UUID actualAccount)throws IOException{
            current();if(!prepared||armed||consumed||actualIdentity!=identity||actualAccount==null||!saves.equals(exact(actualSaves)))throw new IOException("Wrong promotion client/account context");
            verifyFast();account=actualAccount;armedAtMillis=System.currentTimeMillis();deadline=System.nanoTime()+Math.min(15000,permit.remainingMillis())*1000000;armed=true;
        }
        synchronized void claim(Path actualWorld,UUID actualAccount)throws IOException {
            current();if(!armed||consumed||!world.equals(exact(actualWorld))||!Objects.equals(account,actualAccount)||System.nanoTime()-deadline>=0)
                throw new IOException("Promotion constructor capability expired, consumed or mismatched");
            verifyConstructorLevel();consumed=true;armed=false;
        }
        synchronized void requireStartup(Path actualWorld,OnlineHostPermit actualPermit,UUID actualPlayer,String actualActor)throws IOException{
            current();if(!consumed||adopted||actualPermit!=permit||!world.equals(exact(actualWorld))||!nativePlayer().equals(actualPlayer)||!actor.equals(actualActor))
                throw new IOException("Wrong pending promotion startup");
        }
        synchronized void adoptedByHost(){adopted=true;oldLevel=null;preparedLevel=null;metadata.clear();}
        /** Denial-only recovery for a loader that started this exact copy without consuming its required constructor hook. */
        synchronized void suppressLiveRollback(Path actualWorld)throws IOException{
            if(!world.equals(exact(actualWorld))||!requiresScopedOpen(world))throw new IOException("Wrong live promotion failure target");
            closed=true;armed=false;oldLevel=null;
        }
        private void verifyFast()throws IOException{
            safe(world);if(!modifiedLevel.equals(hash(read(world.resolve("level.dat"),LEVEL_LIMIT))))throw new IOException("Prepared native owner pointer changed");
            verifyAuthorityMetadata();
        }
        /** Minecraft.doWorldLoad calls saveDataTag before the constructor; 26.3 rewrites only LastPlayed here. */
        private void verifyConstructorLevel()throws IOException{
            safe(world);byte[] actual=read(world.resolve("level.dat"),LEVEL_LIMIT);
            if(!modifiedLevel.equals(hash(actual))){
                CompoundTag loaded=NbtIo.readCompressed(new ByteArrayInputStream(actual),NbtAccounter.create(LEVEL_LIMIT));
                CompoundTag data=loaded.getCompound("Data").orElseThrow(()->new IOException("Missing constructor level Data"));
                CompoundTag expected=preparedLevel.getCompound("Data").orElseThrow(()->new IOException("Missing prepared level Data"));
                if(!(data.get("LastPlayed") instanceof LongTag)||!(expected.get("LastPlayed") instanceof LongTag))
                    throw new IOException("Constructor LastPlayed is not an exact native LONG");
                long played=data.getLong("LastPlayed").orElseThrow(),now=System.currentTimeMillis();
                if(played<armedAtMillis||played>now||now<armedAtMillis||now-armedAtMillis>15000)
                    throw new IOException("Constructor LastPlayed lies outside this armed native load");
                data.put("LastPlayed",expected.get("LastPlayed").copy());
                if(!loaded.equals(preparedLevel))throw new IOException("Prepared native level changed beyond LastPlayed");
            }
            verifyAuthorityMetadata();
        }
        private void verifyAuthorityMetadata()throws IOException{
            for(var row:metadata.entrySet())if(!row.getValue().equals(hash(read(world.resolve(row.getKey()),row.getKey().endsWith("access.json")?512*1024:65536))))
                throw new IOException("Prepared promotion authority/marker changed");
        }
        /** Before constructor only: CAS restore the old native pointer. Consumed worlds remain fenced, never rewritten live. */
        @Override public synchronized void close()throws IOException{
            if(closed)return;closed=true;armed=false;
            if(prepared&&!consumed&&oldLevel!=null){
                Path level=world.resolve("level.dat");safe(level);
                if(!modifiedLevel.equals(hash(read(level,LEVEL_LIMIT))))throw new IOException("Canceled promotion level changed; original bytes retained, no overwrite");
                replace(level,oldLevel);oldLevel=null;
            }
        }
    }
    /** Already copied and guarded. Does not carry permission to open/simulate and cannot be constructed from a raw existing path. */
    public static final class Staged implements AutoCloseable {
        private final Path world,saves;private final IdentityStore identity;private final ReplicaStore.Ready ready;
        private final Map<String,ReplicaProtocol.Entry> expected;private final AuthorityStandbyCheckpoint baseline;private boolean activated,closed;
        private Staged(Path world,Path saves,IdentityStore identity,ReplicaStore.Ready ready,Map<String,ReplicaProtocol.Entry> expected,AuthorityStandbyCheckpoint baseline){
            this.world=world;this.saves=saves;this.identity=identity;this.ready=ready;this.expected=Map.copyOf(expected);this.baseline=baseline;
        }
        public Path world(){return world;}
        public AuthorityStandbyCheckpoint baseline(){return baseline;}
        public String checkpointSha256(){return ready.index().contentRoot();}
        public long checkpointSequence(){return ready.header().sequence();}
        /** Called only AFTER the journal prepared this exact existing path and the Node elected the actual local candidate. */
        public synchronized Ticket activate(OnlineHostPermit freshPermit)throws IOException {
            if(closed||activated||freshPermit==null)throw new IOException("Staged promotion is closed, activated or lacks fresh authority");
            String actor=identity.profile().id();freshPermit.requireUsableFor(actor,freshPermit.nativeSessionId());
            if(!ready.certificate().authorityId().equals(freshPermit.authorityId()))throw new IOException("Staged copy belongs to another authority");
            ready.requirePromotion(freshPermit.fence(),actor);PinWitness pin=existingPin(identity,freshPermit);
            UUID alias=ready.index().actorAliases().get(actor);if(alias==null)throw new IOException("Staged checkpoint has no candidate alias");
            NativeActorAliases aliases=NativeActorAliases.bind(freshPermit,ready.index(),actor,alias);
            verifyNative(world,expected);
            try(IslandAccess access=IslandAccess.open(world,identity.profile())){
                eligible(access,actor,freshPermit.certificate());
                IslandAuthorityFiles.installVerified(world,access.snapshot(),new IslandAuthorityFiles.Document(freshPermit.certificate(),freshPermit.policy().proof()),freshPermit.fence());
            }
            verifyNative(world,expected);ready.requirePromotion(freshPermit.fence(),actor);aliases.requireFor(freshPermit,actor,alias);
            activated=true;return new Ticket(world,saves,identity,freshPermit,aliases,expected,pin,null);
        }
        /** Retain this inactive copy and its no-vanilla-open guard; never delete original/cache/native actor data. */
        @Override public synchronized void close(){closed=true;}
    }
    /** Proactive stage while an independently observed old host lease is still live. No new lease or native alias is created. */
    public static Staged stageReady(ReplicaStore.Ready ready,IdentityStore actualIdentity,Path nativeSaves,
                                   WorldAuthority.Policy oldVerifiedPolicy,LeaseFence.Fence independentlyObservedOldHost)throws IOException {
        Objects.requireNonNull(ready);Objects.requireNonNull(actualIdentity);Objects.requireNonNull(oldVerifiedPolicy);
        WorldAuthority.Certificate certificate=ready.certificate();ReplicaProtocol.fresh(certificate,independentlyObservedOldHost);
        ReplicaProtocol.sameFence(ready.header(),independentlyObservedOldHost);
        if(ready.header().sequence()!=independentlyObservedOldHost.checkpointSequence()||!ready.index().contentRoot().equals(independentlyObservedOldHost.checkpointSha256()))
            throw new IOException("Proactive stage is not the current independently confirmed old-host checkpoint");
        WorldAuthority.Policy policy=WorldAuthority.verifyPolicy(certificate,oldVerifiedPolicy.proof());
        if(!policy.sha256().equals(oldVerifiedPolicy.sha256())||policy.revision()!=independentlyObservedOldHost.policyRevision()
                ||!policy.sha256().equals(independentlyObservedOldHost.policySha256()))throw new IOException("Stage policy is not confirmed by old-host Node observation");
        String actor=actualIdentity.profile().id();WorldAuthority.authorizeHost(policy,actor,System.currentTimeMillis());
        if(!ready.index().actorAliases().containsKey(actor))throw new IOException("Stage lacks the actual candidate's checkpoint-bound UUID");
        existingPin(actualIdentity,certificate,independentlyObservedOldHost.epoch(),independentlyObservedOldHost.policyRevision());
        return copyReady(ready,actualIdentity,nativeSaves,certificate,policy,independentlyObservedOldHost);
    }
    /** Convenience for an already elected explicit fixture; crash-safe application flow uses stageReady, journal, claim, activate. */
    public static Ticket materialize(ReplicaStore.Ready ready,IdentityStore actualIdentity,OnlineHostPermit freshPermit,Path nativeSaves)throws IOException {
        Objects.requireNonNull(ready);Objects.requireNonNull(actualIdentity);Objects.requireNonNull(freshPermit);
        String actor=actualIdentity.profile().id();freshPermit.requireUsableFor(actor,freshPermit.nativeSessionId());
        ready.requirePromotion(freshPermit.fence(),actor);existingPin(actualIdentity,freshPermit);
        Staged staged=copyReady(ready,actualIdentity,nativeSaves,freshPermit.certificate(),freshPermit.policy(),freshPermit.fence());
        return staged.activate(freshPermit);
    }
    /**
     * Worker-only controlled reopen. Only a revocable registered namespace token supplies the target;
     * a raw existing path, mutable owner field or unconfirmed alias map never creates a capability.
     * The actual lifecycle must have stopped the old native server and confirmed this complete saved index at the Node.
     */
    public static Ticket reopenStopped(TestingArchiveNamespaces.Token registeredTarget,IdentityStore actualIdentity,
                                       OnlineHostPermit freshPermit,NativeActorAliases currentAliases,Path actualSaves)throws IOException {
        if(registeredTarget==null||actualIdentity==null||freshPermit==null||currentAliases==null)
            throw new IOException("Controlled reopen needs registered target and current native authority");
        return registeredTarget.call(()->{
            String actor=actualIdentity.profile().id();freshPermit.requireUsableFor(actor,freshPermit.nativeSessionId());
            currentAliases.requireFor(freshPermit,actor,currentAliases.localNativePlayer());
            if(!registeredTarget.authorityId().equals(freshPermit.authorityId()))throw new IOException("Registered reopen target belongs to another authority");
            Path world=exact(registeredTarget.world()),saves=exact(actualSaves);safe(world);safe(saves);
            if(!Files.isDirectory(saves,LinkOption.NOFOLLOW_LINKS)||!world.getParent().equals(saves))
                throw new IOException("Controlled native reopen is not a direct child of the actual saves directory");
            Path lockFile=world.resolve("session.lock");safe(lockFile);
            if(Files.exists(lockFile,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(lockFile,LinkOption.NOFOLLOW_LINKS)||Files.size(lockFile)>16))
                throw new IOException("Invalid native session lock metadata");
            try(FileChannel lockChannel=FileChannel.open(lockFile,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
                    FileLock nativeLock=stoppedLock(lockChannel)){
            Map<String,ReplicaProtocol.Entry> expected=new TreeMap<>();
            for(var entry:currentAliases.index().files().values())
                if(!ReplicaProtocol.replicationArchivePath(entry.path()))expected.put(ReplicaProtocol.path(entry.path()),entry);
            verifyNative(world,expected);PinWitness pin=existingPin(actualIdentity,freshPermit);
            try(IslandAccess access=IslandAccess.open(world,actualIdentity.profile())){
                eligible(access,actor,freshPermit.certificate());
                var document=IslandAuthorityFiles.load(world,access.snapshot()).orElseThrow(()->new IOException("Controlled reopen lacks its existing public authority"));
                var genesis=WorldAuthority.verify(freshPermit.certificate());
                WorldAuthority.requirePinned(document.certificate(),freshPermit.authorityId(),genesis.ownerId());
                if(!WorldAuthority.verifyPolicy(document.certificate(),document.policy()).sha256().equals(freshPermit.policy().sha256()))
                    throw new IOException("Controlled reopen policy differs from the current confirmed policy");
            }
            freshPermit.requireUsableFor(actor,freshPermit.nativeSessionId());currentAliases.requireFor(freshPermit,actor,currentAliases.localNativePlayer());
            synchronized(REQUIRES_SCOPE){
                if(!REQUIRES_SCOPE.contains(world)&&REQUIRES_SCOPE.size()>=64)throw new IOException("Promotion workspace scope capacity");
                REQUIRES_SCOPE.add(world);
            }
            Ticket ticket=new Ticket(world,saves,actualIdentity,freshPermit,currentAliases,expected,pin,registeredTarget);
            try{ticket.prepareOwnerPointer();return ticket;}
            catch(IOException|RuntimeException failure){try{ticket.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
            }
        });
    }
    private static FileLock stoppedLock(FileChannel channel)throws IOException{
        try{FileLock lock=channel.tryLock();if(lock==null)throw new IOException("Controlled reopen native save is still in use");return lock;}
        catch(OverlappingFileLockException busy){throw new IOException("Controlled reopen native save is still in use",busy);}
    }
    private static void eligible(IslandAccess access,String actor,WorldAuthority.Certificate certificate)throws IOException{
        WorldAuthority.requireAccess(certificate,access.islandId(),access.ownerId());
        if(!access.allowed(actor)||!access.can(actor,IslandAccess.Permission.VIEW)||!access.can(actor,IslandAccess.Permission.TRANSFER_HOST)||!access.trustedLead(actor))
            throw new IOException("Actual candidate lacks current allowed/trusted/TRANSFER_HOST ACL authority");
    }
    private static Staged copyReady(ReplicaStore.Ready ready,IdentityStore actualIdentity,Path nativeSaves,
                                   WorldAuthority.Certificate certificate,WorldAuthority.Policy policy,LeaseFence.Fence fence)throws IOException {
        String actor=actualIdentity.profile().id();WorldAuthority.authorizeHost(policy,actor,System.currentTimeMillis());
        Path saves=exact(nativeSaves);safe(saves);if(!Files.isDirectory(saves,LinkOption.NOFOLLOW_LINKS))throw new IOException("Actual native saves parent is missing");
        Path target=saves.resolve("DW-Promoted-"+UUID.randomUUID()),stage=saves.resolve(".dw-promote-"+UUID.randomUUID());
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)||Files.exists(stage,LinkOption.NOFOLLOW_LINKS))throw new IOException("Promotion target must be absent");
        synchronized(REQUIRES_SCOPE){if(REQUIRES_SCOPE.size()>=64)throw new IOException("Promotion workspace scope capacity");REQUIRES_SCOPE.add(target);}
        Files.createDirectory(stage);ProfileFiles.protect(stage,true);boolean published=false;
        try {
            Map<String,ReplicaProtocol.Entry> expected=new TreeMap<>();
            for(var entry:ready.index().files().values()){
                if(ReplicaProtocol.replicationArchivePath(entry.path()))continue;
                String path=ReplicaProtocol.path(entry.path());Path output=stage.resolve(path).normalize();if(!output.startsWith(stage))throw new IOException("Native copy path escape");
                safe(output);Files.createDirectories(output.getParent());ProfileFiles.protect(output.getParent(),true);
                try(InputStream input=ready.open(path);FileChannel channel=FileChannel.open(output,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                    byte[] buffer=new byte[65536];long count=0;int size;
                    while((size=input.read(buffer))!=-1){canceled();count+=size;if(count>entry.bytes())throw new IOException("READY object grew");ByteBuffer bytes=ByteBuffer.wrap(buffer,0,size);while(bytes.hasRemaining())channel.write(bytes);}
                    if(count!=entry.bytes())throw new IOException("READY object truncated");channel.force(true);
                }ProfileFiles.protect(output,false);expected.put(path,entry);
            }
            verifyNative(stage,expected);fence.requireUsable();
            try(IslandAccess access=IslandAccess.open(stage,actualIdentity.profile())){
                WorldAuthority.requireAccess(certificate,access.islandId(),access.ownerId());
                if(!access.allowed(actor)||!access.can(actor,IslandAccess.Permission.VIEW)||!access.can(actor,IslandAccess.Permission.TRANSFER_HOST)||!access.trustedLead(actor))
                    throw new IOException("Actual candidate lacks current allowed/trusted/TRANSFER_HOST ACL authority");
                IslandAuthorityFiles.installVerified(stage,access.snapshot(),new IslandAuthorityFiles.Document(certificate,policy.proof()),fence);
            }
            verifyNative(stage,expected);fence.requireUsable();existingPin(actualIdentity,certificate,fence.epoch(),fence.policyRevision());
            Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE);published=true;
            return new Staged(target,saves,actualIdentity,ready,expected,AuthorityStandbyCheckpoint.capture(certificate,policy,fence,actor,target));
        }finally{if(!published){try{removeNewStage(stage,saves);}finally{REQUIRES_SCOPE.remove(target);}}}
    }
    private record PinWitness(Path file,String sha256){}
    private static PinWitness existingPin(IdentityStore identity,OnlineHostPermit permit)throws IOException{
        return existingPin(identity,permit.certificate(),permit.nodeLeaseEpoch(),permit.policyRevision());
    }
    private static PinWitness existingPin(IdentityStore identity,WorldAuthority.Certificate certificate,long epoch,long policyRevision)throws IOException{
        var genesis=WorldAuthority.verify(certificate);
        Path file=identity.base().resolve("world-authority-pins.json");byte[] before=read(file,8*1024*1024);
        try(AuthorityPins pins=AuthorityPins.open(identity.base())){
            AuthorityPins.Pin pin=pins.get(genesis.islandId()).orElseThrow(()->new IOException("No existing external authority pin for promoted realm"));
            WorldAuthority.requirePinned(certificate,pin.authorityId(),pin.ownerId());
            if(!pin.nodeId().equals(genesis.coordinatorId())||pin.minEpoch()>epoch||pin.minPolicy()>policyRevision)
                throw new IOException("Fresh promotion permit differs from external Node/epoch/policy floor");
        }
        if(!Arrays.equals(before,read(file,8*1024*1024)))throw new IOException("External authority pins changed during verification");
        return new PinWitness(file,hash(before));
    }
    private static void verifyNative(Path world,Map<String,ReplicaProtocol.Entry> expected)throws IOException{
        for(var row:expected.entrySet()){canceled();Path file=world.resolve(row.getKey());safe(file);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=row.getValue().bytes()||!hash(file).equals(row.getValue().sha256()))throw new IOException("Native materialized copy differs from verified checkpoint");}
        try(var paths=Files.walk(world)){Iterator<Path> iterator=paths.iterator();int count=0;while(iterator.hasNext()){
            Path path=iterator.next();safe(path);if(++count>32768)throw new IOException("Native workspace inventory quota");
            if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))continue;String relative=world.relativize(path).toString().replace('\\','/');
            if(!expected.containsKey(relative)){
                if(ReplicaProtocol.authoritySidecar(relative)){read(path,65536);}
                else if(!ReplicaProtocol.derivedTransient(relative)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>16)
                    throw new IOException("Unexpected file in native promotion workspace");
            }
        }}
    }
    static Path exact(Path path)throws IOException{if(path==null)throw new IOException("Missing promotion path");Path exact=path.toAbsolutePath();if(!exact.equals(exact.normalize()))throw new IOException("Promotion path is not canonical");return exact;}
    static void safe(Path path)throws IOException{for(Path part=exact(path);part!=null;part=part.getParent())if(Files.isSymbolicLink(part)||Files.exists(part,LinkOption.NOFOLLOW_LINKS)&&!part.toRealPath().equals(part))throw new IOException("Linked/aliased promotion path");}
    private static byte[] read(Path file,int limit)throws IOException{safe(file);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>limit)throw new IOException("Missing/oversized promotion metadata");try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("Promotion metadata grew");return bytes;}}
    private static void replace(Path file,byte[] bytes)throws IOException{Path temp=Files.createTempFile(file.getParent(),".promotion-restore-",".tmp");boolean done=false;try{ProfileFiles.protect(temp,false);try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);done=true;}finally{if(!done)Files.deleteIfExists(temp);}}
    private static String hash(Path file)throws IOException{try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=new byte[65536];int count;while((count=input.read(bytes))!=-1){canceled();digest.update(bytes,0,count);}}return HexFormat.of().formatHex(digest.digest());}catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}}
    private static String hash(byte[] bytes)throws IOException{try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}}
    private static void canceled()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Promotion preparation canceled");}
    private static void removeNewStage(Path stage,Path parent)throws IOException{
        if(!stage.toAbsolutePath().normalize().getParent().equals(parent)||!stage.getFileName().toString().startsWith(".dw-promote-"))throw new IOException("Unsafe promotion stage cleanup");
        safe(stage);if(!Files.exists(stage,LinkOption.NOFOLLOW_LINKS))return;List<Path> files;try(var paths=Files.walk(stage)){files=paths.limit(32769).sorted(Comparator.reverseOrder()).toList();}
        if(files.size()>32768)throw new IOException("Promotion stage cleanup quota");for(Path path:files){safe(path);if(!path.startsWith(stage))throw new IOException("Promotion cleanup escape");}for(Path path:files)Files.delete(path);
    }
}
