// Opens explicitly selected local capture and playback devices for voice chat.
package com.dark.winter.online.voice;

import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Explicit local audio endpoints. Construction does not enumerate or open any device.
 * devices/openCapture/openPlayback must be called only from an explicit audio settings action.
 * No audio files, background recording, default microphone selection or device fallback.
 */
public final class JavaSoundVoiceDevices implements AutoCloseable {
    public record Device(String id, String name, boolean input, boolean output) {}
    public record CaptureState(String deviceId, String status, boolean pushToTalk,
                               long frames, double level, String error) {}
    public record PlaybackState(String deviceId, String status, long frames, long dropped,
                                int queuedFrames, String error) {}
    interface Backend {
        List<Device> devices() throws Exception;
        TargetDataLine input(String id) throws Exception;
        SourceDataLine output(String id) throws Exception;
    }
    private static final AudioFormat FORMAT = new AudioFormat(48000, 16, 1, true,
            ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN);
    private static final int LINE_BYTES = VoicePeer.FRAME_BYTES * 8, OUTPUT_FRAMES = 12;
    private final Backend backend;
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), work -> {
                Thread thread = new Thread(work, "DarkWinter-audio-open"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean closed = new AtomicBoolean(), openingInput = new AtomicBoolean(), openingOutput = new AtomicBoolean();
    private final AtomicReference<Capture> capture = new AtomicReference<>();
    private final AtomicReference<Playback> playback = new AtomicReference<>();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();

    public JavaSoundVoiceDevices() { this(new SystemBackend()); }
    JavaSoundVoiceDevices(Backend backend) { this.backend = Objects.requireNonNull(backend); }

    /** Explicit enumeration only; no lines are opened. */
    public CompletableFuture<List<Device>> devices() {
        return operation(() -> List.copyOf(backend.devices()));
    }
    /** Explicit microphone enable. Acquires the selected line STOPPED; it captures only while PTT is held. */
    public CompletableFuture<Capture> openCapture(String deviceId, Consumer<byte[]> pcm10ms) {
        Objects.requireNonNull(pcm10ms); requireId(deviceId);
        if (!openingInput.compareAndSet(false, true))
            return CompletableFuture.failedFuture(new IOException("Microphone is already opening"));
        CompletableFuture<Capture> result = operation(() -> {
            if (capture.get() != null) throw new IOException("A microphone is already open");
            TargetDataLine line = backend.input(deviceId);
            try {
                line.open(FORMAT, LINE_BYTES);
                if (closed.get()) throw new IOException("Audio settings closed during microphone open");
                Capture next = new Capture(deviceId, line, pcm10ms);
                if (!capture.compareAndSet(null, next)) { next.close(); throw new IOException("Microphone already open"); }
                next.start(); return next;
            } catch (Throwable failure) { line.close(); throw failure; }
        });
        result.whenComplete((value, error) -> openingInput.set(false));
        return result;
    }
    /** Explicit output enable. No speaker line is acquired until this method is called. */
    public CompletableFuture<Playback> openPlayback(String deviceId) {
        requireId(deviceId);
        if (!openingOutput.compareAndSet(false, true))
            return CompletableFuture.failedFuture(new IOException("Audio output is already opening"));
        CompletableFuture<Playback> result = operation(() -> {
            if (playback.get() != null) throw new IOException("An audio output is already open");
            SourceDataLine line = backend.output(deviceId);
            try {
                line.open(FORMAT, LINE_BYTES);
                if (closed.get()) throw new IOException("Audio settings closed during output open");
                Playback next = new Playback(deviceId, line);
                if (!playback.compareAndSet(null, next)) { next.close(); throw new IOException("Output already open"); }
                next.start(); return next;
            } catch (Throwable failure) { line.close(); throw failure; }
        });
        result.whenComplete((value, error) -> openingOutput.set(false));
        return result;
    }
    private static void requireId(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Select an enumerated audio device");
    }
    @FunctionalInterface private interface Open<T> { T run() throws Exception; }
    private <T> CompletableFuture<T> operation(Open<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed.get()) return CompletableFuture.failedFuture(new IOException("Audio settings closed"));
        try {
            io.execute(() -> {
                if (closed.get() || result.isDone()) { result.completeExceptionally(new IOException("Audio settings closed")); return; }
                try {
                    T value = work.run();
                    if (closed.get() || !result.complete(value)) {
                        if (value instanceof AutoCloseable endpoint) endpoint.close();
                        if (closed.get()) result.completeExceptionally(new IOException("Audio settings closed"));
                    }
                } catch (Throwable failure) {
                    result.completeExceptionally(new IOException("Audio device unavailable: " + failure.getClass().getSimpleName(), failure));
                }
            });
        } catch (RejectedExecutionException busy) { result.completeExceptionally(new IOException("Audio device request queue is full")); }
        // Drivers remain off game/render threads; late successful opens are closed above.
        result.orTimeout(8, TimeUnit.SECONDS);
        return result.copy();
    }
    public CompletableFuture<Void> terminated() { return terminated.copy(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Capture input = capture.get(); Playback output = playback.get();
        if (input != null) input.close(); if (output != null) output.close();
        io.shutdownNow();
        Thread.ofVirtual().name("DarkWinter-audio-close").start(() -> {
            try {
                if (!io.awaitTermination(10, TimeUnit.SECONDS)) throw new TimeoutException("Audio driver open did not finish");
                Capture remainingInput = capture.get(); Playback remainingOutput = playback.get();
                if (remainingInput != null) { remainingInput.close(); remainingInput.terminated().get(10, TimeUnit.SECONDS); }
                if (remainingOutput != null) { remainingOutput.close(); remainingOutput.terminated().get(10, TimeUnit.SECONDS); }
                if (input != null) input.terminated().get(10, TimeUnit.SECONDS);
                if (output != null) output.terminated().get(10, TimeUnit.SECONDS);
                terminated.complete(null);
            } catch (Exception failure) { terminated.completeExceptionally(failure); }
        });
    }

    public final class Capture implements AutoCloseable {
        private record Request(long generation, boolean enabled, CompletableFuture<Void> result) {}
        private final String device;
        private final TargetDataLine line;
        private final Consumer<byte[]> consumer;
        private final AtomicBoolean stopped = new AtomicBoolean(), ptt = new AtomicBoolean();
        private final AtomicLong generation = new AtomicLong();
        private final AtomicReference<Request> request = new AtomicReference<>();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private volatile CaptureState state;
        private Thread worker;
        private Capture(String device, TargetDataLine line, Consumer<byte[]> consumer) {
            this.device = device; this.line = line; this.consumer = consumer;
            state = new CaptureState(device, "READY", false, 0, 0, "");
        }
        private void start() { worker = Thread.ofVirtual().name("DarkWinter-microphone").start(this::run); }
        /** Immediate delivery gate; returned future acknowledges line start/stop and flush on its worker. */
        public CompletableFuture<Void> setPushToTalk(boolean enabled) {
            if (stopped.get()) return CompletableFuture.failedFuture(new IOException("Microphone closed"));
            ptt.set(enabled); long next = generation.incrementAndGet();
            CompletableFuture<Void> result = new CompletableFuture<>();
            Request old = request.getAndSet(new Request(next, enabled, result));
            if (old != null && !old.result().isDone()) old.result().completeExceptionally(new CancellationException("PTT request superseded"));
            if (stopped.get()) result.completeExceptionally(new IOException("Microphone closed"));
            return result.copy();
        }
        public CaptureState state() { return state; }
        public CompletableFuture<Void> terminated() { return finished.copy(); }
        private void run() {
            boolean running = false; long applied = 0, frames = 0; String error = "";
            try {
                while (!stopped.get()) {
                    Request next = request.get();
                    if (next != null && next.generation() != applied) {
                        if (next.enabled() != running) {
                            if (next.enabled()) { line.flush(); line.start(); } else { line.stop(); line.flush(); }
                            running = next.enabled();
                        }
                        applied = next.generation();
                        state = new CaptureState(device, running ? "CAPTURING" : "READY", running, frames, 0, "");
                        next.result().complete(null);
                    }
                    if (!line.isOpen()) throw new IOException("Microphone disconnected");
                    if (!running || !ptt.get() || line.available() < VoicePeer.FRAME_BYTES) { Thread.sleep(2); continue; }
                    if (line.available() > LINE_BYTES) { line.flush(); continue; }
                    byte[] pcm = new byte[VoicePeer.FRAME_BYTES];
                    int read = line.read(pcm, 0, pcm.length);
                    if (read == pcm.length && ptt.get() && applied == generation.get() && !stopped.get()) {
                        consumer.accept(pcm); frames++;
                        state = new CaptureState(device, "CAPTURING", true, frames, VoicePcm.level(pcm), "");
                    }
                }
            } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
            catch (Throwable failure) { error = failure.getClass().getSimpleName() + ": audio capture failed"; }
            finally {
                stopped.set(true); ptt.set(false);
                try { line.stop(); } catch (Throwable ignored) {}
                try { line.flush(); } catch (Throwable ignored) {}
                try { line.close(); } catch (Throwable failure) { error = failure.getClass().getSimpleName() + ": microphone close failed"; }
                Request pending = request.getAndSet(null);
                if (pending != null) pending.result().completeExceptionally(new IOException("Microphone closed"));
                state = new CaptureState(device, error.isEmpty() ? "CLOSED" : "FAILED", false, frames, 0, error);
                capture.compareAndSet(this, null); finished.complete(null);
            }
        }
        @Override public void close() {
            ptt.set(false); generation.incrementAndGet();
            if (!stopped.compareAndSet(false, true)) return;
            if (worker != null) worker.interrupt();
            else { line.close(); finished.complete(null); }
        }
    }

    public final class Playback implements AutoCloseable {
        private final String device;
        private final SourceDataLine line;
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(OUTPUT_FRAMES);
        private final AtomicBoolean stopped = new AtomicBoolean(), flush = new AtomicBoolean();
        private final AtomicLong dropped = new AtomicLong();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private volatile PlaybackState state;
        private Thread worker;
        private Playback(String device, SourceDataLine line) {
            this.device = device; this.line = line; state = new PlaybackState(device, "READY", 0, 0, 0, "");
        }
        private void start() { worker = Thread.ofVirtual().name("DarkWinter-voice-output").start(this::run); }
        public PlaybackState state() {
            PlaybackState view = state;
            return new PlaybackState(view.deviceId(), view.status(), view.frames(), dropped.get(), queue.size(), view.error());
        }
        public CompletableFuture<Void> terminated() { return finished.copy(); }
        /** Never blocks the caller; stale audio is dropped instead of accumulating latency. */
        public boolean offerPcm10ms(byte[] pcm) {
            Objects.requireNonNull(pcm);
            if (pcm.length != VoicePeer.FRAME_BYTES) throw new IllegalArgumentException("One output frame is 960 bytes");
            if (stopped.get()) return false;
            byte[] copy = pcm.clone();
            while (!queue.offer(copy)) { if (queue.poll() != null) dropped.incrementAndGet(); }
            if (stopped.get()) { queue.remove(copy); return false; }
            return true;
        }
        public boolean offer(VoicePeer.DecodedAudio audio) {
            float[] mono = VoicePcm.mono48k(audio);
            boolean accepted = true;
            for (int offset = 0; offset < mono.length; offset += VoicePeer.FRAME_SAMPLES) {
                float[] frame = new float[VoicePeer.FRAME_SAMPLES];
                System.arraycopy(mono, offset, frame, 0, Math.min(frame.length, mono.length - offset));
                accepted &= offerPcm10ms(VoicePcm.pcm16(frame, 1));
            }
            return accepted;
        }
        public void clear() { queue.clear(); flush.set(true); }
        private void run() {
            long frames = 0; String error = "";
            try {
                line.start();
                while (!stopped.get()) {
                    if (flush.getAndSet(false)) line.flush();
                    if (!line.isOpen()) throw new IOException("Output disconnected");
                    if (line.available() < VoicePeer.FRAME_BYTES) { Thread.sleep(2); continue; }
                    byte[] pcm = queue.poll(5, TimeUnit.MILLISECONDS); if (pcm == null) continue;
                    if (stopped.get()) break;
                    int written = line.write(pcm, 0, pcm.length);
                    if (written != pcm.length) throw new IOException("Output did not consume one frame");
                    frames++; state = new PlaybackState(device, "PLAYING", frames, dropped.get(), queue.size(), "");
                }
            } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
            catch (Throwable failure) { error = failure.getClass().getSimpleName() + ": audio playback failed"; }
            finally {
                stopped.set(true); queue.clear();
                try { line.stop(); } catch (Throwable ignored) {}
                try { line.flush(); } catch (Throwable ignored) {}
                try { line.close(); } catch (Throwable failure) { error = failure.getClass().getSimpleName() + ": output close failed"; }
                state = new PlaybackState(device, error.isEmpty() ? "CLOSED" : "FAILED", frames, dropped.get(), 0, error);
                playback.compareAndSet(this, null); finished.complete(null);
            }
        }
        @Override public void close() {
            if (!stopped.compareAndSet(false, true)) return;
            queue.clear(); if (worker != null) worker.interrupt();
            else { line.close(); finished.complete(null); }
        }
    }

    private static final class SystemBackend implements Backend {
        private record NativeDevice(Device view, Mixer.Info mixer) {}
        private List<NativeDevice> inventory() throws Exception {
            List<NativeDevice> found = new ArrayList<>();
            Map<String, Integer> duplicates = new HashMap<>();
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                Mixer mixer = AudioSystem.getMixer(info);
                boolean input = mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, FORMAT));
                boolean output = mixer.isLineSupported(new DataLine.Info(SourceDataLine.class, FORMAT));
                if (!input && !output) continue;
                String key = info.getName() + "\n" + info.getVendor() + "\n" + info.getDescription() + "\n" + info.getVersion();
                int ordinal = duplicates.merge(key, 1, Integer::sum);
                String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest((key + "\n" + ordinal).getBytes(StandardCharsets.UTF_8)));
                String name = info.getName().replaceAll("[\\p{Cntrl}]", " ");
                if (name.length() > 160) name = name.substring(0, 160);
                found.add(new NativeDevice(new Device(id, name, input, output), info));
                if (found.size() > 128) throw new IOException("Too many audio devices");
            }
            return found;
        }
        @Override public List<Device> devices() throws Exception { return inventory().stream().map(NativeDevice::view).toList(); }
        private Mixer find(String id, boolean input) throws Exception {
            for (NativeDevice device : inventory())
                if (device.view().id().equals(id) && (input ? device.view().input() : device.view().output()))
                    return AudioSystem.getMixer(device.mixer());
            throw new IOException("Selected audio device is disconnected or does not support PCM48k");
        }
        @Override public TargetDataLine input(String id) throws Exception {
            return (TargetDataLine) find(id, true).getLine(new DataLine.Info(TargetDataLine.class, FORMAT));
        }
        @Override public SourceDataLine output(String id) throws Exception {
            return (SourceDataLine) find(id, false).getLine(new DataLine.Info(SourceDataLine.class, FORMAT));
        }
    }
}
