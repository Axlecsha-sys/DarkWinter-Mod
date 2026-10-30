// Explicitly opened, signaling-independent WebRTC transport.
package com.dark.winter.online.transport;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCIceTransportPolicy;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Explicitly opened, signaling-independent WebRTC transport.
 * Constructing this class does not initialize JNI, open network sockets or touch audio devices.
 * SDP and candidates MUST be authenticated by the caller before supplying them to a link.
 */
public final class WebRtcTransport implements AutoCloseable {
    public enum IcePolicy { ALL, RELAY_ONLY }
    public enum Code { CLOSED, CAPACITY, NO_RELAY_CONFIGURED, NEGOTIATION, TIMEOUT, PROTOCOL, NATIVE_FAILURE }
    public static final class TransportException extends IOException {
        private final Code code;
        public TransportException(Code code, String message) { super(message); this.code = code; }
        public Code code() { return code; }
    }

    /** Credentials are deliberately omitted from toString/log output. */
    public record IceServer(List<String> urls, String username, String credential) {
        public IceServer {
            urls = List.copyOf(Objects.requireNonNull(urls));
            if (urls.isEmpty() || urls.size() > 8) throw new IllegalArgumentException("ICE URL count");
            for (String value : urls) {
                if (value == null || value.length() > 2048 || value.chars().anyMatch(Character::isWhitespace))
                    throw new IllegalArgumentException("Invalid ICE URL");
                URI uri = URI.create(value);
                if (!Set.of("stun", "stuns", "turn", "turns").contains(uri.getScheme())
                        || uri.getRawFragment() != null || uri.getRawSchemeSpecificPart().isBlank()
                        || uri.getRawSchemeSpecificPart().contains("@"))
                    throw new IllegalArgumentException("Only explicit STUN/TURN URLs are accepted");
            }
            username = username == null ? "" : username;
            credential = credential == null ? "" : credential;
            if (username.length() > 512 || credential.length() > 2048)
                throw new IllegalArgumentException("ICE credential length");
        }
        boolean turn() { return urls.stream().anyMatch(u -> u.startsWith("turn:") || u.startsWith("turns:")); }
        @Override public String toString() { return "IceServer[urls=" + urls.size() + ", credentials=redacted]"; }
    }

    public record Config(List<IceServer> iceServers, IcePolicy policy, Duration connectTimeout,
                         Duration idleWriteTimeout, int maxPeers, int frameBytes,
                         int receiveWindowBytes, int maxQueuedWriteBytes, int nativeHighWaterBytes) {
        public Config {
            iceServers = List.copyOf(Objects.requireNonNull(iceServers));
            Objects.requireNonNull(policy); Objects.requireNonNull(connectTimeout); Objects.requireNonNull(idleWriteTimeout);
            if (iceServers.size() > 8 || maxPeers < 1 || maxPeers > 16
                    || frameBytes < 1024 || frameBytes > 16 * 1024
                    || receiveWindowBytes < frameBytes || receiveWindowBytes > 4 * 1024 * 1024
                    || maxQueuedWriteBytes < frameBytes || maxQueuedWriteBytes > 16 * 1024 * 1024
                    || nativeHighWaterBytes < frameBytes || nativeHighWaterBytes > 4 * 1024 * 1024
                    || connectTimeout.compareTo(Duration.ofSeconds(1)) < 0 || connectTimeout.compareTo(Duration.ofMinutes(2)) > 0
                    || idleWriteTimeout.compareTo(Duration.ofSeconds(1)) < 0 || idleWriteTimeout.compareTo(Duration.ofMinutes(2)) > 0)
                throw new IllegalArgumentException("Transport bounds");
        }
        public Config withIceServers(List<IceServer> updated){return new Config(updated,policy,connectTimeout,idleWriteTimeout,maxPeers,frameBytes,receiveWindowBytes,maxQueuedWriteBytes,nativeHighWaterBytes);}
        public static Config localOnly() {
            return new Config(List.of(), IcePolicy.ALL, Duration.ofSeconds(20), Duration.ofSeconds(30),
                    16, 16 * 1024, 256 * 1024, 4 * 1024 * 1024, 256 * 1024);
        }
        @Override public String toString() {
            return "Config[iceServers=" + iceServers.size() + ",policy=" + policy + ",maxPeers=" + maxPeers + "]";
        }
    }

    private volatile Config config;
    private final ScheduledThreadPoolExecutor loop;
    private final Map<String, WebRtcLink> links = new HashMap<>();
    private final AtomicInteger queuedTasks = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final AtomicBoolean overloaded = new AtomicBoolean();
    private PeerConnectionFactory factory;
    private HeadlessAudioDeviceModule silentAudio;
    private volatile Thread ownerThread;

    public WebRtcTransport(Config config) {
        this.config = Objects.requireNonNull(config);
        loop = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "DarkWinter-WebRTC");
            thread.setDaemon(true); ownerThread = thread; return thread;
        });
        loop.setRemoveOnCancelPolicy(true);
        loop.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        loop.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        // Starting the worker is also lazy: first createLink starts it.
    }

    public Config config() { return config; }
    /** Credentials for subsequently created peers; existing streams and their authority are preserved. */
    public void refreshIceServers(List<IceServer> servers){if(closed.get())throw new IllegalStateException("Transport closed");config=config.withIceServers(servers);}

    public CompletableFuture<WebRtcLink> createLink(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}"))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Link ID"));
        if (config.policy() == IcePolicy.RELAY_ONLY && config.iceServers().stream().noneMatch(IceServer::turn))
            return CompletableFuture.failedFuture(error(Code.NO_RELAY_CONFIGURED, "Relay-only connection requires a configured TURN server"));
        CompletableFuture<WebRtcLink> result = new CompletableFuture<>();
        dispatch(() -> {
            if (links.containsKey(id) || links.size() >= config.maxPeers()) {
                result.completeExceptionally(error(Code.CAPACITY, "Peer limit or duplicate link ID")); return;
            }
            try {
                if (factory == null) {
                    silentAudio = new HeadlessAudioDeviceModule();
                    factory = new PeerConnectionFactory(silentAudio);
                    loop.scheduleWithFixedDelay(this::tick, 10, 10, TimeUnit.MILLISECONDS);
                }
                WebRtcLink link = new WebRtcLink(this, id, factory, nativeConfiguration());
                links.put(id, link);
                result.complete(link);
            } catch (Throwable failure) {
                result.completeExceptionally(error(Code.NATIVE_FAILURE, "WebRTC native initialization failed: " + failure.getClass().getSimpleName()));
            }
        }, result);
        return result;
    }

    private RTCConfiguration nativeConfiguration() {
        RTCConfiguration rtc = new RTCConfiguration();
        rtc.iceTransportPolicy = config.policy() == IcePolicy.RELAY_ONLY ? RTCIceTransportPolicy.RELAY : RTCIceTransportPolicy.ALL;
        // Enumerated NIC-bound UDP sockets cannot reliably reach an explicitly owned loopback TURN on Windows.
        // Also allocate an OS-routed ANY socket for this literal endpoint only. RELAY filtering remains unchanged.
        if (config.iceServers().stream().flatMap(server -> server.urls().stream())
                .anyMatch(url -> url.matches("turns?:127\\.0\\.0\\.1(?::[0-9]{1,5})?(?:\\?transport=(?:udp|tcp))?")))
            rtc.portAllocatorConfig.setEnableAnyAddressPorts(true);
        // No implicit public servers, hostnames, probes or signaling endpoints.
        for (IceServer source : config.iceServers()) {
            RTCIceServer server = new RTCIceServer();
            server.urls.addAll(source.urls()); server.username = source.username(); server.password = source.credential();
            rtc.iceServers.add(server);
        }
        return rtc;
    }

    void dispatch(Runnable task, CompletableFuture<?> failureTarget) {
        if (closed.get()) {
            if (failureTarget != null) failureTarget.completeExceptionally(error(Code.CLOSED, "Transport is closed"));
            return;
        }
        if (queuedTasks.incrementAndGet() > 1024) {
            queuedTasks.decrementAndGet(); overloaded.set(true);
            if (failureTarget != null) failureTarget.completeExceptionally(error(Code.CAPACITY, "Transport event queue limit"));
            return;
        }
        try {
            loop.execute(() -> {
                queuedTasks.decrementAndGet();
                if (closed.get()) {
                    if (failureTarget != null) failureTarget.completeExceptionally(error(Code.CLOSED, "Transport is closed"));
                    return;
                }
                try { task.run(); }
                catch (Throwable failure) {
                    if (failureTarget != null) failureTarget.completeExceptionally(error(Code.NATIVE_FAILURE, "Transport operation failed: " + failure.getClass().getSimpleName()));
                }
            });
        } catch (RejectedExecutionException failure) {
            queuedTasks.decrementAndGet();
            if (failureTarget != null) failureTarget.completeExceptionally(error(Code.CLOSED, "Transport is closed"));
        }
    }

    private void tick() {
        try {
            if (overloaded.getAndSet(false)) {
                for (WebRtcLink link : List.copyOf(links.values())) link.fail(error(Code.CAPACITY, "Transport callback queue overflow"));
            }
            long now = System.nanoTime();
            for (WebRtcLink link : List.copyOf(links.values())) {
                try { link.tick(now); }
                catch (Throwable failure) { link.fail(error(Code.NATIVE_FAILURE, "WebRTC worker failure: " + failure.getClass().getSimpleName())); }
            }
        } catch (Throwable ignored) {
            // A periodic task must remain alive to release peers after errors.
        }
    }

    void removed(WebRtcLink link) { links.remove(link.id(), link); }
    static TransportException error(Code code, String message) { return new TransportException(code, message); }

    /** Completes after JNI peers/factory have been released; never join this on a game/render thread. */
    public CompletableFuture<Void> terminated() { return terminated.copy(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            loop.execute(() -> {
                Throwable first = null;
                for (WebRtcLink link : List.copyOf(links.values())) {
                    try { link.fail(error(Code.CLOSED, "Transport closed")); } catch (Throwable ex) { first = ex; }
                }
                try { if (factory != null) factory.dispose(); } catch (Throwable ex) { first = ex; }
                try { if (silentAudio != null) silentAudio.dispose(); } catch (Throwable ex) { first = ex; }
                factory = null; silentAudio = null; links.clear(); loop.shutdown();
                if (first == null) terminated.complete(null); else terminated.completeExceptionally(first);
            });
        } catch (RejectedExecutionException failure) { terminated.completeExceptionally(failure); }
    }
}
