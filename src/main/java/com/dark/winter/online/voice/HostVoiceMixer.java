// Mixes admitted voice streams at the authoritative host using immutable world snapshots.
package com.dark.winter.online.voice;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Host-authoritative STAR audio: input goes to the host; each listener receives only its authorized mix.
 * Native world objects must never enter this worker. The server supplies immutable snapshots on its owning thread.
 */
public final class HostVoiceMixer implements AutoCloseable {
    public record WorldSnapshot(UUID sessionId, long epoch, long revision, long capturedAtNanos,
                                VoiceRoutePolicy.Settings settings, Map<String, VoiceRoutePolicy.Member> members,
                                Map<VoiceRoutePolicy.Pair, VoiceRoutePolicy.Acoustics> acoustics) {
        public WorldSnapshot {
            Objects.requireNonNull(sessionId); Objects.requireNonNull(settings);
            if (epoch < 0 || revision < 0 || members.size() > 16 || acoustics.size() > 240)
                throw new IllegalArgumentException("Voice snapshot bounds");
            members = Map.copyOf(members); acoustics = Map.copyOf(acoustics);
            members.forEach((id, member) -> { if (!id.equals(member.id())) throw new IllegalArgumentException("Voice member key"); });
            Map<String, VoiceRoutePolicy.Member> captured = members;
            acoustics.keySet().forEach(pair -> {
                if (!captured.containsKey(pair.source()) || !captured.containsKey(pair.listener()))
                    throw new IllegalArgumentException("Acoustic pair is outside this snapshot");
            });
        }
    }
    public interface AudioPort extends AutoCloseable {
        Optional<VoicePeer.DecodedAudio> pollInput();
        boolean offerMix(byte[] monoPcm10ms);
        default CompletableFuture<Void> terminated() { return CompletableFuture.completedFuture(null); }
        @Override void close();
    }
    /** Local microphone input is supplied only by explicit JavaSound capture; no device is opened here. */
    public static final class LocalPort implements AudioPort {
        private final ArrayBlockingQueue<byte[]> input = new ArrayBlockingQueue<>(8);
        private final Consumer<byte[]> output;
        private final AtomicBoolean closed = new AtomicBoolean();
        public LocalPort(Consumer<byte[]> output) { this.output = Objects.requireNonNull(output); }
        public boolean offerInput(byte[] pcm10ms) {
            Objects.requireNonNull(pcm10ms);
            if (pcm10ms.length != VoicePeer.FRAME_BYTES) throw new IllegalArgumentException("Local voice frame");
            if (closed.get()) return false;
            byte[] copy = pcm10ms.clone();
            while (!input.offer(copy)) input.poll();
            if (closed.get()) { input.remove(copy); return false; }
            return true;
        }
        @Override public Optional<VoicePeer.DecodedAudio> pollInput() {
            byte[] pcm = input.poll();
            return pcm == null ? Optional.empty() : Optional.of(new VoicePeer.DecodedAudio(pcm, 48000, 1, 480));
        }
        @Override public boolean offerMix(byte[] pcm10ms) {
            if (closed.get()) return false; output.accept(pcm10ms.clone()); return true;
        }
        @Override public void close() { closed.set(true); input.clear(); }
    }
    public record Route(String sourceId, String listenerId, VoiceRoutePolicy.Mode mode, double gain, boolean speaking) {}
    public record View(String status, long revision, long ticks, long outputFrames, long droppedInput,
                       long droppedOutput, long staleTicks, long lastTickMicros, long maximumTickMicros,
                       Set<String> speaking, List<Route> routes, String error) {
        public View { speaking = Set.copyOf(speaking); routes = List.copyOf(routes); }
    }
    private static final class Registration {
        final String id; final AudioPort port;
        final AtomicBoolean active = new AtomicBoolean(true);
        final ArrayDeque<byte[]> frames = new ArrayDeque<>();
        long lastSpeech;
        Registration(String id, AudioPort port) { this.id = id; this.port = port; }
    }
    private final UUID sessionId;
    private final long epoch, staleNanos;
    private final LongSupplier clock;
    private final ScheduledThreadPoolExecutor worker;
    private final ConcurrentMap<String, Registration> ports = new ConcurrentHashMap<>();
    private final AtomicReference<WorldSnapshot> policy = new AtomicReference<>();
    private final AtomicBoolean started = new AtomicBoolean(), closed = new AtomicBoolean();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private long ticks, outputFrames, droppedInput, droppedOutput, staleTicks, maximumTickMicros;
    private volatile View view = new View("IDLE", -1, 0, 0, 0, 0, 0, 0, 0, Set.of(), List.of(), "");

    /** Construction is inert. start() begins a worker, never a microphone. */
    public HostVoiceMixer(UUID sessionId, long epoch) { this(sessionId, epoch, Duration.ofMillis(250), System::nanoTime); }
    HostVoiceMixer(UUID sessionId, long epoch, Duration staleAfter, LongSupplier clock) {
        this.sessionId = Objects.requireNonNull(sessionId); this.clock = Objects.requireNonNull(clock); this.epoch = epoch;
        if (epoch < 0 || staleAfter.compareTo(Duration.ofMillis(50)) < 0 || staleAfter.compareTo(Duration.ofSeconds(2)) > 0)
            throw new IllegalArgumentException("Voice snapshot lifetime");
        staleNanos = staleAfter.toNanos();
        worker = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "DarkWinter-host-voice"); thread.setDaemon(true); return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        worker.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }
    public void start() {
        if (closed.get()) throw new IllegalStateException("Voice mixer closed");
        if (started.compareAndSet(false, true)) worker.scheduleWithFixedDelay(this::safeTick, 0, 10, TimeUnit.MILLISECONDS);
    }
    /** Only the native authority may provide this snapshot; client coordinates/permissions are never accepted. */
    public synchronized void update(WorldSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        if (closed.get() || !snapshot.sessionId().equals(sessionId) || snapshot.epoch() != epoch)
            throw new IllegalArgumentException("Voice snapshot belongs to another/closed host");
        WorldSnapshot prior = policy.get();
        if (prior != null && (snapshot.revision() <= prior.revision() || snapshot.capturedAtNanos() < prior.capturedAtNanos()))
            throw new IllegalArgumentException("Stale voice policy revision");
        policy.set(snapshot);
    }
    /** Transfers this port's lifecycle to the mixer. Call only after the peer's identity/admission is verified. */
    public synchronized void register(String verifiedId, AudioPort port) throws IOException {
        VoiceRoutePolicy.requireId(verifiedId); Objects.requireNonNull(port);
        if (closed.get()) throw new IOException("Voice mixer closed");
        if (ports.size() >= 16 || ports.containsKey(verifiedId) || ports.values().stream().anyMatch(registered -> registered.port == port))
            throw new IOException("Duplicate/full voice participant");
        ports.put(verifiedId, new Registration(verifiedId, port));
    }
    /** Caller has already authenticated the voice SDP/fingerprint and tied this peer to verifiedId. */
    public CompletableFuture<Void> attach(String verifiedId, VoicePeer peer) {
        Objects.requireNonNull(peer); VoiceRoutePolicy.requireId(verifiedId);
        return peer.setTransmit(true).thenCompose(ignored -> {
            try {
                register(verifiedId, new AudioPort() {
                    @Override public Optional<VoicePeer.DecodedAudio> pollInput() { return peer.pollDecoded(); }
                    @Override public boolean offerMix(byte[] pcm) { return peer.pushPcm10ms(pcm); }
                    @Override public CompletableFuture<Void> terminated() { return peer.closed(); }
                    @Override public void close() { peer.close(); }
                });
                return CompletableFuture.completedFuture(null);
            } catch (IOException failure) { peer.close(); return CompletableFuture.failedFuture(failure); }
        });
    }
    public void unregister(String verifiedId) {
        Registration registered = ports.remove(verifiedId);
        if (registered != null) synchronized (registered) {
            registered.active.set(false); registered.frames.clear(); registered.port.close();
        }
    }
    /** Full host diagnostic view. Remote clients must receive routesFor(theirAuthenticatedId), not every route. */
    public View view() { return view; }
    public List<Route> routesFor(String authenticatedId) {
        VoiceRoutePolicy.requireId(authenticatedId);
        return view.routes().stream().filter(route -> route.listenerId().equals(authenticatedId)).toList();
    }
    private boolean fresh(WorldSnapshot snapshot, long now) {
        long age = now - snapshot.capturedAtNanos();
        return age >= 0 && age <= staleNanos;
    }
    private void safeTick() {
        try { tickOnce(); }
        catch (Throwable failure) {
            view = new View("FAILED", view.revision(), ticks, outputFrames, droppedInput, droppedOutput,
                    staleTicks, 0, maximumTickMicros, Set.of(), List.of(), failure.getClass().getSimpleName());
            close();
        }
    }
    /** Package-private deterministic test entry; production uses only the single scheduled worker. */
    void tickOnce() {
        if (closed.get()) return;
        long began = System.nanoTime(), now = clock.getAsLong();
        WorldSnapshot snapshot = policy.get();
        List<Registration> participants = List.copyOf(ports.values());
        Map<String, float[]> input = new HashMap<>();
        Set<String> speaking = new HashSet<>();
        boolean usable = snapshot != null && fresh(snapshot, now);
        for (Registration registered : participants) {
            synchronized (registered) {
                if (!registered.active.get()) continue;
                // Keep only a short real-time tail, never unbounded decoded/native backlog.
                ArrayDeque<VoicePeer.DecodedAudio> tail = new ArrayDeque<>();
                for (int count = 0; count < 256; count++) {
                    Optional<VoicePeer.DecodedAudio> next = registered.port.pollInput();
                    if (next.isEmpty()) break;
                    if (tail.size() == 4) { tail.removeFirst(); droppedInput++; }
                    tail.add(next.orElseThrow());
                }
                VoiceRoutePolicy.Member author = usable ? snapshot.members().get(registered.id) : null;
                if (author == null || !author.active() || !author.canSpeak()) {
                    droppedInput += tail.size() + registered.frames.size(); registered.frames.clear(); continue;
                }
                for (VoicePeer.DecodedAudio decoded : tail) {
                    float[] converted = VoicePcm.mono48k(decoded);
                    if (converted.length > 960) throw new IllegalArgumentException("Voice decoder chunk exceeds20ms");
                    for (int offset = 0; offset < converted.length; offset += 480) {
                        float[] frame = new float[480];
                        System.arraycopy(converted, offset, frame, 0, Math.min(480, converted.length - offset));
                        if (registered.frames.size() == 4) { registered.frames.removeFirst(); droppedInput++; }
                        registered.frames.add(VoicePcm.pcm16(frame, 1));
                    }
                }
                byte[] pcm = registered.frames.pollFirst();
                if (pcm != null) {
                    input.put(registered.id, VoicePcm.mono10ms(pcm));
                    if (VoicePcm.level(pcm) > 0.015) registered.lastSpeech = now;
                }
                if (registered.lastSpeech != 0 && now - registered.lastSpeech <= TimeUnit.MILLISECONDS.toNanos(300))
                    speaking.add(registered.id);
            }
        }
        List<Route> routes = new ArrayList<>();
        for (Registration listener : participants) synchronized (listener) {
            if (!listener.active.get()) continue;
            float[] mix = new float[480];
            VoiceRoutePolicy.Member target = usable ? snapshot.members().get(listener.id) : null;
            if (target != null) {
                for (Registration source : participants) {
                    if (!source.active.get()) continue;
                    VoiceRoutePolicy.Member author = snapshot.members().get(source.id);
                    if (author == null) continue;
                    VoiceRoutePolicy.Decision decision = VoiceRoutePolicy.evaluate(snapshot.settings(), author, target, snapshot.acoustics());
                    if (!decision.allowed()) continue;
                    boolean talking = speaking.contains(source.id);
                    routes.add(new Route(source.id, listener.id, author.mode(), decision.gain(), talking));
                    float[] signal = input.get(source.id);
                    if (signal != null) for (int i = 0; i < 480; i++) mix[i] += (float) (signal[i] * decision.gain());
                }
            }
            // A rights/session update during mixing invalidates the whole computed frame.
            if (snapshot != policy.get() || snapshot != null && !fresh(snapshot, clock.getAsLong())) Arrays.fill(mix, 0);
            if (listener.port.offerMix(VoicePcm.pcm16(mix, 1))) outputFrames++; else droppedOutput++;
        }
        if (!usable) { staleTicks++; speaking.clear(); routes.clear(); }
        if (snapshot != policy.get()) { speaking.clear(); routes.clear(); }
        ticks++;
        long elapsed = (System.nanoTime() - began) / 1000; maximumTickMicros = Math.max(maximumTickMicros, elapsed);
        view = new View(usable ? "ACTIVE" : "WAITING_AUTHORITY", snapshot == null ? -1 : snapshot.revision(),
                ticks, outputFrames, droppedInput, droppedOutput, staleTicks, elapsed, maximumTickMicros, speaking, routes, "");
    }
    public CompletableFuture<Void> terminated() { return terminated.copy(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.shutdownNow(); policy.set(null);
        List<CompletableFuture<Void>> closing = new ArrayList<>();
        for (Registration registered : List.copyOf(ports.values())) synchronized (registered) {
            registered.active.set(false); registered.frames.clear(); registered.port.close(); closing.add(registered.port.terminated());
        }
        ports.clear();
        View previous = view;
        view = new View(previous.status().equals("FAILED") ? "FAILED" : "CLOSED", previous.revision(),
                ticks, outputFrames, droppedInput, droppedOutput, staleTicks, previous.lastTickMicros(),
                maximumTickMicros, Set.of(), List.of(), previous.error());
        CompletableFuture.allOf(closing.toArray(CompletableFuture[]::new)).orTimeout(10, TimeUnit.SECONDS)
                .whenComplete((ignored, error) -> { if (error == null) terminated.complete(null); else terminated.completeExceptionally(error); });
    }
}
