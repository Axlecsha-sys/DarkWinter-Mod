// Persists verified authority and coordinator trust anchors outside world saves.
package com.dark.winter.online.authority;

import com.dark.winter.online.identity.ProfileFiles;
import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.text.Normalizer;
import java.util.*;

/**
 * Durable public trust anchors outside untrusted world copies. No identity/key is opened,
 * no endpoint is contacted and no hosting permission follows merely from possessing a pin.
 * Initial enrollment trust is the caller's verified SERVICE_RESPONSE decision.
 */
public final class AuthorityPins implements AutoCloseable {
    public static final int MAX_REALMS=1024;
    private static final int MAX_BYTES=8*1024*1024;
    private static final String FILE="world-authority-pins.json",LOCK=".world-authority-pins.lock";
    private final Path base,file,lock;
    private boolean closed;
    private long observedRevision=-1;
    private String observedDigest="";
    private Map<UUID,Pin> observed=Map.of();
    public record Pin(UUID islandId,String authorityId,String ownerId,String nodeId,
                      long minEpoch,long minPolicy,WorldAuthority.Certificate certificate) {}
    private record State(long revision,Map<UUID,Pin> pins,byte[] bytes,String digest) {}
    @FunctionalInterface private interface Work<T>{T run(State state)throws IOException;}
    private AuthorityPins(Path base){this.base=base;file=base.resolve(FILE);lock=base.resolve(LOCK);}
    public static AuthorityPins open(Path explicitIdentityBase)throws IOException {
        Objects.requireNonNull(explicitIdentityBase);
        Path requested=explicitIdentityBase.toAbsolutePath();
        if(!requested.equals(requested.normalize()))throw new IOException("Authority pin base must be an exact canonical path");
        safe(requested);ProfileFiles.requireOutsideWorld(requested);
        Path base=ProfileFiles.directory(requested);safe(base);
        AuthorityPins store=new AuthorityPins(base);
        store.locked(state->null);
        return store;
    }
    public Path base(){return base;}
    public Optional<Pin> get(UUID island)throws IOException {
        Objects.requireNonNull(island);canonicalUUID(island.toString());
        return locked(state->Optional.ofNullable(state.pins().get(island)));
    }
    public Map<UUID,Pin> snapshot()throws IOException {return locked(State::pins);}
    /**
     * The owner certificate is cryptographically verified here. The caller must first
     * verify the fresh node enrollment/status receipt; this method does not invent such a receipt.
     * A changed root/owner/node for an existing island UUID is always a distinct rejected authority.
     */
    public Pin pinVerified(WorldAuthority.Certificate certificate,String nodeId,long minEpoch,long minPolicy)throws IOException {
        WorldAuthority.Genesis genesis=WorldAuthority.verify(certificate);
        WorldAuthority.identity(nodeId);floors(minEpoch,minPolicy);
        if(!nodeId.equals(genesis.coordinatorId()))throw new IOException("Coordinator differs from the signed owner root");
        return locked(state->{
            Pin old=state.pins().get(genesis.islandId());
            if(old!=null)requireSame(old,certificate,genesis,nodeId);
            if(old!=null&&(minEpoch<old.minEpoch()||minPolicy<old.minPolicy()))throw new IOException("Authority pin epoch/policy rollback");
            Pin next=new Pin(genesis.islandId(),certificate.authorityId(),genesis.ownerId(),nodeId,minEpoch,minPolicy,old==null?certificate:old.certificate());
            if(old!=null&&old.minEpoch()==minEpoch&&old.minPolicy()==minPolicy)return old;
            if(old==null&&state.pins().size()>=MAX_REALMS)throw new IOException("Authority pin realm capacity");
            Map<UUID,Pin> updated=new HashMap<>(state.pins());updated.put(next.islandId(),next);
            commit(state,updated);return next;
        });
    }
    /** Fence construction is private to LeaseFence.accept: signatures, fresh request and monotonic deadline were verified there. */
    public Pin advanceVerified(WorldAuthority.Certificate certificate,LeaseFence.Fence fence)throws IOException {
        if(fence==null)throw new IOException("Missing verified lease fence");
        fence.requireUsable();WorldAuthority.Genesis genesis=WorldAuthority.verify(certificate);
        if(!certificate.authorityId().equals(fence.authorityId()))throw new IOException("Lease fence belongs to another certified authority");
        return locked(state->{
            fence.requireUsable();
            Pin old=state.pins().get(genesis.islandId());if(old==null)throw new IOException("Authority must be externally pinned after enrollment before advancing");
            requireSame(old,certificate,genesis,genesis.coordinatorId());
            if(fence.epoch()<old.minEpoch()||fence.policyRevision()<old.minPolicy())throw new IOException("Verified lease is older than the durable external pin");
            if(fence.epoch()==old.minEpoch()&&fence.policyRevision()==old.minPolicy())return old;
            Pin next=new Pin(old.islandId(),old.authorityId(),old.ownerId(),old.nodeId(),fence.epoch(),fence.policyRevision(),old.certificate());
            Map<UUID,Pin> updated=new HashMap<>(state.pins());updated.put(next.islandId(),next);
            // Recheck after the bounded read and immediately before the durable commit.
            fence.requireUsable();commit(state,updated);return next;
        });
    }
    private <T>T locked(Work<T> action)throws IOException {
        // One JVM monitor avoids overlapping Java FileLock exceptions; OS lock also serializes other processes.
        synchronized(AuthorityPins.class) {
            if(closed)throw new IOException("Authority pin store closed");
            safe(base);safe(file);safe(lock);ProfileFiles.requireOutsideWorld(base);
            if(Files.exists(lock,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(lock,LinkOption.NOFOLLOW_LINKS)||Files.size(lock)!=0))
                throw new IOException("Invalid authority pin lock file");
            try(FileChannel channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
                ProfileFiles.protect(lock,false);
                try(FileLock guard=tryLock(channel)) {
                    State state=read();observe(state);
                    return action.run(state);
                }
            }
        }
    }
    private static FileLock tryLock(FileChannel channel)throws IOException {
        try{FileLock result=channel.tryLock();if(result==null)throw new IOException("Authority pin store is busy");return result;}
        catch(OverlappingFileLockException busy){throw new IOException("Authority pin store is busy",busy);}
    }
    private State read()throws IOException {
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return new State(0,Map.of(),null,"");
        safe(file);byte[] bytes=readBytes(file);JsonObject envelope=ProfileFiles.parse(bytes,MAX_BYTES);
        if(!Arrays.equals(bytes,ProfileFiles.bytes(envelope)))throw new IOException("Authority pin file is not canonical");
        ProfileFiles.fields(envelope,Set.of("schema","revision","pins","sha256"),Set.of());
        if(WorldAuthority.integer(envelope,"schema")!=1)throw new IOException("Unsupported authority pin schema");
        long revision=WorldAuthority.integer(envelope,"revision");if(revision<0)throw new IOException("Invalid authority pin revision");
        String expected=WorldAuthority.digest(ProfileFiles.string(envelope,"sha256",64));
        JsonObject body=envelope.deepCopy();body.remove("sha256");
        if(!hash(ProfileFiles.bytes(body)).equals(expected))throw new IOException("Authority pin file checksum mismatch");
        if(!envelope.get("pins").isJsonObject())throw new IOException("Invalid authority pin catalog");
        JsonObject rows=envelope.getAsJsonObject("pins");if(rows.size()>MAX_REALMS)throw new IOException("Authority pin realm capacity");
        Map<UUID,Pin> pins=new HashMap<>();
        for(var row:rows.entrySet()) {
            UUID island=canonicalUUID(row.getKey());if(!row.getValue().isJsonObject())throw new IOException("Invalid authority pin row");
            JsonObject value=row.getValue().getAsJsonObject();ProfileFiles.fields(value,Set.of("certificate","nodeId","minEpoch","minPolicy"),Set.of());
            if(!value.get("certificate").isJsonObject())throw new IOException("Missing owner root certificate");
            var certificate=WorldAuthority.certificateFromJson(value.getAsJsonObject("certificate"));var genesis=WorldAuthority.verify(certificate);
            String node=WorldAuthority.identity(ProfileFiles.string(value,"nodeId",64));
            long epoch=WorldAuthority.integer(value,"minEpoch"),policy=WorldAuthority.integer(value,"minPolicy");floors(epoch,policy);
            if(!island.equals(genesis.islandId())||!node.equals(genesis.coordinatorId()))throw new IOException("Authority pin key/node differs from signed island root");
            pins.put(island,new Pin(island,certificate.authorityId(),genesis.ownerId(),node,epoch,policy,certificate));
        }
        ProfileFiles.protect(file,false);
        return new State(revision,Map.copyOf(pins),bytes,hash(bytes));
    }
    private void observe(State current)throws IOException {
        if(observedRevision>=0) {
            if(current.revision()<observedRevision||current.revision()==observedRevision&&!current.digest().equals(observedDigest))
                throw new IOException("Authority pin catalog rollback or same-revision replacement");
            for(Pin previous:observed.values()) {
                Pin next=current.pins().get(previous.islandId());if(next==null)throw new IOException("Previously observed authority pin was removed");
                requireSame(previous,next.certificate(),WorldAuthority.verify(next.certificate()),next.nodeId());
                if(next.minEpoch()<previous.minEpoch()||next.minPolicy()<previous.minPolicy())throw new IOException("Previously observed epoch/policy pin was rolled back");
            }
        }
        observedRevision=current.revision();observedDigest=current.digest();observed=current.pins();
    }
    private void commit(State previous,Map<UUID,Pin> pins)throws IOException {
        if(pins.size()>MAX_REALMS||previous.revision()==Long.MAX_VALUE)throw new IOException("Authority pin catalog capacity/revision limit");
        JsonObject body=new JsonObject();body.addProperty("schema",1);body.addProperty("revision",previous.revision()+1);
        JsonObject rows=new JsonObject();for(UUID island:new TreeSet<>(pins.keySet())) {
            Pin pin=pins.get(island);JsonObject value=new JsonObject();value.add("certificate",WorldAuthority.certificateToJson(pin.certificate()));
            value.addProperty("nodeId",pin.nodeId());value.addProperty("minEpoch",pin.minEpoch());value.addProperty("minPolicy",pin.minPolicy());rows.add(island.toString(),value);
        }
        body.add("pins",rows);body.addProperty("sha256",hash(ProfileFiles.bytes(body)));
        byte[] bytes=ProfileFiles.bytes(body);if(bytes.length>MAX_BYTES)throw new IOException("Authority pin byte capacity");
        // Full-byte CAS catches non-cooperating file edits between validation and publication.
        safe(file);
        if(previous.bytes()==null) {
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Authority pin file appeared during update");
        } else if(!Arrays.equals(previous.bytes(),readBytes(file)))throw new IOException("Authority pin file changed during update");
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Authority pin commit canceled");
        ProfileFiles.atomic(file,body,previous.bytes()!=null);
        observedRevision=previous.revision()+1;observedDigest=hash(bytes);observed=Map.copyOf(pins);
    }
    private static void requireSame(Pin previous,WorldAuthority.Certificate certificate,WorldAuthority.Genesis genesis,String node)throws IOException {
        WorldAuthority.requirePinned(certificate,previous.authorityId(),previous.ownerId());
        if(!previous.islandId().equals(genesis.islandId())||!previous.nodeId().equals(node)||!node.equals(genesis.coordinatorId()))
            throw new IOException("Pinned island or coordinator cannot be replaced");
    }
    private static byte[] readBytes(Path path)throws IOException {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>MAX_BYTES)throw new IOException("Missing/oversized authority pin file");
        try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES)throw new IOException("Authority pin file grew");return bytes;
        }
    }
    private static void safe(Path path)throws IOException {
        Path absolute=path.toAbsolutePath().normalize();
        if(absolute.toString().startsWith("\\\\"))throw new IOException("Authority pins require a local profile path");
        for(Path current=absolute;current!=null;current=current.getParent()) {
            Path leaf=current.getFileName();
            if(leaf!=null) {
                String name=leaf.toString();
                if(name.endsWith(".")||name.endsWith(" ")||!Normalizer.isNormalized(name,Normalizer.Form.NFC)||
                    name.codePoints().anyMatch(c->c<32||c==127||":*?<>|\"".indexOf(c)>=0)||
                    name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?"))
                    throw new IOException("Windows alias/reserved authority pin path");
            }
            if(Files.isSymbolicLink(current)||Files.exists(current,LinkOption.NOFOLLOW_LINKS)&&!current.toRealPath().equals(current))
                throw new IOException("Linked/redirected authority pin path");
        }
    }
    private static UUID canonicalUUID(String value)throws IOException {return WorldAuthority.uuid(value);}
    private static void floors(long epoch,long policy)throws IOException {if(epoch<0||policy<0)throw new IOException("Authority pin floors must be nonnegative");}
    private static String hash(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    @Override public void close(){synchronized(AuthorityPins.class){closed=true;observed=Map.of();}}
}
