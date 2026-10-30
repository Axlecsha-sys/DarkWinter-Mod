// One loopback TCP connection bridged to one authenticated WebRTC byte stream.
package com.dark.winter.online.transport;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * One loopback TCP connection bridged to one authenticated WebRTC byte stream.
 * Raw-link overloads retain physical ownership; logical-stream overloads own only that stream.
 * Its parent must explicitly close the multiplex on Binding.closed() for a real game disconnect.
 * attach() never forwards bytes until start(): register its ephemeral ingress address first.
 * No DNS, remote proxy targets, Minecraft packet parsing or trust-by-loopback is implemented.
 */
public final class WebRtcSocketBridge {
    private WebRtcSocketBridge() {}
    private static final Semaphore SLOTS = new Semaphore(32);
    private static final InetAddress LOOPBACK;
    static {
        try { LOOPBACK = InetAddress.getByAddress(new byte[]{127, 0, 0, 1}); }
        catch (UnknownHostException impossible) { throw new ExceptionInInitializerError(impossible); }
    }
    public record Options(Duration connectTimeout, Duration acceptTimeout, Duration readyTimeout, Duration writeTimeout,
                          Duration eofIdleTimeout, Duration eofOverallTimeout) {
        /** Source-compatible constructor; a half-close may continue while actual bytes still progress. */
        public Options(Duration connectTimeout, Duration acceptTimeout, Duration readyTimeout, Duration writeTimeout) {
            this(connectTimeout,acceptTimeout,readyTimeout,writeTimeout,writeTimeout,Duration.ofMinutes(2));
        }
        public Options {
            for (Duration value : new Duration[]{connectTimeout, acceptTimeout, readyTimeout, writeTimeout, eofIdleTimeout, eofOverallTimeout})
                if (value == null || value.compareTo(Duration.ofSeconds(1)) < 0 || value.compareTo(Duration.ofMinutes(2)) > 0)
                    throw new IllegalArgumentException("Bridge timeout bounds");
            if(eofIdleTimeout.compareTo(eofOverallTimeout)>0)throw new IllegalArgumentException("Bridge EOF idle exceeds overall deadline");
        }
        public static Options defaults() { return new Options(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30)); }
    }
    public record Stats(long socketToLinkBytes, long linkToSocketBytes, boolean started, boolean closing, String closeReason,
                        String socketPump, String linkPump) {}

    public static CompletableFuture<Binding> attach(WebRtcLink link, InetSocketAddress nativeLoopbackGamePort) {
        return attach(link, nativeLoopbackGamePort, Options.defaults());
    }
    public static CompletableFuture<Binding> attach(WebRtcLink link, InetSocketAddress target, Options options) {
        return attach(new RawStream(Objects.requireNonNull(link)), target, options);
    }
    /** Owns only the supplied logical stream, never an unseen parent multiplex. */
    public static CompletableFuture<Binding> attach(WebRtcByteStream stream, InetSocketAddress target) {
        return attach(stream, target, Options.defaults());
    }
    public static CompletableFuture<Binding> attach(WebRtcByteStream link, InetSocketAddress target, Options options) {
        Objects.requireNonNull(link); Objects.requireNonNull(options);
        if (target == null || target.isUnresolved() || target.getAddress() == null || !target.getAddress().isLoopbackAddress()
                || target.getPort() < 1)
            return CompletableFuture.failedFuture(new IOException("Game bridge target must be an explicit resolved loopback TCP endpoint"));
        if (!SLOTS.tryAcquire()) return CompletableFuture.failedFuture(new IOException("Loopback bridge capacity reached"));
        Binding binding = new Binding(link, options, null);
        CompletableFuture<Binding> result = new CompletableFuture<>();
        result.whenComplete((value, failure) -> { if (result.isCancelled()) binding.requestClose("CANCELLED"); });
        binding.connected.whenComplete((value, failure) -> { if (failure != null) result.completeExceptionally(failure); });
        binding.run(() -> {
            try {
                Socket socket = new Socket();
                binding.socket = socket;
                if (binding.closing.get()) { socket.close(); return; }
                InetAddress local = target.getAddress() instanceof Inet6Address
                        ? InetAddress.getByAddress(new byte[]{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1}) : LOOPBACK;
                socket.bind(new InetSocketAddress(local, 0));
                socket.setTcpNoDelay(true);
                socket.connect(target, (int) options.connectTimeout().toMillis());
                binding.address = (InetSocketAddress) socket.getLocalSocketAddress();
                if (binding.closing.get()) { socket.close(); return; }
                // Do not start pumps here: caller must register upstreamLocalAddress in the authenticated ingress map.
                result.complete(binding);
                binding.readyAndPump(socket);
            } catch (Throwable failure) {
                result.completeExceptionally(new IOException("Loopback game connection failed", failure));
                binding.requestClose("CONNECT_FAILED");
            } finally {
                if (!result.isDone()) result.completeExceptionally(new IOException("Bridge closed before connection"));
            }
        });
        binding.watchLink();
        return result;
    }

    /** Returns a bound 127.0.0.1 ephemeral address before the single local Minecraft accept completes. */
    public static Binding listen(WebRtcLink link) throws IOException { return listen(link, Options.defaults()); }
    public static Binding listen(WebRtcLink link, Options options) throws IOException {
        return listen(new RawStream(Objects.requireNonNull(link)), options);
    }
    /** Owns only the supplied logical stream; the caller remains the physical connection owner. */
    public static Binding listen(WebRtcByteStream stream) throws IOException { return listen(stream, Options.defaults()); }
    public static Binding listen(WebRtcByteStream link, Options options) throws IOException {
        Objects.requireNonNull(link); Objects.requireNonNull(options);
        if (!SLOTS.tryAcquire()) throw new IOException("Loopback bridge capacity reached");
        ServerSocket listener = null;
        try {
            listener = new ServerSocket();
            listener.bind(new InetSocketAddress(LOOPBACK, 0), 1);
            listener.setSoTimeout((int) options.acceptTimeout().toMillis());
            Binding binding = new Binding(link, options, listener);
            binding.address = (InetSocketAddress) listener.getLocalSocketAddress();
            binding.run(() -> {
                try {
                    Socket socket = binding.listener.accept();
                    binding.socket = socket;
                    binding.listener.close(); // Exactly one local client; second connections cannot enter this link.
                    if (binding.closing.get()) { socket.close(); return; }
                    if (!socket.getInetAddress().isLoopbackAddress()) throw new IOException("Non-loopback local client");
                    socket.setTcpNoDelay(true);
                    binding.readyAndPump(socket);
                } catch (Throwable failure) { binding.requestClose(failure instanceof SocketTimeoutException ? "ACCEPT_TIMEOUT" : "ACCEPT_FAILED"); }
            });
            binding.watchLink();
            return binding;
        } catch (IOException | RuntimeException failure) {
            if (listener != null) try { listener.close(); } catch (IOException ignored) {}
            SLOTS.release(); throw failure;
        }
    }

    /** Private legacy adapter: closing the raw binding still closes its physical WebRtcLink. */
    private record RawStream(WebRtcLink link) implements WebRtcByteStream {
        @Override public CompletableFuture<Void> opened(){return link.opened();}
        @Override public CompletableFuture<Void> closed(){return link.closed();}
        @Override public CompletableFuture<Void> send(byte[] bytes){return link.send(bytes);}
        @Override public CompletableFuture<byte[]> read(){return link.read();}
        @Override public CompletableFuture<Void> finishOutput(){return link.finishOutput();}
        @Override public CompletableFuture<Void> closeGracefully(){return link.closeGracefully();}
        @Override public void close(){link.close();}
    }

    public static final class Binding implements AutoCloseable {
        private final WebRtcByteStream link;
        private final Options options;
        private final ServerSocket listener;
        private volatile Socket socket;
        private volatile Thread socketWorker,linkWorker;
        private volatile InetSocketAddress address;
        private final AtomicBoolean closing = new AtomicBoolean(), socketEnded = new AtomicBoolean(), linkEnded = new AtomicBoolean(), graceStarted = new AtomicBoolean(), gracefulRequested = new AtomicBoolean();
        private final AtomicInteger tasks = new AtomicInteger();
        private final AtomicLong socketToLink = new AtomicLong(), linkToSocket = new AtomicLong(), lastByteOrEof = new AtomicLong(System.nanoTime());
        private final CompletableFuture<Void> started = new CompletableFuture<>(), connected = new CompletableFuture<>(), closed = new CompletableFuture<>(), pumpsEnded = new CompletableFuture<>();
        private volatile String closeReason = "", socketPhase = "WAIT_START", linkPhase = "WAIT_START";
        private volatile boolean pumping;

        private Binding(WebRtcByteStream link, Options options, ServerSocket listener) {
            this.link = link; this.options = options; this.listener = listener;
        }
        private void watchLink() {
            link.closed().whenComplete((unused, failure) -> requestClose(gracefulRequested.get() ? "GRACEFUL_EOF" : "LINK_CLOSED"));
        }
        /** Listener address on the client; connected outbound socket's local endpoint on the server. */
        public InetSocketAddress localAddress() {
            InetSocketAddress value = address;
            if (value == null) throw new IllegalStateException("Loopback endpoint is not yet bound");
            return value;
        }
        /** Server-only admission key. Register this address against the verified identity before calling start(). */
        public InetSocketAddress upstreamLocalAddress() {
            if (listener != null) throw new IllegalStateException("Client listener has no upstream game connection");
            return localAddress();
        }
        public CompletableFuture<Void> connected() { return connected.copy(); }
        /** Completes after bridge worker tasks exit and sockets are closed. */
        public CompletableFuture<Void> closed() { return closed.copy(); }
        public Stats stats() { return new Stats(socketToLink.get(), linkToSocket.get(), pumping, closing.get(), closeReason, socketPhase, linkPhase); }
        /** Bounded own-worker diagnostics only: class/method/line, no arguments, payload or endpoint. */
        public record WorkerTrace(String role,boolean available,boolean virtual,String state,java.util.List<String> frames) {}
        public java.util.List<WorkerTrace> workerTraces(){return java.util.List.of(trace("socket",socketWorker),trace("link",linkWorker));}
        private static WorkerTrace trace(String role,Thread worker){
            if(worker==null)return new WorkerTrace(role,false,false,"",java.util.List.of());
            return new WorkerTrace(role,true,worker.isVirtual(),worker.getState().name(),
                    java.util.Arrays.stream(worker.getStackTrace()).limit(12).map(frame->frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber()).toList());
        }
        public CompletableFuture<Void> start() {
            if (closing.get()) return CompletableFuture.failedFuture(new IOException("Bridge is closing"));
            started.complete(null);
            return connected();
        }

        private void readyAndPump(Socket active) {
            try {
                started.get(options.readyTimeout().toMillis(), TimeUnit.MILLISECONDS);
                link.opened().get(options.readyTimeout().toMillis(), TimeUnit.MILLISECONDS);
                if (closing.get()) return;
                pumping = true;
                // Pump workers use their own virtual threads; no blocking I/O or get/join on JNI, Netty or game threads.
                run(() -> socketToLink(active));
                run(() -> linkToSocket(active));
                connected.complete(null);
            } catch (Throwable failure) { requestClose("READY_TIMEOUT_OR_CLOSED"); }
        }
        private void socketToLink(Socket active) {
            socketWorker=Thread.currentThread();
            byte[] buffer = new byte[16 * 1024];
            try {
                InputStream input = active.getInputStream();
                while (!closing.get()) {
                    socketPhase="READ_SOCKET";int count = input.read(buffer);
                    if (count < 0) {
                        progress();beginGraceDeadline();
                        socketPhase="FIN_ACK";link.finishOutput().get(options.writeTimeout().toMillis(), TimeUnit.MILLISECONDS);
                        socketPhase="EOF";socketEnded.set(true);progress();finishPumps();return;
                    }
                    if (count == 0) continue;
                    byte[] copy = java.util.Arrays.copyOf(buffer, count);
                    socketPhase="WRITE_LINK";link.send(copy).get(options.writeTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    socketToLink.addAndGet(count);progress();
                }
            } catch (Throwable failure) { socketPhase="FAILED_"+socketPhase;requestClose("SOCKET_READ_OR_LINK_WRITE_FAILED"); }
            finally{socketWorker=null;}
        }
        private void linkToSocket(Socket active) {
            linkWorker=Thread.currentThread();
            try {
                OutputStream output = active.getOutputStream();
                while (!closing.get()) {
                    linkPhase="READ_LINK";byte[] bytes = link.read().get();
                    if (bytes == null) {
                        progress();beginGraceDeadline();
                        linkPhase="SHUTDOWN_SOCKET_OUTPUT";active.shutdownOutput();
                        linkPhase="EOF";linkEnded.set(true);progress();finishPumps();return;
                    }
                    if (bytes.length == 0 || bytes.length > 16 * 1024) throw new IOException("Unexpected stream segment size");
                    linkPhase="WRITE_SOCKET";output.write(bytes);
                    linkToSocket.addAndGet(bytes.length);progress();
                }
            } catch (Throwable failure) { linkPhase="FAILED_"+linkPhase;requestClose("LINK_READ_OR_SOCKET_WRITE_FAILED"); }
            finally{linkWorker=null;}
        }
        private void progress(){lastByteOrEof.accumulateAndGet(System.nanoTime(),Math::max);}
        private void beginGraceDeadline() {
            if (!graceStarted.compareAndSet(false, true)) return;
            long began=System.nanoTime();
            run(() -> {
                try {
                    // TCP permits a response after request-side EOF. Only actual byte/EOF events
                    // extend the idle deadline; the overall deadline is fixed and cannot be pinged away.
                    while(!closing.get()) {
                        long now=System.nanoTime();
                        if(now-began>=options.eofOverallTimeout().toNanos()){requestClose("EOF_OVERALL_TIMEOUT");return;}
                        if(now-lastByteOrEof.get()>=options.eofIdleTimeout().toNanos()){requestClose("EOF_IDLE_TIMEOUT");return;}
                        Thread.sleep(25);
                    }
                } catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();requestClose("EOF_WATCH_INTERRUPTED");
                }
            });
        }
        private void finishPumps() {
            if (socketEnded.get() && linkEnded.get() && gracefulRequested.compareAndSet(false, true)) {
                link.closeGracefully().whenComplete((unused,failure)->{if(failure!=null)requestClose("EOF_ACK_FAILED");});
                pumpsEnded.complete(null);
            }
        }
        private void run(Runnable operation) {
            tasks.incrementAndGet();
            runReserved(operation);
        }
        private void runReserved(Runnable operation) {
            try {
                Thread.ofVirtual().name("DarkWinter-RTC-Socket").start(() -> {
                    try { operation.run(); }
                    finally { tasks.decrementAndGet(); finishClosed(); }
                });
            } catch (Throwable failure) {
                closeSocketsNow();
                tasks.decrementAndGet(); requestClose("WORKER_FAILED"); finishClosed();
            }
        }
        private void requestClose(String reason) {
            // Reserve cleanup BEFORE publishing closing or completing futures that can wake the final pump.
            tasks.incrementAndGet();
            if (!closing.compareAndSet(false, true)) { tasks.decrementAndGet(); finishClosed(); return; }
            closeReason = reason;
            IOException failure = new IOException("Loopback stream bridge closed: " + reason);
            started.completeExceptionally(failure); connected.completeExceptionally(failure); pumpsEnded.completeExceptionally(failure);
            link.close();
            // Socket.close may unblock native OS I/O: keep it off a native WebRTC future completion thread.
            runReserved(this::closeSocketsNow);
        }
        private void closeSocketsNow() {
            try { if (listener != null) listener.close(); } catch (IOException ignored) {}
            try { Socket active = socket; if (active != null) active.close(); } catch (IOException ignored) {}
        }
        private void finishClosed() {
            if (closing.get() && tasks.get() == 0 && closed.complete(null)) SLOTS.release();
        }
        @Override public void close() { requestClose("APPLICATION_CLOSED"); }
    }
}
