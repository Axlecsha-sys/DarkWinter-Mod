// One Opus/DTLS-SRTP audio peer.
package com.dark.winter.online.voice;

import com.dark.winter.online.transport.WebRtcTransport;
import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * One Opus/DTLS-SRTP audio peer. No OS device or recording is created.
 * PCM is real-time, lossy/bounded audio, separate from the reliable Minecraft byte stream.
 * Signaling/fingerprint authentication and host-authoritative delivery policy belong to the caller.
 */
public final class VoicePeer implements AutoCloseable {
    public static final int SAMPLE_RATE = 48000, FRAME_SAMPLES = 480, FRAME_BYTES = 960;
    public record Description(String type, String sdp) {
        public Description {
            Objects.requireNonNull(type); Objects.requireNonNull(sdp);
            if (!Set.of("offer", "answer").contains(type) || sdp.length() > 256 * 1024
                    || sdp.indexOf('\0') >= 0 || !sdp.startsWith("v=0"))
                throw new IllegalArgumentException("Voice SDP format");
            String lower = sdp.toLowerCase(Locale.ROOT);
            List<String> media = lower.lines().filter(line -> line.startsWith("m=")).toList();
            if (media.size() != 1 || !media.getFirst().startsWith("m=audio ")
                    || !media.getFirst().contains(" udp/tls/rtp/savpf ")
                    || !lower.contains("a=fingerprint:sha-256 ")
                    || !lower.matches("(?s).*a=rtpmap:[0-9]+ opus/48000/2(?:\\r?\\n|$).*"))
                throw new IllegalArgumentException("Expected audio-only Opus DTLS-SRTP SDP");
        }
        RTCSessionDescription nativeValue() {
            return new RTCSessionDescription(type.equals("offer") ? RTCSdpType.OFFER : RTCSdpType.ANSWER, sdp);
        }
        @Override public String toString() { return "VoiceDescription[type=" + type + ",sdp=redacted]"; }
    }
    public record Candidate(String sdpMid, int sdpMLineIndex, String sdp) {
        public Candidate {
            if (sdpMid == null || sdpMid.length() > 256 || sdpMLineIndex < 0 || sdpMLineIndex > 8
                    || sdp == null || sdp.length() > 8192 || !sdp.startsWith("candidate:")
                    || sdp.indexOf('\0') >= 0 || sdp.indexOf('\r') >= 0 || sdp.indexOf('\n') >= 0)
                throw new IllegalArgumentException("Voice ICE candidate");
        }
        RTCIceCandidate nativeValue() { return new RTCIceCandidate(sdpMid, sdpMLineIndex, sdp); }
        @Override public String toString() { return "VoiceCandidate[mid=" + sdpMid + ",address=redacted]"; }
    }
    /** Signed PCM16 in platform byte order; returned arrays are defensive copies. */
    public record DecodedAudio(byte[] pcm, int sampleRate, int channels, int frames) {
        public DecodedAudio {
            Objects.requireNonNull(pcm);
            if (sampleRate < 8000 || sampleRate > 48000 || channels < 1 || channels > 2
                    || frames < 1 || (long) frames * channels > 7680 || pcm.length != frames * channels * 2)
                throw new IllegalArgumentException("Decoded audio bounds");
            pcm = pcm.clone();
        }
        @Override public byte[] pcm() { return pcm.clone(); }
    }
    public record Stats(String state, String candidateType, boolean transmitting, boolean receiving,
                        boolean speaking, long submittedFrames, long decodedFrames, long inputDrops,
                        long outputDrops, int queuedInputFrames, int queuedOutputFrames) {}
    /** Only whitelisted media/security counters; no addresses, certificates or raw SDP. */
    public record MediaStats(Set<String> codecs, String dtlsState, String dtlsCipher, String srtpCipher,
                             long sentBytes, long receivedBytes, long sentPackets, long receivedPackets) {
        public MediaStats { codecs = Set.copyOf(codecs); }
        public boolean protectedMedia() {
            return dtlsState.equalsIgnoreCase("connected") && !srtpCipher.isBlank()
                    && !dtlsCipher.isBlank() && codecs.stream().anyMatch(codec -> codec.equalsIgnoreCase("audio/opus"));
        }
    }
    private record Input(byte[] pcm, long captured) {}
    private record RemoteCandidate(Candidate value, CompletableFuture<Void> result) {}
    private final WebRtcVoiceTransport owner;
    private final String id;
    private RTCPeerConnection peer;
    private CustomAudioSource source;
    private AudioTrack localTrack, remoteTrack;
    private RTCRtpTransceiver localTransceiver;
    private RTCRtpSender sender;
    private RTCRtpReceiver receiver;
    private AudioTrackSink sink;
    private final Object inputLock = new Object(), outputLock = new Object();
    private final ArrayDeque<Input> input = new ArrayDeque<>();
    private final ArrayDeque<DecodedAudio> output = new ArrayDeque<>();
    private final ArrayDeque<Candidate> candidates = new ArrayDeque<>();
    private final ArrayDeque<CompletableFuture<Candidate>> candidateReaders = new ArrayDeque<>();
    private final ArrayDeque<RemoteCandidate> pendingCandidates = new ArrayDeque<>();
    private final Set<CompletableFuture<?>> operations = new HashSet<>();
    private final AtomicBoolean transmit = new AtomicBoolean(), receive = new AtomicBoolean(true);
    private final AtomicInteger reservedCandidateReaders = new AtomicInteger();
    private final AtomicLong submitted = new AtomicLong(), decoded = new AtomicLong(), inputDrops = new AtomicLong(),
            outputDrops = new AtomicLong(), lastSpeech = new AtomicLong();
    private final CompletableFuture<Void> opened = new CompletableFuture<>(), closed = new CompletableFuture<>();
    private final long created = System.nanoTime();
    private volatile boolean closedFlag;
    private volatile double volume = 1.0;
    private boolean disposed, negotiating, localOffer, remoteSet;
    private int localCandidateCount, remoteCandidateCount;
    private long lastPcmPush;
    private String state = "NEW", candidateType = "";
    private WebRtcTransport.TransportException failure;
    private volatile Stats snapshot = new Stats("NEW", "", false, true, false, 0, 0, 0, 0, 0, 0);

    VoicePeer(WebRtcVoiceTransport owner, String id, PeerConnectionFactory factory, RTCConfiguration config) {
        this.owner = owner; this.id = id;
        try {
            peer = factory.createPeerConnection(config, new PeerConnectionObserver() {
                @Override public void onIceCandidate(RTCIceCandidate value) {
                    try {
                        Candidate copy = new Candidate(value.sdpMid, value.sdpMLineIndex, value.sdp);
                        enqueue(() -> candidate(copy));
                    } catch (Throwable invalid) {
                        enqueue(() -> fail(error(WebRtcTransport.Code.PROTOCOL, "Invalid native voice candidate")));
                    }
                }
                @Override public void onConnectionChange(RTCPeerConnectionState value) {
                    enqueue(() -> {
                        state = value.name(); publish();
                        if (value == RTCPeerConnectionState.CONNECTED) opened.complete(null);
                        else if (value == RTCPeerConnectionState.FAILED || value == RTCPeerConnectionState.CLOSED)
                            fail(error(WebRtcTransport.Code.NEGOTIATION, "Voice route " + value.name().toLowerCase(Locale.ROOT)));
                    });
                }
                @Override public void onSelectedCandidatePairChanged(String address, int port, String type) {
                    enqueue(() -> candidateType = type == null ? "" : type);
                }
                @Override public void onTrack(RTCRtpTransceiver incoming) {
                    owner.dispatch(() -> {
                        try { if (!disposed) attach(incoming); }
                        finally { incoming.dispose(); }
                    }, null, incoming::dispose);
                }
                @Override public void onDataChannel(RTCDataChannel unwanted) {
                    owner.dispatch(() -> {
                        try { unwanted.close(); } finally { unwanted.dispose(); }
                        fail(error(WebRtcTransport.Code.PROTOCOL, "Data channels are not voice media"));
                    }, null, () -> { try { unwanted.close(); } finally { unwanted.dispose(); } });
                }
            });
            if (peer == null) throw new IllegalStateException("Missing voice peer");
            source = new CustomAudioSource();
            localTrack = factory.createAudioTrack("dw-voice-" + id, source);
            localTrack.setEnabled(false);
            // addTrack-created transceivers can associate with an incoming offer.
            // A precreated addTransceiver would leave the answerer's local sender unnegotiated.
            sender = peer.addTrack(localTrack, List.of("dw-voice"));
            RTCRtpTransceiver[] tracks = peer.getTransceivers();
            if (tracks.length != 1) {
                for (RTCRtpTransceiver extra : tracks) extra.dispose();
                throw new IllegalStateException("Expected one voice transceiver");
            }
            localTransceiver = tracks[0];
            List<RTCRtpCodecCapability> opus = factory.getRtpSenderCapabilities(MediaType.AUDIO).getCodecs().stream()
                    .filter(codec -> codec.getName().equalsIgnoreCase("opus") && codec.getClockRate() == SAMPLE_RATE).toList();
            if (opus.isEmpty()) throw new IllegalStateException("Opus unavailable");
            localTransceiver.setCodecPreferences(opus);
        } catch (Throwable ex) {
            release(); throw ex;
        }
    }
    public String id() { return id; }
    public CompletableFuture<Void> opened() { return opened.copy(); }
    public CompletableFuture<Void> closed() { return closed.copy(); }
    public Stats stats() { return snapshot; }

    public CompletableFuture<Description> offer() {
        CompletableFuture<Description> result = new CompletableFuture<>();
        submit(() -> {
            if (negotiating) { result.completeExceptionally(error(WebRtcTransport.Code.NEGOTIATION, "Voice already negotiated")); return; }
            negotiating = true; localOffer = true; track(result); createDescription(true, result);
        }, result);
        return result.copy();
    }
    public CompletableFuture<Description> answer(Description offer) {
        Objects.requireNonNull(offer);
        CompletableFuture<Description> result = new CompletableFuture<>();
        submit(() -> {
            if (negotiating || !offer.type().equals("offer")) {
                result.completeExceptionally(error(WebRtcTransport.Code.NEGOTIATION, "Fresh voice offer required")); return;
            }
            negotiating = true; track(result); setRemote(offer, () -> createDescription(false, result));
        }, result);
        return result.copy();
    }
    public CompletableFuture<Void> acceptAnswer(Description answer) {
        Objects.requireNonNull(answer);
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(() -> {
            if (!localOffer || remoteSet || !answer.type().equals("answer")) {
                result.completeExceptionally(error(WebRtcTransport.Code.NEGOTIATION, "No voice offer awaiting answer")); return;
            }
            track(result); setRemote(answer, () -> result.complete(null));
        }, result);
        return result.copy();
    }
    public CompletableFuture<Candidate> nextLocalCandidate() {
        if (reservedCandidateReaders.incrementAndGet() > 4) {
            reservedCandidateReaders.decrementAndGet();
            return CompletableFuture.failedFuture(error(WebRtcTransport.Code.CAPACITY, "Voice candidate reader capacity"));
        }
        CompletableFuture<Candidate> result = new CompletableFuture<>();
        result.whenComplete((value, ex) -> reservedCandidateReaders.decrementAndGet());
        submit(() -> {
            if (candidates.isEmpty()) candidateReaders.add(result);
            else result.complete(candidates.removeFirst());
        }, result);
        return result.copy();
    }
    public CompletableFuture<Void> addRemoteCandidate(Candidate candidate) {
        Objects.requireNonNull(candidate);
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(() -> {
            if (++remoteCandidateCount > 256) {
                result.completeExceptionally(error(WebRtcTransport.Code.CAPACITY, "Voice ICE candidate limit")); return;
            }
            track(result);
            if (remoteSet) applyCandidate(candidate, result);
            else pendingCandidates.add(new RemoteCandidate(candidate, result));
        }, result);
        return result.copy();
    }

    /** Explicit Push-to-Talk gate. Disabled by default; disabling immediately discards queued input PCM. */
    public CompletableFuture<Void> setTransmit(boolean enabled) {
        if (!enabled) {
            transmit.set(false);
            synchronized (inputLock) { input.clear(); }
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(() -> {
            localTrack.setEnabled(enabled); transmit.set(enabled); publish(); result.complete(null);
        }, result);
        return result.copy();
    }
    /** Does not open a microphone. Caller supplies a single 10ms PCM16/48k/mono frame in native byte order. */
    public boolean pushPcm10ms(byte[] pcm) {
        Objects.requireNonNull(pcm);
        if (pcm.length != FRAME_BYTES) throw new IllegalArgumentException("One PCM frame is 960 bytes");
        if (closedFlag || !transmit.get() || !opened.isDone() || opened.isCompletedExceptionally()) return false;
        synchronized (inputLock) {
            if (closedFlag || !transmit.get()) return false;
            if (input.size() >= owner.config().inputQueueFrames()) { inputDrops.incrementAndGet(); return false; }
            input.add(new Input(pcm.clone(), System.nanoTime())); return true;
        }
    }
    /** Local playout control, not a server delivery permission. The caller must enforce routing at the host. */
    public void setReceive(boolean enabled, double volume) {
        if (!Double.isFinite(volume) || volume < 0 || volume > 2) throw new IllegalArgumentException("Voice volume 0..2");
        this.volume = volume; receive.set(enabled);
        if (!enabled) synchronized (outputLock) { output.clear(); lastSpeech.set(0); }
    }
    public Optional<DecodedAudio> pollDecoded() {
        synchronized (outputLock) { return Optional.ofNullable(output.pollFirst()); }
    }
    public CompletableFuture<MediaStats> mediaStats() {
        CompletableFuture<MediaStats> result = new CompletableFuture<>();
        submit(() -> {
            track(result);
            peer.getStats(report -> {
                try {
                    MediaStats value = summarize(report);
                    enqueue(() -> result.complete(value));
                } catch (Throwable invalid) {
                    enqueue(() -> result.completeExceptionally(error(WebRtcTransport.Code.PROTOCOL, "Invalid native voice stats")));
                }
            });
        }, result);
        result.orTimeout(5, TimeUnit.SECONDS);
        return result.copy();
    }
    private static MediaStats summarize(RTCStatsReport report) {
        Map<String, RTCStats> all = report.getStats();
        Set<String> codecs = new TreeSet<>();
        Set<String> audioCodecs = new HashSet<>();
        String dtls = "", dtlsCipher = "", srtp = "";
        long sentBytes = 0, receivedBytes = 0, sentPackets = 0, receivedPackets = 0;
        for (RTCStats stat : all.values()) {
            Map<String, Object> attrs = stat.getAttributes();
            if (stat.getType() == RTCStatsType.CODEC) {
                String mime = text(attrs.get("mimeType"));
                if (mime.toLowerCase(Locale.ROOT).startsWith("audio/")) { codecs.add(mime); audioCodecs.add(stat.getId()); }
            } else if (stat.getType() == RTCStatsType.TRANSPORT) {
                String next = text(attrs.get("dtlsState"));
                if (next.equalsIgnoreCase("connected") || dtls.isEmpty()) dtls = next;
                String cipher = text(attrs.get("dtlsCipher")), profile = text(attrs.get("srtpCipher"));
                if (!cipher.isEmpty()) dtlsCipher = cipher;
                if (!profile.isEmpty()) srtp = profile;
            }
        }
        for (RTCStats stat : all.values()) {
            Map<String, Object> attrs = stat.getAttributes();
            if (!"audio".equals(attrs.get("kind")) && !"audio".equals(attrs.get("mediaType"))
                    && !audioCodecs.contains(text(attrs.get("codecId")))) continue;
            if (stat.getType() == RTCStatsType.OUTBOUND_RTP) {
                sentBytes += number(attrs.get("bytesSent")); sentPackets += number(attrs.get("packetsSent"));
            } else if (stat.getType() == RTCStatsType.INBOUND_RTP) {
                receivedBytes += number(attrs.get("bytesReceived")); receivedPackets += number(attrs.get("packetsReceived"));
            }
        }
        return new MediaStats(codecs, dtls, dtlsCipher, srtp, sentBytes, receivedBytes, sentPackets, receivedPackets);
    }
    private static String text(Object value) { return value instanceof String s && s.length() <= 256 ? s : ""; }
    private static long number(Object value) { return value instanceof Number n ? Math.max(0, n.longValue()) : 0; }

    private void attach(RTCRtpTransceiver incoming) {
        RTCRtpReceiver found = incoming.getReceiver();
        MediaStreamTrack view = found.getTrack();
        if (!(view instanceof AudioTrack audio) || remoteTrack != null) {
            try { if (view != null) view.dispose(); } finally { found.dispose(); }
            fail(error(WebRtcTransport.Code.PROTOCOL, "Unexpected remote voice track")); return;
        }
        receiver = found; remoteTrack = audio; sink = this::decodedAudio;
        remoteTrack.addSink(sink);
    }
    private void decodedAudio(byte[] data, int bits, int rate, int channels, int frames) {
        if (closedFlag || !receive.get()) return;
        if (bits != 16 || rate < 8000 || rate > 48000 || channels < 1 || channels > 2 || frames < 1
                || (long) frames * channels > 7680 || data.length < frames * channels * 2) {
            enqueue(() -> fail(error(WebRtcTransport.Code.PROTOCOL, "Decoded voice format rejected"))); return;
        }
        byte[] pcm = Arrays.copyOf(data, frames * channels * 2);
        double gain = volume, energy = 0;
        boolean little = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN;
        for (int i = 0; i < pcm.length; i += 2) {
            int value = little ? (short) ((pcm[i] & 255) | pcm[i + 1] << 8)
                    : (short) ((pcm[i] << 8) | (pcm[i + 1] & 255));
            int scaled = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value * gain)));
            energy += (double) scaled * scaled;
            pcm[i] = (byte) (little ? scaled : scaled >> 8);
            pcm[i + 1] = (byte) (little ? scaled >> 8 : scaled);
        }
        synchronized (outputLock) {
            if (closedFlag || !receive.get()) return;
            if (output.size() >= owner.config().decodedQueueFrames()) { output.removeFirst(); outputDrops.incrementAndGet(); }
            output.add(new DecodedAudio(pcm, rate, channels, frames)); decoded.incrementAndGet();
            if (energy / (pcm.length / 2) > 150.0 * 150.0) lastSpeech.set(System.nanoTime());
        }
    }
    private void createDescription(boolean offer, CompletableFuture<Description> result) {
        CreateSessionDescriptionObserver observer = new CreateSessionDescriptionObserver() {
            @Override public void onSuccess(RTCSessionDescription value) {
                enqueue(() -> peer.setLocalDescription(value, new SetSessionDescriptionObserver() {
                    @Override public void onSuccess() {
                        enqueue(() -> {
                            try { result.complete(new Description(offer ? "offer" : "answer", value.sdp)); }
                            catch (RuntimeException invalid) { fail(error(WebRtcTransport.Code.NEGOTIATION, "Native SDP violated voice policy")); }
                        });
                    }
                    @Override public void onFailure(String message) { enqueue(() -> fail(error(WebRtcTransport.Code.NEGOTIATION, "Local voice SDP rejected"))); }
                }));
            }
            @Override public void onFailure(String message) { enqueue(() -> fail(error(WebRtcTransport.Code.NEGOTIATION, "Voice SDP creation failed"))); }
        };
        if (offer) peer.createOffer(new RTCOfferOptions(), observer); else peer.createAnswer(new RTCAnswerOptions(), observer);
    }
    private void setRemote(Description value, Runnable success) {
        peer.setRemoteDescription(value.nativeValue(), new SetSessionDescriptionObserver() {
            @Override public void onSuccess() {
                enqueue(() -> {
                    remoteSet = true;
                    while (!pendingCandidates.isEmpty()) {
                        RemoteCandidate waiting = pendingCandidates.removeFirst(); applyCandidate(waiting.value(), waiting.result());
                    }
                    success.run();
                });
            }
            @Override public void onFailure(String message) { enqueue(() -> fail(error(WebRtcTransport.Code.NEGOTIATION, "Authenticated remote voice SDP rejected"))); }
        });
    }
    private void applyCandidate(Candidate value, CompletableFuture<Void> result) {
        peer.addIceCandidate(value.nativeValue(), new AddIceCandidateObserver() {
            @Override public void onSuccess() { enqueue(() -> result.complete(null)); }
            @Override public void onFailure(String message) { enqueue(() -> fail(error(WebRtcTransport.Code.NEGOTIATION, "Voice ICE candidate rejected"))); }
        });
    }
    private void candidate(Candidate value) {
        if (++localCandidateCount > 256) { fail(error(WebRtcTransport.Code.CAPACITY, "Voice local candidate limit")); return; }
        while (!candidateReaders.isEmpty() && candidateReaders.peekFirst().isDone()) candidateReaders.removeFirst();
        if (!candidateReaders.isEmpty()) candidateReaders.removeFirst().complete(value); else candidates.add(value);
    }
    void tick(long now) {
        if (disposed) return;
        if (!opened.isDone() && now - created > owner.config().network().connectTimeout().toNanos()) {
            fail(error(WebRtcTransport.Code.TIMEOUT, "No usable voice ICE/DTLS route before deadline")); return;
        }
        Input frame = null;
        synchronized (inputLock) {
            while (!input.isEmpty() && now - input.peekFirst().captured() > TimeUnit.MILLISECONDS.toNanos(200)) {
                input.removeFirst(); inputDrops.incrementAndGet();
            }
            if (transmit.get() && !input.isEmpty() && (lastPcmPush == 0 || now - lastPcmPush >= TimeUnit.MILLISECONDS.toNanos(8))) frame = input.removeFirst();
        }
        if (frame != null && transmit.get()) {
            source.pushAudio(frame.pcm(), 16, SAMPLE_RATE, 1, FRAME_SAMPLES); lastPcmPush = now; submitted.incrementAndGet();
        }
        publish();
    }
    private void publish() {
        int in, out;
        synchronized (inputLock) { in = input.size(); }
        synchronized (outputLock) { out = output.size(); }
        snapshot = new Stats(disposed ? "CLOSED" : state, candidateType, transmit.get(), receive.get(),
                lastSpeech.get() > 0 && System.nanoTime() - lastSpeech.get() < TimeUnit.MILLISECONDS.toNanos(300),
                submitted.get(), decoded.get(), inputDrops.get(), outputDrops.get(), in, out);
    }
    private void track(CompletableFuture<?> result) {
        operations.add(result); result.whenComplete((value, error) -> enqueue(() -> operations.remove(result)));
    }
    private void submit(Runnable task, CompletableFuture<?> result) {
        if (closedFlag) { result.completeExceptionally(failure == null ? error(WebRtcTransport.Code.CLOSED, "Voice peer closed") : failure); return; }
        owner.dispatch(() -> {
            if (disposed) result.completeExceptionally(failure); else task.run();
        }, result);
    }
    private void enqueue(Runnable callback) {
        if (!closedFlag) owner.dispatch(() -> { if (!disposed) callback.run(); }, null);
    }
    private static WebRtcTransport.TransportException error(WebRtcTransport.Code code, String text) {
        return WebRtcVoiceTransport.error(code, text);
    }
    void fail(WebRtcTransport.TransportException reason) {
        if (disposed) return;
        disposed = true; closedFlag = true; transmit.set(false); receive.set(false); failure = reason;
        opened.completeExceptionally(reason);
        for (CompletableFuture<?> operation : List.copyOf(operations)) operation.completeExceptionally(reason);
        for (CompletableFuture<Candidate> waiter : candidateReaders) waiter.completeExceptionally(reason);
        operations.clear(); candidateReaders.clear(); candidates.clear(); pendingCandidates.clear();
        synchronized (inputLock) { input.clear(); }
        synchronized (outputLock) { output.clear(); }
        Throwable cleanup = release(); owner.removed(this); publish();
        if (cleanup == null) closed.complete(null); else closed.completeExceptionally(cleanup);
    }
    private Throwable release() {
        Throwable first = null;
        try { if (remoteTrack != null && sink != null) remoteTrack.removeSink(sink); } catch (Throwable ex) { first = ex; }
        try { if (remoteTrack != null) remoteTrack.dispose(); } catch (Throwable ex) { first = ex; }
        try { if (sender != null) sender.replaceTrack(null); } catch (Throwable ex) { first = ex; }
        try { if (receiver != null) receiver.dispose(); } catch (Throwable ex) { first = ex; }
        try { if (sender != null) sender.dispose(); } catch (Throwable ex) { first = ex; }
        try { if (localTransceiver != null) localTransceiver.dispose(); } catch (Throwable ex) { first = ex; }
        try { if (peer != null) peer.close(); } catch (Throwable ex) { first = ex; }
        try { if (localTrack != null) localTrack.dispose(); } catch (Throwable ex) { first = ex; }
        try { if (source != null) source.dispose(); } catch (Throwable ex) { first = ex; }
        remoteTrack = null; receiver = null; sender = null; localTransceiver = null; localTrack = null; source = null; peer = null; sink = null;
        return first;
    }
    @Override public void close() {
        transmit.set(false);
        owner.dispatch(() -> fail(error(WebRtcTransport.Code.CLOSED, "Voice peer closed by application")), null);
    }
}
