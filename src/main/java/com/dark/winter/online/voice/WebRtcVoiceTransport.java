// Explicit, signaling-independent audio transport.
package com.dark.winter.online.voice;

import com.dark.winter.online.transport.WebRtcTransport;
import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Explicit, signaling-independent audio transport. Construction is inert.
 * Only dummy audio devices are used: this class never enumerates or opens OS microphones/speakers.
 * Applications must authenticate SDP/fingerprints and candidates before passing them to a peer.
 */
public final class WebRtcVoiceTransport implements AutoCloseable {
    public record Config(WebRtcTransport.Config network, int inputQueueFrames, int decodedQueueFrames) {
        public Config {
            Objects.requireNonNull(network);
            if (inputQueueFrames < 1 || inputQueueFrames > 32 || decodedQueueFrames < 1 || decodedQueueFrames > 256)
                throw new IllegalArgumentException("Voice queue bounds");
        }
        public static Config localOnly() { return new Config(WebRtcTransport.Config.localOnly(), 8, 64); }
    }
    private volatile Config config;
    private final ScheduledThreadPoolExecutor loop;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean overloaded = new AtomicBoolean();
    private final Map<String, VoicePeer> peers = new HashMap<>();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private PeerConnectionFactory factory;
    private HeadlessAudioDeviceModule headless;

    public WebRtcVoiceTransport(Config config) {
        this.config = Objects.requireNonNull(config);
        loop = new ScheduledThreadPoolExecutor(1, work -> {
            Thread thread = new Thread(work, "DarkWinter-voice");
            thread.setDaemon(true); return thread;
        });
        loop.setRemoveOnCancelPolicy(true);
        loop.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        loop.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }
    public Config config() { return config; }
    public void refreshIceServers(List<WebRtcTransport.IceServer> servers){if(closed.get())throw new IllegalStateException("Voice transport closed");Config old=config;config=new Config(old.network().withIceServers(servers),old.inputQueueFrames(),old.decodedQueueFrames());}
    public CompletableFuture<VoicePeer> createPeer(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}"))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Voice peer ID"));
        var network = config.network();
        if (network.policy() == WebRtcTransport.IcePolicy.RELAY_ONLY
                && network.iceServers().stream().flatMap(server -> server.urls().stream())
                .noneMatch(url -> url.startsWith("turn:") || url.startsWith("turns:")))
            return CompletableFuture.failedFuture(error(WebRtcTransport.Code.NO_RELAY_CONFIGURED,
                    "Voice relay-only route requires configured TURN"));
        CompletableFuture<VoicePeer> result = new CompletableFuture<>();
        dispatch(() -> {
            if (peers.containsKey(id) || peers.size() >= network.maxPeers()) {
                result.completeExceptionally(error(WebRtcTransport.Code.CAPACITY, "Voice peer capacity")); return;
            }
            try {
                if (factory == null) {
                    headless = new HeadlessAudioDeviceModule();
                    try {
                        factory = new PeerConnectionFactory(headless);
                        // Drives decoding/sinks only; this ADM never opens OS audio devices.
                        headless.initPlayout(); headless.startPlayout();
                    }
                    catch (Throwable initialization) {
                        try { if (factory != null) factory.dispose(); }
                        finally { try { headless.dispose(); } finally { headless = null; factory = null; } }
                        throw initialization;
                    }
                    loop.scheduleAtFixedRate(this::tick, 10, 10, TimeUnit.MILLISECONDS);
                }
                VoicePeer peer = new VoicePeer(this, id, factory, nativeConfiguration());
                peers.put(id, peer); result.complete(peer);
            } catch (Throwable failure) {
                result.completeExceptionally(error(WebRtcTransport.Code.NATIVE_FAILURE,
                        "Voice native initialization failed: " + failure.getClass().getSimpleName()));
            }
        }, result);
        return result.copy();
    }
    private RTCConfiguration nativeConfiguration() {
        RTCConfiguration rtc = new RTCConfiguration();
        rtc.iceTransportPolicy = config.network().policy() == WebRtcTransport.IcePolicy.RELAY_ONLY
                ? RTCIceTransportPolicy.RELAY : RTCIceTransportPolicy.ALL;
        // Preserve relay-only policy while allowing the OS route to an explicitly configured local TURN fixture.
        if (config.network().iceServers().stream().flatMap(server -> server.urls().stream())
                .anyMatch(url -> url.matches("turns?:127\\.0\\.0\\.1(?::[0-9]{1,5})?(?:\\?transport=(?:udp|tcp))?")))
            rtc.portAllocatorConfig.setEnableAnyAddressPorts(true);
        for (WebRtcTransport.IceServer configured : config.network().iceServers()) {
            RTCIceServer server = new RTCIceServer();
            server.urls.addAll(configured.urls()); server.username = configured.username(); server.password = configured.credential();
            rtc.iceServers.add(server);
        }
        return rtc;
    }
    void dispatch(Runnable task, CompletableFuture<?> failureTarget) { dispatch(task, failureTarget, () -> {}); }
    void dispatch(Runnable task, CompletableFuture<?> failureTarget, Runnable rejectedCleanup) {
        if (closed.get()) { reject(failureTarget, rejectedCleanup); return; }
        if (queued.incrementAndGet() > 1024) {
            queued.decrementAndGet(); overloaded.set(true); reject(failureTarget, rejectedCleanup); return;
        }
        try {
            loop.execute(() -> {
                queued.decrementAndGet();
                if (closed.get()) { reject(failureTarget, rejectedCleanup); return; }
                try { task.run(); }
                catch (Throwable failure) {
                    if (failureTarget != null) failureTarget.completeExceptionally(error(WebRtcTransport.Code.NATIVE_FAILURE,
                            "Voice worker failed: " + failure.getClass().getSimpleName()));
                    else overloaded.set(true);
                }
            });
        } catch (RejectedExecutionException stopped) { queued.decrementAndGet(); reject(failureTarget, rejectedCleanup); }
    }
    private static void reject(CompletableFuture<?> target, Runnable cleanup) {
        try { cleanup.run(); } finally {
            if (target != null) target.completeExceptionally(error(WebRtcTransport.Code.CLOSED, "Voice transport unavailable"));
        }
    }
    private void tick() {
        if (closed.get()) return;
        long now = System.nanoTime();
        boolean overflow = overloaded.getAndSet(false);
        for (VoicePeer peer : List.copyOf(peers.values())) {
            try {
                if (overflow) peer.fail(error(WebRtcTransport.Code.CAPACITY, "Voice callback queue limit"));
                else peer.tick(now);
            } catch (Throwable failure) {
                peer.fail(error(WebRtcTransport.Code.NATIVE_FAILURE, "Voice native worker failure: " + failure.getClass().getSimpleName()));
            }
        }
    }
    void removed(VoicePeer peer) { peers.remove(peer.id(), peer); }
    static WebRtcTransport.TransportException error(WebRtcTransport.Code code, String message) {
        return new WebRtcTransport.TransportException(code, message);
    }
    /** Completes after peer/factory native resources are released. Never join on render/game threads. */
    public CompletableFuture<Void> terminated() { return terminated.copy(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            loop.execute(() -> {
                Throwable failure = null;
                for (VoicePeer peer : List.copyOf(peers.values())) {
                    try { peer.fail(error(WebRtcTransport.Code.CLOSED, "Voice transport closed")); }
                    catch (Throwable ex) { failure = ex; }
                }
                try { if (headless != null) headless.stopPlayout(); } catch (Throwable ex) { failure = ex; }
                try { if (factory != null) factory.dispose(); } catch (Throwable ex) { failure = ex; }
                try { if (headless != null) headless.dispose(); } catch (Throwable ex) { failure = ex; }
                factory = null; headless = null; peers.clear(); loop.shutdown();
                if (failure == null) terminated.complete(null); else terminated.completeExceptionally(failure);
            });
        } catch (RejectedExecutionException failure) { terminated.completeExceptionally(failure); }
    }
}
