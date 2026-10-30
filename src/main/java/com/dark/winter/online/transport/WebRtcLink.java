// A reliable, ordered byte stream carried by one encrypted WebRTC DataChannel.
package com.dark.winter.online.transport;

import dev.onvoid.webrtc.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * A reliable, ordered byte stream carried by one encrypted WebRTC DataChannel.
 * No Minecraft protocol parsing, login identity or signaling service is implemented here.
 * Future continuations may run on the transport worker: use an explicit application executor.
 */
public final class WebRtcLink implements ReliableTransportLink {
    private static final String LABEL = "dw-mc-stream-v1";
    private static final int MAGIC = 0x44575254, HEADER = 12, VERSION = 1;
    private static final byte HELLO = 1, DATA = 2, CREDIT = 3, FIN = 4, FIN_ACK = 5;
    public record Description(String type, String sdp) {
        public Description {
            Objects.requireNonNull(type); Objects.requireNonNull(sdp);
            if ((!type.equals("offer") && !type.equals("answer")) || sdp.length() > 256 * 1024
                    || sdp.indexOf('\0') >= 0 || !sdp.startsWith("v=0")
                    || !sdp.contains("m=application ")
                    || sdp.contains("m=audio ") || sdp.contains("m=video ")
                    || !sdp.toLowerCase(Locale.ROOT).contains("a=fingerprint:sha-256 "))
                throw new IllegalArgumentException("Expected data-only SDP with SHA-256 DTLS fingerprint");
        }
        RTCSessionDescription nativeValue() {
            return new RTCSessionDescription(type.equals("offer") ? RTCSdpType.OFFER : RTCSdpType.ANSWER, sdp);
        }
        @Override public String toString() { return "Description[type=" + type + ",sdp=redacted]"; }
    }
    public record Candidate(String sdpMid, int sdpMLineIndex, String sdp) {
        public Candidate {
            if (sdpMid == null || sdpMid.length() > 256 || sdpMLineIndex < 0 || sdpMLineIndex > 32
                    || sdp == null || sdp.length() > 8192 || !sdp.startsWith("candidate:")
                    || sdp.indexOf('\0') >= 0 || sdp.indexOf('\r') >= 0 || sdp.indexOf('\n') >= 0)
                throw new IllegalArgumentException("Invalid ICE candidate");
        }
        RTCIceCandidate nativeValue() { return new RTCIceCandidate(sdpMid, sdpMLineIndex, sdp); }
        @Override public String toString() { return "Candidate[mid=" + sdpMid + ",address=redacted]"; }
    }
    public record Stats(String state, String candidateType, long sentBytes, long receivedBytes,
                        long queuedWriteBytes, long queuedReceiveBytes, long nativeBufferedBytes,
                        long peakQueuedWriteBytes, long peakQueuedReceiveBytes, long backpressureEvents) {}

    private static final class Write {
        final byte[] bytes; final CompletableFuture<Void> result; int offset;
        Write(byte[] bytes, CompletableFuture<Void> result) { this.bytes = bytes; this.result = result; }
    }
    private record PendingCandidate(Candidate candidate, CompletableFuture<Void> result) {}
    private final WebRtcTransport owner;
    private final String id;
    private final RTCPeerConnection peer;
    private RTCDataChannel channel;
    private final ArrayDeque<Write> writes = new ArrayDeque<>();
    private final ArrayDeque<byte[]> received = new ArrayDeque<>();
    private final ArrayDeque<CompletableFuture<byte[]>> readers = new ArrayDeque<>();
    private final ArrayDeque<Candidate> localCandidates = new ArrayDeque<>();
    private final ArrayDeque<CompletableFuture<Candidate>> candidateReaders = new ArrayDeque<>();
    private final ArrayDeque<PendingCandidate> remoteCandidates = new ArrayDeque<>();
    private final Set<CompletableFuture<?>> operations = new HashSet<>();
    private final AtomicLong reservedWrites = new AtomicLong(), callbackBytes = new AtomicLong();
    private final AtomicInteger reservedReaders = new AtomicInteger(), reservedCandidateReaders = new AtomicInteger();
    private final AtomicReference<WebRtcTransport.TransportException> callbackError = new AtomicReference<>();
    private final CompletableFuture<Void> opened = new CompletableFuture<>(), closed = new CompletableFuture<>(), outputFinished = new CompletableFuture<>();
    private final long created = System.nanoTime();
    private long lastProgress = created, sentBytes, receivedBytes, queuedReceiveBytes, peakWrites, peakReceives, backpressureEvents;
    private long receiveAllowance, peerCredit, pendingCredit;
    private int peerWindow, frameBytes, localCandidateCount, remoteCandidateCount;
    private boolean negotiating, localOffer, remoteSet, helloSent, helloReceived, disposed, blocked;
    private volatile boolean closedFlag, outputClosing;
    private boolean finSent, finAcknowledged, remoteFin, remoteEofConsumed, finAckSent, gracefulClosing;
    private long outputClosingAt, gracefulClosingAt;
    private volatile Stats snapshot = new Stats("NEW", "", 0, 0, 0, 0, 0, 0, 0, 0);
    private String connectionState = "NEW", candidateType = "";
    private final Set<Integer> iceErrorCodes = new LinkedHashSet<>();
    private WebRtcTransport.TransportException failure;

    WebRtcLink(WebRtcTransport owner, String id, PeerConnectionFactory factory, RTCConfiguration configuration) {
        this.owner = owner; this.id = id;
        frameBytes = owner.config().frameBytes(); receiveAllowance = owner.config().receiveWindowBytes();
        peer = factory.createPeerConnection(configuration, new PeerConnectionObserver() {
            @Override public void onIceCandidate(RTCIceCandidate value) {
                try {
                    Candidate copy = new Candidate(value.sdpMid, value.sdpMLineIndex, value.sdp);
                    enqueue(() -> candidate(copy));
                } catch (Throwable ex) { callbackError.compareAndSet(null, error(WebRtcTransport.Code.PROTOCOL, "Invalid native ICE candidate")); }
            }
            @Override public void onIceCandidateError(RTCPeerConnectionIceErrorEvent event) {
                // Retain only bounded numeric diagnostics, never the URL, address, credential or native error text.
                int code = event.getErrorCode();
                enqueue(() -> { if (code >= 100 && code <= 999 && iceErrorCodes.size() < 16) iceErrorCodes.add(code); });
            }
            @Override public void onConnectionChange(RTCPeerConnectionState state) {
                enqueue(() -> {
                    connectionState = state.name();
                    if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED)
                        fail(error(WebRtcTransport.Code.NEGOTIATION, "Peer connection " + state.name().toLowerCase(Locale.ROOT)));
                });
            }
            @Override public void onSelectedCandidatePairChanged(String address, int port, String type) {
                enqueue(() -> candidateType = type == null ? "" : type);
            }
            @Override public void onDataChannel(RTCDataChannel incoming) { enqueue(() -> attach(incoming)); }
        });
        if (peer == null) throw new IllegalStateException("PeerConnection creation returned null");
    }

    public String id() { return id; }
    public CompletableFuture<Void> opened() { return opened.copy(); }
    public CompletableFuture<Void> closed() { return closed.copy(); }
    public Stats stats() { return snapshot; }

    /** Create exactly one offer. The application must sign/bind SDP and all ICE candidates to its authenticated session. */
    public CompletableFuture<Description> offer() {
        CompletableFuture<Description> result = new CompletableFuture<>();
        submit(() -> {
            if (negotiating) { result.completeExceptionally(error(WebRtcTransport.Code.NEGOTIATION, "Link already negotiated")); return; }
            negotiating = true; localOffer = true; track(result);
            RTCDataChannelInit init = new RTCDataChannelInit();
            init.ordered = true; init.maxRetransmits = -1; init.maxPacketLifeTime = -1; init.protocol = LABEL;
            attach(peer.createDataChannel(LABEL, init));
            createDescription(true, result);
        }, result);
        return result;
    }

    public CompletableFuture<Description> answer(Description offer) {
        Objects.requireNonNull(offer);
        CompletableFuture<Description> result = new CompletableFuture<>();
        submit(() -> {
            if (negotiating || !offer.type().equals("offer")) {
                result.completeExceptionally(error(WebRtcTransport.Code.NEGOTIATION, "Expected a fresh offer")); return;
            }
            negotiating = true; track(result);
            setRemote(offer, () -> createDescription(false, result), result);
        }, result);
        return result;
    }

    public CompletableFuture<Void> acceptAnswer(Description answer) {
        Objects.requireNonNull(answer);
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(() -> {
            if (!localOffer || remoteSet || !answer.type().equals("answer")) {
                result.completeExceptionally(error(WebRtcTransport.Code.NEGOTIATION, "No offer awaiting answer")); return;
            }
            track(result); setRemote(answer, () -> result.complete(null), result);
        }, result);
        return result;
    }

    /** Pull candidates without invoking application signaling code on a native/JNI thread. */
    public CompletableFuture<Candidate> nextLocalCandidate() {
        CompletableFuture<Candidate> result = new CompletableFuture<>();
        if (reservedCandidateReaders.incrementAndGet() > 4) {
            reservedCandidateReaders.decrementAndGet();
            return CompletableFuture.failedFuture(error(WebRtcTransport.Code.CAPACITY, "Too many candidate readers"));
        }
        result.whenComplete((v, e) -> reservedCandidateReaders.decrementAndGet());
        submit(() -> {
            if (!localCandidates.isEmpty()) result.complete(localCandidates.removeFirst());
            else candidateReaders.add(result);
        }, result);
        return result.copy();
    }

    public CompletableFuture<Void> addRemoteCandidate(Candidate candidate) {
        Objects.requireNonNull(candidate);
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(() -> {
            if (++remoteCandidateCount > 256) {
                result.completeExceptionally(error(WebRtcTransport.Code.CAPACITY, "Remote candidate limit")); return;
            }
            track(result);
            if (!remoteSet) remoteCandidates.add(new PendingCandidate(candidate, result));
            else applyCandidate(candidate, result);
        }, result);
        return result;
    }

    /**
     * Enqueues bytes preserving call order as serialized by the transport worker.
     * Completion means accepted by the native send queue, not acknowledged by the game.
     * Canceling a returned future cannot retract a partially sent byte stream; close the link to abort.
     */
    public synchronized CompletableFuture<Void> send(byte[] bytes) {
        Objects.requireNonNull(bytes);
        if (outputClosing) return CompletableFuture.failedFuture(error(WebRtcTransport.Code.CLOSED, "Stream output is finished"));
        if (bytes.length == 0) return closedFlag
                ? CompletableFuture.failedFuture(error(WebRtcTransport.Code.CLOSED, "Link closed")) : CompletableFuture.completedFuture(null);
        int maximum = owner.config().maxQueuedWriteBytes();
        for (;;) {
            long current = reservedWrites.get();
            if (bytes.length > maximum || current + bytes.length > maximum)
                return CompletableFuture.failedFuture(error(WebRtcTransport.Code.CAPACITY, "Write queue is full"));
            if (reservedWrites.compareAndSet(current, current + bytes.length)) break;
        }
        byte[] copy = bytes.clone();
        CompletableFuture<Void> result = new CompletableFuture<>();
        result.whenComplete((v, e) -> reservedWrites.addAndGet(-copy.length));
        submit(() -> {
            writes.add(new Write(copy, result)); peakWrites = Math.max(peakWrites, reservedWrites.get());
            pump(); publish();
        }, result);
        return result.copy();
    }

    /** Send an ordered FIN after all prior sends. Completes when the remote reader consumes EOF after prior data. */
    public synchronized CompletableFuture<Void> finishOutput() {
        if (outputClosing) return outputFinished.copy();
        if (closedFlag) return CompletableFuture.failedFuture(error(WebRtcTransport.Code.CLOSED, "Link closed"));
        outputClosing = true;
        owner.dispatch(() -> { outputClosingAt = System.nanoTime(); pump(); }, outputFinished);
        return outputFinished.copy();
    }

    /** After both half-streams drain, let SCTP close its stream before releasing the PeerConnection. */
    public CompletableFuture<Void> closeGracefully() {
        owner.dispatch(() -> {
            if (disposed) return;
            if (!finAcknowledged || !remoteEofConsumed || !finAckSent) {
                fail(error(WebRtcTransport.Code.PROTOCOL, "Graceful close requires both stream EOF acknowledgements")); return;
            }
            gracefulClosing = true; gracefulClosingAt = System.nanoTime(); channel.close();
        }, null);
        return closed();
    }

    /** Returns the next segment, or null at orderly EOF. Concatenate segments to reconstruct the byte stream. */
    public CompletableFuture<byte[]> read() {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        if (reservedReaders.incrementAndGet() > 4) {
            reservedReaders.decrementAndGet();
            return CompletableFuture.failedFuture(error(WebRtcTransport.Code.CAPACITY, "Too many pending stream readers"));
        }
        result.whenComplete((v, e) -> reservedReaders.decrementAndGet());
        submit(() -> {
            if (!received.isEmpty()) deliver(result, received.removeFirst());
            else if (remoteFin) { remoteEofConsumed = true; result.complete(null); }
            else readers.add(result);
            pump(); publish();
        }, result);
        return result.copy();
    }

    private void track(CompletableFuture<?> result) {
        operations.add(result);
        result.whenComplete((v, e) -> enqueue(() -> operations.remove(result)));
    }

    private void createDescription(boolean offer, CompletableFuture<Description> result) {
        CreateSessionDescriptionObserver observer = new CreateSessionDescriptionObserver() {
            @Override public void onSuccess(RTCSessionDescription value) {
                enqueue(() -> peer.setLocalDescription(value, new SetSessionDescriptionObserver() {
                    @Override public void onSuccess() {
                        enqueue(() -> {
                            try { result.complete(new Description(offer ? "offer" : "answer", value.sdp)); }
                            catch (Throwable ex) { fail(error(WebRtcTransport.Code.NEGOTIATION, "Native SDP did not meet data transport policy")); }
                        });
                    }
                    @Override public void onFailure(String message) { enqueue(() -> fail(error(WebRtcTransport.Code.NEGOTIATION, "Setting local description failed"))); }
                }));
            }
            @Override public void onFailure(String message) { enqueue(() -> fail(error(WebRtcTransport.Code.NEGOTIATION, "Creating session description failed"))); }
        };
        if (offer) peer.createOffer(new RTCOfferOptions(), observer);
        else peer.createAnswer(new RTCAnswerOptions(), observer);
    }

    private void setRemote(Description description, Runnable success, CompletableFuture<?> result) {
        peer.setRemoteDescription(description.nativeValue(), new SetSessionDescriptionObserver() {
            @Override public void onSuccess() {
                enqueue(() -> {
                    remoteSet = true;
                    while (!remoteCandidates.isEmpty()) {
                        PendingCandidate pending = remoteCandidates.removeFirst();
                        applyCandidate(pending.candidate, pending.result);
                    }
                    success.run();
                });
            }
            @Override public void onFailure(String message) { enqueue(() -> fail(error(WebRtcTransport.Code.NEGOTIATION, "Setting authenticated remote description failed"))); }
        });
    }

    private void applyCandidate(Candidate candidate, CompletableFuture<Void> result) {
        peer.addIceCandidate(candidate.nativeValue(), new AddIceCandidateObserver() {
            @Override public void onSuccess() { enqueue(() -> result.complete(null)); }
            @Override public void onFailure(String message) {
                enqueue(() -> {
                    result.completeExceptionally(error(WebRtcTransport.Code.NEGOTIATION, "Remote ICE candidate rejected"));
                    fail(error(WebRtcTransport.Code.NEGOTIATION, "Remote ICE candidate rejected"));
                });
            }
        });
    }

    private void candidate(Candidate candidate) {
        if (++localCandidateCount > 256) { fail(error(WebRtcTransport.Code.CAPACITY, "Local ICE candidate limit")); return; }
        while (!candidateReaders.isEmpty() && candidateReaders.peekFirst().isDone()) candidateReaders.removeFirst();
        if (!candidateReaders.isEmpty()) candidateReaders.removeFirst().complete(candidate);
        else localCandidates.add(candidate);
    }

    private void attach(RTCDataChannel incoming) {
        if (incoming == null) { fail(error(WebRtcTransport.Code.NATIVE_FAILURE, "Missing data channel")); return; }
        if (channel != null || !LABEL.equals(incoming.getLabel()) || !LABEL.equals(incoming.getProtocol())
                || !incoming.isOrdered() || !incoming.isReliable()) {
            incoming.close(); incoming.dispose();
            fail(error(WebRtcTransport.Code.PROTOCOL, "Unexpected or unreliable data channel")); return;
        }
        channel = incoming;
        incoming.registerObserver(new RTCDataChannelObserver() {
            @Override public void onBufferedAmountChange(long sentDataSize) { enqueue(() -> { pump(); publish(); }); }
            @Override public void onStateChange() { enqueue(() -> { pump(); publish(); }); }
            @Override public void onMessage(RTCDataChannelBuffer buffer) {
                int size = buffer.data.remaining();
                if (!buffer.binary || size < HEADER || size > HEADER + owner.config().frameBytes()
                        || callbackBytes.addAndGet(size) > (long) owner.config().receiveWindowBytes() + 8L * (HEADER + owner.config().frameBytes())) {
                    callbackError.compareAndSet(null, error(WebRtcTransport.Code.PROTOCOL, "Data-channel frame or receive-window limit"));
                    return;
                }
                byte[] copy = new byte[size];
                buffer.data.duplicate().get(copy);
                // Native buffer lifetime ends when this callback returns.
                enqueue(() -> {
                    callbackBytes.addAndGet(-copy.length);
                    frame(copy); pump(); publish();
                });
            }
        });
        pump();
    }

    private void frame(byte[] bytes) {
        ByteBuffer input = ByteBuffer.wrap(bytes);
        int magic = input.getInt(); byte version = input.get(), type = input.get(); short flags = input.getShort(); int length = input.getInt();
        if (magic != MAGIC || version != VERSION || flags != 0 || length != input.remaining()) {
            fail(error(WebRtcTransport.Code.PROTOCOL, "Invalid transport frame")); return;
        }
        if (type == HELLO) {
            if (helloReceived || length != 8) { fail(error(WebRtcTransport.Code.PROTOCOL, "Invalid stream greeting")); return; }
            int frame = input.getInt(), window = input.getInt();
            if (frame < 1024 || frame > 16 * 1024 || window < frame || window > 4 * 1024 * 1024) {
                fail(error(WebRtcTransport.Code.PROTOCOL, "Peer stream bounds rejected")); return;
            }
            frameBytes = Math.min(frameBytes, frame); peerWindow = window; peerCredit = window; helloReceived = true;
            lastProgress = System.nanoTime(); finishOpen();
        } else if (type == CREDIT) {
            if (!helloReceived || length != 4) { fail(error(WebRtcTransport.Code.PROTOCOL, "Unexpected flow credit")); return; }
            int credit = input.getInt();
            if (credit <= 0 || peerCredit + credit > peerWindow) { fail(error(WebRtcTransport.Code.PROTOCOL, "Invalid flow credit")); return; }
            peerCredit += credit; lastProgress = System.nanoTime();
        } else if (type == FIN) {
            if (!helloReceived || remoteFin || length != 0) { fail(error(WebRtcTransport.Code.PROTOCOL, "Invalid stream FIN")); return; }
            remoteFin = true;
            if (received.isEmpty() && !readers.isEmpty()) {
                remoteEofConsumed = true;
                while (!readers.isEmpty()) readers.removeFirst().complete(null);
            }
        } else if (type == FIN_ACK) {
            if (!finSent || finAcknowledged || length != 0) { fail(error(WebRtcTransport.Code.PROTOCOL, "Invalid stream FIN acknowledgement")); return; }
            finAcknowledged = true; outputFinished.complete(null);
        } else if (type == DATA) {
            if (remoteFin || !helloReceived || length <= 0 || length > frameBytes || length > receiveAllowance) {
                fail(error(WebRtcTransport.Code.PROTOCOL, "Peer exceeded receive credit")); return;
            }
            receiveAllowance -= length; receivedBytes += length; queuedReceiveBytes += length;
            byte[] payload = new byte[length]; input.get(payload);
            peakReceives = Math.max(peakReceives, queuedReceiveBytes);
            while (!readers.isEmpty() && readers.peekFirst().isDone()) readers.removeFirst();
            if (readers.isEmpty()) received.add(payload);
            else deliver(readers.removeFirst(), payload);
        } else fail(error(WebRtcTransport.Code.PROTOCOL, "Unknown transport frame type"));
    }

    private void deliver(CompletableFuture<byte[]> reader, byte[] bytes) {
        queuedReceiveBytes -= bytes.length;
        pendingCredit += bytes.length;
        reader.complete(bytes);
    }

    private boolean sendFrame(byte type, byte[] payload, int offset, int length) {
        ByteBuffer output = ByteBuffer.allocate(HEADER + length);
        output.putInt(MAGIC).put((byte) VERSION).put(type).putShort((short) 0).putInt(length).put(payload, offset, length).flip();
        try { channel.send(new RTCDataChannelBuffer(output, true)); return true; }
        catch (Exception fullOrClosed) { return false; } // bounded retry/deadline in tick; no sleep/JNI callback spin.
    }

    private void pump() {
        if (disposed || channel == null) return;
        RTCDataChannelState state = channel.getState();
        if (state == RTCDataChannelState.CLOSED) {
            fail(error(WebRtcTransport.Code.CLOSED, "Remote stream closed")); return;
        }
        if (state == RTCDataChannelState.CLOSING) {
            if (outputClosing && remoteEofConsumed) {
                if (!gracefulClosing) { gracefulClosing = true; gracefulClosingAt = System.nanoTime(); }
                return;
            }
            fail(error(WebRtcTransport.Code.CLOSED, "Remote stream closed")); return;
        }
        if (state != RTCDataChannelState.OPEN) return;
        if (!helloSent) {
            byte[] greeting = ByteBuffer.allocate(8).putInt(owner.config().frameBytes()).putInt(owner.config().receiveWindowBytes()).array();
            if (!sendFrame(HELLO, greeting, 0, greeting.length)) return;
            helloSent = true; lastProgress = System.nanoTime(); finishOpen();
        }
        if (pendingCredit > 0) {
            int amount = (int) pendingCredit;
            byte[] credit = ByteBuffer.allocate(4).putInt(amount).array();
            if (!sendFrame(CREDIT, credit, 0, credit.length)) return;
            pendingCredit -= amount; receiveAllowance += amount;
        }
        if (!helloReceived) return;
        if (remoteEofConsumed && !finAckSent) {
            if (!sendFrame(FIN_ACK, new byte[0], 0, 0)) return;
            finAckSent = true;
        }
        // Yield after a bounded quantum so one bulk sender cannot monopolize every peer.
        for (int quantum = 0; quantum < 16 && !writes.isEmpty(); quantum++) {
            Write write = writes.peekFirst();
            int count = (int) Math.min(Math.min(frameBytes, write.bytes.length - write.offset), peerCredit);
            if (count <= 0 || channel.getBufferedAmount() >= owner.config().nativeHighWaterBytes()) {
                if (!blocked) { blocked = true; backpressureEvents++; }
                return;
            }
            if (!sendFrame(DATA, write.bytes, write.offset, count)) {
                if (!blocked) { blocked = true; backpressureEvents++; }
                return;
            }
            blocked = false; peerCredit -= count; write.offset += count; sentBytes += count; lastProgress = System.nanoTime();
            if (write.offset == write.bytes.length) { writes.removeFirst(); write.result.complete(null); }
        }
        if (writes.isEmpty() && outputClosing && !finSent) {
            if (sendFrame(FIN, new byte[0], 0, 0)) { finSent = true; lastProgress = System.nanoTime(); }
        }
    }

    private void finishOpen() {
        if (helloSent && helloReceived) { publish(); opened.complete(null); }
    }
    void tick(long now) {
        if (disposed) return;
        WebRtcTransport.TransportException callbackFailure = callbackError.getAndSet(null);
        if (callbackFailure != null) { fail(callbackFailure); return; }
        if (!opened.isDone() && now - created > owner.config().connectTimeout().toNanos()) {
            fail(error(WebRtcTransport.Code.TIMEOUT, "No usable ICE/DTLS/SCTP route before deadline; ICE codes=" + iceErrorCodes)); return;
        }
        if (gracefulClosing && now - gracefulClosingAt > TimeUnit.SECONDS.toNanos(5)) {
            fail(error(WebRtcTransport.Code.TIMEOUT, "SCTP stream close timed out")); return;
        }
        if (outputClosingAt > 0 && !finAcknowledged && now - outputClosingAt > owner.config().idleWriteTimeout().toNanos()) {
            fail(error(WebRtcTransport.Code.TIMEOUT, "Stream EOF acknowledgement timed out")); return;
        }
        if (!writes.isEmpty() && now - lastProgress > owner.config().idleWriteTimeout().toNanos()) {
            fail(error(WebRtcTransport.Code.TIMEOUT, "Reliable stream write stalled")); return;
        }
        pump(); publish();
    }

    private void publish() {
        snapshot = new Stats(disposed ? "CLOSED" : helloSent && helloReceived ? "OPEN" : connectionState,
                candidateType, sentBytes, receivedBytes, reservedWrites.get(), queuedReceiveBytes,
                disposed || channel == null ? 0 : channel.getBufferedAmount(), peakWrites, peakReceives, backpressureEvents);
    }

    private void submit(Runnable operation, CompletableFuture<?> result) {
        if (closedFlag) { result.completeExceptionally(failure == null ? error(WebRtcTransport.Code.CLOSED, "Link closed") : failure); return; }
        owner.dispatch(() -> {
            if (disposed) result.completeExceptionally(failure);
            else operation.run();
        }, result);
    }
    private void enqueue(Runnable callback) {
        if (!closedFlag) owner.dispatch(() -> {
            if (!disposed) {
                try { callback.run(); }
                catch (Throwable ex) { callbackError.compareAndSet(null, error(WebRtcTransport.Code.NATIVE_FAILURE, "Native callback failed: " + ex.getClass().getSimpleName())); }
            }
        }, null);
    }
    private static WebRtcTransport.TransportException error(WebRtcTransport.Code code, String message) { return WebRtcTransport.error(code, message); }

    void fail(WebRtcTransport.TransportException why) {
        if (disposed) return;
        disposed = true; closedFlag = true; failure = why;
        opened.completeExceptionally(why);
        outputFinished.completeExceptionally(why);
        for (Write write : writes) write.result.completeExceptionally(why);
        for (CompletableFuture<byte[]> reader : readers) reader.completeExceptionally(why);
        for (CompletableFuture<Candidate> reader : candidateReaders) reader.completeExceptionally(why);
        for (CompletableFuture<?> operation : List.copyOf(operations)) operation.completeExceptionally(why);
        writes.clear(); readers.clear(); candidateReaders.clear(); remoteCandidates.clear(); localCandidates.clear(); received.clear(); operations.clear();
        queuedReceiveBytes = 0;
        try {
            if (channel != null) { channel.unregisterObserver(); channel.close(); channel.dispose(); channel = null; }
        } finally {
            try { peer.close(); }
            finally { owner.removed(this); publish(); closed.complete(null); }
        }
    }

    @Override public void close() { owner.dispatch(() -> fail(error(WebRtcTransport.Code.CLOSED, "Stream closed by application")), null); }
}
