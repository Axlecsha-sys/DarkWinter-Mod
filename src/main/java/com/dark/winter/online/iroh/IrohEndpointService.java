// Owns the shared-profile Iroh endpoint, private transport key, and bounded native operations.
package com.dark.winter.online.iroh;

import com.dark.winter.DarkWinter;
import com.dark.winter.online.identity.IdentityStore;
import com.dark.winter.online.identity.ProfileFiles;
import com.google.gson.JsonObject;
import computer.iroh.Accepting;
import computer.iroh.Connection;
import computer.iroh.CounterStats;
import computer.iroh.Endpoint;
import computer.iroh.EndpointAddr;
import computer.iroh.EndpointId;
import computer.iroh.EndpointOptions;
import computer.iroh.Incoming;
import computer.iroh.Iroh_ffiKt;
import computer.iroh.RelayMode;
import computer.iroh.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import kotlin.Unit;

/**
 * One actual shared-profile transport endpoint. Its independent key is private
 * local storage, never a Minecraft identity, friendship, island grant or lease.
 * File work, native work and public callers use separate execution paths.
 */
public final class IrohEndpointService implements AutoCloseable {
    private static final String QA_RELAY_PROPERTY = "darkwinter.online.iroh.qaRelay";
    /** Strict development opt-in; an absent property keeps the ordinary N0 preset untouched. */
    enum QaRelay {
        US_WEST("https://usw1-1.relay.n0.iroh.link./"),
        US_EAST("https://use1-1.relay.n0.iroh.link./"),
        EU_CENTRAL("https://euc1-1.relay.n0.iroh.link./"),
        ASIA("https://aps1-1.relay.n0.iroh.link./");
        private final String url;
        QaRelay(String url) { this.url = url; }
    }
    static QaRelay parseQaRelay(String selection) throws IOException {
        if (selection == null) return null;
        return switch (selection) {
            case "us_west" -> QaRelay.US_WEST;
            case "us_east" -> QaRelay.US_EAST;
            case "eu_central" -> QaRelay.EU_CENTRAL;
            case "asia" -> QaRelay.ASIA;
            default -> throw new Failure(FailureCode.SERVICE_BIND);
        };
    }
    public enum SocketSampleState { NONE, READY, UNAVAILABLE }
    /** Only fixed socket metric counters. No metric descriptions, dynamic names, paths or identities. */
    public record SocketStats(SocketSampleState sample,long sendRelay,long recvDataRelay,long relayConnectionsSucceeded,
                              long relayConnectionsFailed,long relayConnectionsClosed,long relayConnectionsRateLimited,long recvGroDatagrams) {
        public SocketStats {
            sample=sample==null?SocketSampleState.NONE:sample;sendRelay=bounded(sendRelay);recvDataRelay=bounded(recvDataRelay);
            relayConnectionsSucceeded=bounded(relayConnectionsSucceeded);relayConnectionsFailed=bounded(relayConnectionsFailed);
            relayConnectionsClosed=bounded(relayConnectionsClosed);relayConnectionsRateLimited=bounded(relayConnectionsRateLimited);
            recvGroDatagrams=bounded(recvGroDatagrams);
        }
        private static long bounded(long value){return Math.clamp(value,0,0xffff_ffffL);}
        public static SocketStats empty(){return new SocketStats(SocketSampleState.NONE,0,0,0,0,0,0,0);}
        public static SocketStats unavailable(){return new SocketStats(SocketSampleState.UNAVAILABLE,0,0,0,0,0,0,0);}
        public JsonObject toJson(){
            JsonObject value=new JsonObject();value.addProperty("sample",sample.name().toLowerCase(java.util.Locale.ROOT));
            value.addProperty("send_relay",sendRelay);value.addProperty("recv_data_relay",recvDataRelay);
            value.addProperty("relay_conns_success",relayConnectionsSucceeded);value.addProperty("relay_conns_failed",relayConnectionsFailed);
            value.addProperty("relay_conns_closed",relayConnectionsClosed);value.addProperty("relay_conns_ratelimited",relayConnectionsRateLimited);
            value.addProperty("recv_gro_datagrams",recvGroDatagrams);return value;
        }
        public String summary(){
            return "socket_sample="+sample.name().toLowerCase(java.util.Locale.ROOT)+" send_relay="+sendRelay
                    +" recv_data_relay="+recvDataRelay+" relay_conns_success="+relayConnectionsSucceeded
                    +" relay_conns_failed="+relayConnectionsFailed+" relay_conns_closed="+relayConnectionsClosed
                    +" relay_conns_ratelimited="+relayConnectionsRateLimited+" recv_gro_datagrams="+recvGroDatagrams;
        }
    }
    /** Pure fixed-key decoder for the actual pinned UInt CounterStats ABI. */
    static SocketStats socketStatsFrom(Map<String,CounterStats> counters){
        try{
            if(counters==null)return SocketStats.unavailable();
            Method value=CounterStats.class.getMethod("getValue-pVg5ArA");
            return new SocketStats(SocketSampleState.READY,counter(counters,value,"socket:send_relay"),
                    counter(counters,value,"socket:recv_data_relay"),counter(counters,value,"socket:relay_conns_success"),
                    counter(counters,value,"socket:relay_conns_failed"),counter(counters,value,"socket:relay_conns_closed"),
                    counter(counters,value,"socket:relay_conns_ratelimited"),counter(counters,value,"socket:recv_gro_datagrams"));
        }catch(Throwable unavailable){return SocketStats.unavailable();}
    }
    private static long counter(Map<String,CounterStats> counters,Method getter,String name)throws ReflectiveOperationException{
        CounterStats value=counters.get(name);if(value==null)throw new IllegalStateException("Socket metric unavailable");
        return Integer.toUnsignedLong((Integer)getter.invoke(value));
    }
    /** Network-worker only; UI callers must use the route's immutable cached snapshot. */
    public SocketStats sampleSocketStatsOnWorker(){
        try{return socketStatsFrom(requireEndpoint().stats());}
        catch(Throwable unavailable){return SocketStats.unavailable();}
    }
    public enum FailureCode { NATIVE_LOAD, LOG_INIT, KEY_STORAGE, SERVICE_BIND }
    /** Fixed diagnostic enums only: native exception messages, paths and credentials are discarded. */
    public static final class Failure extends IOException {
        private final FailureCode code;private final IrohAsync.Code transportCode;
        private Failure(FailureCode code){this(code,null);}
        private Failure(FailureCode code,IrohAsync.Code transportCode){
            super("Iroh endpoint startup "+code.name());this.code=code;this.transportCode=transportCode;
        }
        public FailureCode code(){return code;}
        public IrohAsync.Code transportCode(){return transportCode;}
    }
    private static boolean loggingReady;
    private static Failure loggingFailure;
    private static synchronized void initializeLogging()throws IOException{
        if(loggingReady)return;
        if(loggingFailure!=null)throw loggingFailure;
        try{Iroh_ffiKt.setLogLevel(computer.iroh.LogLevel.OFF);loggingReady=true;}
        catch(Throwable failed){loggingFailure=new Failure(failed instanceof LinkageError?FailureCode.NATIVE_LOAD:FailureCode.LOG_INIT);throw loggingFailure;}
    }
    private static final Duration BIND_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final ThreadPoolExecutor KEY_IO = new ThreadPoolExecutor(
            1, 1, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "Iroh-endpoint-key-io");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final IdentityStore identity;
    private final String profileId;
    private final List<String> alpns;
    private final QaRelay qaRelay;
    private final AtomicReference<byte[]> pendingKey;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean accepting = new AtomicBoolean();
    private final Semaphore pending = new Semaphore(32);
    private final Semaphore connecting = new Semaphore(16);
    private final Set<Operation<?>> operations = ConcurrentHashMap.newKeySet();
    private final Object admission = new Object();
    private final ThreadPoolExecutor serial = new ThreadPoolExecutor(
            1, 1, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(512), task -> {
                Thread thread = new Thread(task, "Iroh-endpoint-control");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private volatile Endpoint endpoint;
    private volatile String publicKey;
    private volatile String expectedPublicKey;
    private CompletableFuture<Endpoint> binding;
    private boolean bindSettled, bindHandled;

    private IrohEndpointService(IdentityStore identity, String profileId, List<String> alpns, byte[] key, QaRelay qaRelay) {
        this.identity = identity;
        this.profileId = profileId;
        this.alpns = alpns;
        this.qaRelay = qaRelay;
        this.pendingKey = new AtomicReference<>(key);
    }

    public static CompletableFuture<IrohEndpointService> open(IdentityStore actualSharedIdentity, List<String> alpn) {
        final QaRelay qaRelay;
        try { qaRelay = parseQaRelay(System.getProperty(QA_RELAY_PROPERTY)); }
        catch (RuntimeException | IOException rejected) {
            return CompletableFuture.failedFuture(new Failure(FailureCode.SERVICE_BIND));
        }
        final List<String> selected;
        try {
            if (actualSharedIdentity == null || alpn == null || alpn.isEmpty() || alpn.size() > 8)
                throw failure("Invalid endpoint profile or ALPN list");
            selected = List.copyOf(alpn);
            for (String value : selected) validAlpn(value);
            if (Set.copyOf(selected).size() != selected.size()) throw failure("Duplicate endpoint ALPN");
        } catch (RuntimeException | IOException invalid) {
            return CompletableFuture.failedFuture(failure("Invalid endpoint profile or ALPN list"));
        }
        CompletableFuture<IrohEndpointService> result = new CompletableFuture<>();
        AtomicReference<IrohEndpointService> opening = new AtomicReference<>();
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                IrohEndpointService service = opening.get();
                if (service != null) service.close();
            }
        });
        try {
            KEY_IO.execute(() -> {
                byte[] key = null;
                try {
                    if (result.isDone()) return;
                    String owner = verifiedOwner(actualSharedIdentity);
                    if (result.isDone()) return;
                    key = readOrCreateKey(actualSharedIdentity, owner);
                    if (result.isDone()) return;
                    IrohEndpointService service = new IrohEndpointService(actualSharedIdentity, owner, selected, key, qaRelay);
                    key = null;
                    opening.set(service);
                    if (result.isDone()) service.close();
                    else if (!service.post(() -> service.bind(result))) {
                        service.close();
                        result.completeExceptionally(new Failure(FailureCode.SERVICE_BIND));
                    }
                } catch (Throwable unavailable) {
                    result.completeExceptionally(new Failure(FailureCode.KEY_STORAGE));
                } finally {
                    wipe(key);
                }
            });
        } catch (RuntimeException full) {
            result.completeExceptionally(new Failure(FailureCode.KEY_STORAGE));
        }
        return result;
    }

    /** Cached public endpoint key. No file or native call is performed here. */
    public String publicKeyHex() {
        String value = publicKey;
        if (closed.get() || value == null) throw new IllegalStateException("Endpoint is not available");
        return value;
    }

    public CompletableFuture<Void> online(Duration timeout) {
        final Operation<Void> operation;
        try { operation = begin(timeout, false, false); }
        catch (IOException invalid) { return CompletableFuture.failedFuture(invalid); }
        if (!post(() -> operation.<Unit>call(continuation -> requireEndpoint().online(continuation), value -> operation.publish(null), value -> {})))
            operation.rejectBeforeStart();
        return operation.result;
    }

    public CompletableFuture<Connection> connect(String remoteHex, String alpn, Duration timeout) {
        final Operation<Connection> operation;
        try {
            if (remoteHex == null || !remoteHex.matches("[0-9a-f]{64}")) throw failure("Invalid remote endpoint key");
            validAlpn(alpn);
            if (!alpns.contains(alpn)) throw failure("Unconfigured endpoint ALPN");
            operation = begin(timeout, true, false);
        } catch (IOException invalid) { return CompletableFuture.failedFuture(invalid); }
        if (!post(() -> operation.<Connection>call(continuation -> {
            try (EndpointId remote = EndpointId.Companion.fromBytes(HexFormat.of().parseHex(remoteHex));
                 EndpointAddr address = new EndpointAddr(remote, null, List.of())) {
                return requireEndpoint().connect(address, alpn.getBytes(StandardCharsets.US_ASCII), continuation);
            }
        }, connection -> operation.publishConnection(connection, remoteHex, alpn), IrohEndpointService::closeConnection)))
            operation.rejectBeforeStart();
        return operation.result;
    }

    /** One rejected incoming TLS peer does not mean the endpoint has died. */
    public static final class PeerRejected extends IOException {
        private PeerRejected(){super("Incoming transport handshake rejected");}
    }
    /** Native health check for transport workers; UI callers must use cached snapshots. */
    public boolean nativeAvailableOnWorker(){
        Endpoint actual=endpoint;
        try{return !closed.get()&&actual!=null&&!actual.isClosed();}catch(Throwable closedHandle){return false;}
    }
    public CompletableFuture<Connection> accept(Duration timeout) {
        final Operation<Connection> operation;
        try { operation = begin(timeout, false, true); }
        catch (IOException invalid) { return CompletableFuture.failedFuture(invalid); }
        if (!post(() -> operation.<Incoming>call(continuation -> requireEndpoint().acceptNext(continuation), incoming -> {
            if (incoming == null) { operation.fail(failure("Endpoint accept ended")); return; }
            operation.peerPhase=true;
            operation.incoming.set(incoming);
            operation.<Accepting>call(continuation -> {
                Incoming owned = operation.incoming.getAndSet(null);
                if (owned == null) throw failure("Endpoint accept was cancelled");
                try (owned) { return owned.accept(continuation); }
            }, handshake -> {
                operation.handshake.set(handshake);
                operation.<Connection>call(continuation -> {
                    Accepting owned = operation.handshake.getAndSet(null);
                    if (owned == null) throw failure("Endpoint handshake was cancelled");
                    try (owned) { return owned.connect(continuation); }
                }, connection -> operation.publishConnection(connection, null, null), IrohEndpointService::closeConnection);
            }, IrohEndpointService::closeHandle);
        }, IrohEndpointService::closeHandle))) operation.rejectBeforeStart();
        return operation.result;
    }

    private void bind(CompletableFuture<IrohEndpointService> result) {
        if (closed.get() || result.isDone()) {
            bindHandled = bindSettled = true;
            close();
            stopIfFinished();
            return;
        }
        // Keep a native-library initialization classification before the async bridge normalizes native failures.
        try { initializeLogging(); }
        catch (IOException startup) {
            bindHandled = bindSettled = true;
            result.completeExceptionally(startup);
            close();
            stopIfFinished();
            return;
        }
        binding = IrohAsync.<Endpoint>call(BIND_TIMEOUT, continuation -> {
            byte[] key = pendingKey.getAndSet(null);
            if (key == null) throw failure("Endpoint startup was cancelled");
            EndpointOptions options = null;
            try {
                if (closed.get() || !verifiedOwner(identity).equals(profileId))
                    throw failure("Endpoint profile changed");
                options = new EndpointOptions();
                options.setPreset(Iroh_ffiKt.presetN0());
                if (qaRelay != null) {
                    options.setRelayMode(RelayMode.Companion.customFromUrls(List.of(qaRelay.url)));
                    DarkWinter.LOGGER.info("Online Iroh QA home-relay selection={}",
                            qaRelay.name().toLowerCase(java.util.Locale.ROOT));
                }
                options.setSecretKey(key);
                options.setAlpns(alpns.stream().map(value -> value.getBytes(StandardCharsets.US_ASCII)).toList());
                try (SecretKey secret = SecretKey.Companion.fromBytes(key);
                     EndpointId publicPart = (EndpointId) SecretKey.class.getMethod("public").invoke(secret)) {
                    expectedPublicKey = HexFormat.of().formatHex(publicPart.toBytes());
                }
                return Endpoint.Companion.bind(options, continuation);
            } finally {
                // UniFFI has copied EndpointOptions into Rust before bind() returns its suspension marker.
                wipe(key);
                if (options != null) {
                    options.setSecretKey(null);
                    try { options.destroy(); } catch (Throwable ignored) {}
                }
            }
        }, IrohEndpointService::shutdownEndpoint);
        CompletableFuture<Endpoint> actual = binding;
        IrohAsync.settled(actual).whenComplete((ignored, error) -> post(() -> {
            bindSettled = true;
            stopIfFinished();
        }));
        actual.whenComplete((opened, error) -> {
            if (!post(() -> {
                bindHandled = true;
                if (error != null || closed.get() || result.isDone()) {
                    if (opened != null) shutdownEndpoint(opened);
                    result.completeExceptionally(new Failure(FailureCode.SERVICE_BIND,
                            error instanceof IrohAsync.Failure known ? known.code() : null));
                    close();
                } else {
                    try (EndpointId id = opened.id()) {
                        String actualKey = HexFormat.of().formatHex(id.toBytes());
                        if (!actualKey.equals(expectedPublicKey)) throw failure("Endpoint key binding failed");
                        if (!verifiedOwner(identity).equals(profileId)) throw failure("Endpoint profile changed");
                        endpoint = opened;
                        publicKey = actualKey;
                        if (!result.complete(this)) close();
                    } catch (Throwable rejected) {
                        shutdownEndpoint(opened);
                        result.completeExceptionally(new Failure(FailureCode.SERVICE_BIND));
                        close();
                    }
                }
                stopIfFinished();
            }) && opened != null) shutdownEndpoint(opened);
        });
    }

    private <T> Operation<T> begin(Duration timeout, boolean connect, boolean accept) throws IOException {
        long budget = duration(timeout);
        synchronized (admission) {
            if (closed.get() || endpoint == null) throw failure("Endpoint is closed");
            if (accept && !accepting.compareAndSet(false, true)) throw failure("An endpoint accept is already pending");
            if (connect && !connecting.tryAcquire()) throw failure("Endpoint connection limit reached");
            if (!pending.tryAcquire()) {
                if (connect) connecting.release();
                if (accept) accepting.set(false);
                throw failure("Endpoint operation limit reached");
            }
            Operation<T> operation = new Operation<>(budget, connect, accept);
            operations.add(operation);
            return operation;
        }
    }

    /** Completion of the actual operation reservation, including cancelled native calls. */
    public CompletableFuture<Void> quiesced(CompletableFuture<?> result) {
        if(result==null)return CompletableFuture.failedFuture(failure("Unknown endpoint operation"));
        for(Operation<?> operation:operations)if(operation.result==result)return operation.quiesced.copy();
        return result.isDone()?CompletableFuture.completedFuture(null):CompletableFuture.failedFuture(failure("Unknown endpoint operation"));
    }

    private final class Operation<T> {
        final CompletableFuture<T> result = new CompletableFuture<>();
        final CompletableFuture<Void> quiesced = new CompletableFuture<>();
        final long started = System.nanoTime(), budget;
        final boolean connect, accept;
        volatile boolean peerPhase;
        final Set<CompletableFuture<?>> calls = ConcurrentHashMap.newKeySet();
        final AtomicReference<Incoming> incoming = new AtomicReference<>();
        final AtomicReference<Accepting> handshake = new AtomicReference<>();
        final AtomicBoolean released = new AtomicBoolean();
        Operation(long budget, boolean connect, boolean accept) {
            this.budget = budget;
            this.connect = connect;
            this.accept = accept;
            result.whenComplete((value, error) -> post(() -> {
                for (CompletableFuture<?> call : calls) if (!call.isDone()) call.cancel(true);
                closeHandle(incoming.getAndSet(null));
                closeHandle(handshake.getAndSet(null));
                releaseIfFinished();
            }));
        }
        <R> void call(IrohAsync.Suspend<R> suspend, Consumer<R> success, Consumer<R> discard) {
            if (closed.get() || result.isDone()) { fail(failure("Endpoint operation cancelled")); return; }
            long remaining = budget - (System.nanoTime() - started);
            if (remaining < TimeUnit.MILLISECONDS.toNanos(1)) { fail(failure("Endpoint operation timed out")); return; }
            CompletableFuture<R> future = IrohAsync.call(Duration.ofNanos(remaining), suspend, discard);
            calls.add(future);
            IrohAsync.settled(future).whenComplete((ignored, error) -> post(() -> {
                calls.remove(future);
                releaseIfFinished();
            }));
            future.whenComplete((value, error) -> {
                Runnable completed = () -> {
                    if (error != null) fail(error);
                    else if (closed.get() || result.isDone()) {
                        if (value != null) discard.accept(value);
                        fail(failure("Endpoint operation cancelled"));
                    } else {
                        try { success.accept(value); }
                        catch (Throwable invalid) {
                            if (value != null) discard.accept(value);
                            fail(failure("Endpoint result validation failed"));
                        }
                    }
                    releaseIfFinished();
                };
                if (!post(completed) && value != null) discard.accept(value);
            });
        }
        void publish(T value) {
            if (closed.get()) fail(failure("Endpoint is closed"));
            else result.complete(value);
        }
        @SuppressWarnings("unchecked")
        void publishConnection(Connection connection, String remoteHex, String alpn) {
            boolean transferred = false;
            try {
                if (connection == null || closed.get() || result.isDone()) throw failure("Endpoint connection cancelled");
                byte[] negotiated = connection.alpn();
                if (alpn != null && !Arrays.equals(negotiated, alpn.getBytes(StandardCharsets.US_ASCII))
                        || alpn == null && alpns.stream().noneMatch(value -> Arrays.equals(negotiated, value.getBytes(StandardCharsets.US_ASCII))))
                    throw failure("Endpoint negotiated an unexpected ALPN");
                if (remoteHex != null) try (EndpointId remote = connection.remoteId()) {
                    if (!remoteHex.equals(HexFormat.of().formatHex(remote.toBytes())))
                        throw failure("Endpoint peer key differs from its requested key");
                }
                if (closed.get()) throw failure("Endpoint is closed");
                transferred = result.complete((T) connection);
            } catch (Throwable invalid) {
                fail(failure("Endpoint connection validation failed"));
            } finally {
                if (!transferred) closeConnection(connection);
            }
        }
        void fail(Throwable failure) {
            if(accept&&peerPhase&&!closed.get()&&!(failure instanceof IrohAsync.Failure known
                    &&(known.code()==IrohAsync.Code.TIMEOUT||known.code()==IrohAsync.Code.CANCELLED)))
                result.completeExceptionally(new PeerRejected());
            else result.completeExceptionally(failure);
        }
        void rejectBeforeStart() {
            result.completeExceptionally(failure("Endpoint worker is closed"));
            release();
        }
        void releaseIfFinished() {
            // A cancelled/timed-out native accept retains its reservation until real UniFFI completion.
            if (result.isDone() && calls.isEmpty()) release();
        }
        void release() {
            if (!released.compareAndSet(false, true)) return;
            pending.release();
            if (connect) connecting.release();
            if (accept) accepting.set(false);
            quiesced.complete(null);
            operations.remove(this);
            stopIfFinished();
        }
    }

    @Override public void close() {
        synchronized (admission) {
            if (!closed.compareAndSet(false, true)) return;
        }
        wipe(pendingKey.getAndSet(null));
        post(() -> {
            if (binding != null && !binding.isDone()) binding.cancel(true);
            if (binding == null) bindHandled = bindSettled = true;
            for (Operation<?> operation : operations)
                operation.result.completeExceptionally(failure("Endpoint is closed"));
            Endpoint owned = endpoint;
            endpoint = null;
            if (owned != null) shutdownEndpoint(owned);
            stopIfFinished();
        });
    }

    private Endpoint requireEndpoint() throws IOException {
        Endpoint actual = endpoint;
        if (closed.get() || actual == null) throw failure("Endpoint is closed");
        return actual;
    }
    private void stopIfFinished() {
        if (closed.get() && bindHandled && bindSettled && operations.isEmpty()) serial.shutdown();
    }
    private boolean post(Runnable task) {
        try { serial.execute(task); return true; }
        catch (RuntimeException unavailable) { return false; }
    }
    private static String verifiedOwner(IdentityStore identity) throws IOException {
        try { return IdentityStore.validateProfileProof(identity.publicProof()).id(); }
        catch (RuntimeException closed) { throw failure("Shared endpoint profile is unavailable"); }
    }
    private static byte[] readOrCreateKey(IdentityStore identity, String owner) throws IOException {
        // This method is called only by KEY_IO, before any endpoint/native initialization.
        Path directory = ProfileFiles.directory(identity.base().resolve("iroh"));
        Path file = directory.resolve("endpoint-" + owner + ".json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] generated = IdentityStore.randomBytes(32);
            try {
                JsonObject document = new JsonObject();
                document.addProperty("schema", 1);
                document.addProperty("owner", owner);
                document.addProperty("key", Base64.getUrlEncoder().withoutPadding().encodeToString(generated));
                try { ProfileFiles.atomic(file, document, false); }
                catch (FileAlreadyExistsException concurrent) { /* Read and verify the committed file below. */ }
            } finally { wipe(generated); }
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw failure("Endpoint key file is not a regular private document");
        ProfileFiles.protect(file, false);
        JsonObject document = ProfileFiles.read(file, 1024);
        ProfileFiles.fields(document, Set.of("schema", "owner", "key"), Set.of());
        if (ProfileFiles.number(document, "schema") != 1
                || !ProfileFiles.string(document, "owner", 64).equals(owner))
            throw failure("Endpoint key belongs to a different profile or schema");
        byte[] key = IdentityStore.decode(ProfileFiles.string(document, "key", 43), 32);
        if (key.length != 32) { wipe(key); throw failure("Invalid endpoint key size"); }
        return key;
    }
    private static long duration(Duration timeout) throws IOException {
        if (timeout == null || timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofMinutes(2)) > 0)
            throw failure("Endpoint timeout must be between one millisecond and two minutes");
        return timeout.toNanos();
    }
    private static void validAlpn(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,63}"))
            throw failure("Invalid endpoint ALPN");
    }
    private static void shutdownEndpoint(Endpoint endpoint) {
        if (endpoint == null) return;
        CompletableFuture<Unit> stopping=IrohAsync.<Unit>cleanup(CLOSE_TIMEOUT, endpoint::shutdown);
        IrohAsync.settled(stopping).whenComplete((ignored,failure)->closeHandle(endpoint));
    }
    private static void closeConnection(Connection connection) {
        if (connection == null) return;
        try { connection.close(0, new byte[0]); } catch (Throwable ignored) {}
        closeHandle(connection);
    }
    private static void closeHandle(AutoCloseable handle) {
        if (handle != null) try { handle.close(); } catch (Throwable ignored) {}
    }
    private static void wipe(byte[] key) { if (key != null) Arrays.fill(key, (byte) 0); }
    private static IOException failure(String message) { return new IOException(message); }
    @Override public String toString() { return "IrohEndpointService"; }
}
