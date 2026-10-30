// Full-body size/deadline limits for JDK 25 HTTP.
package com.dark.winter.online.node;

import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full-body size/deadline limits for JDK 25 HTTP. No parser, identity mutation or service endpoint selection.
 * read() is for the JDK HttpServer's virtual request workers (interruptible SocketChannel input).
 */
public final class OnlineHttpBodies {
    private OnlineHttpBodies() {}
    private static final int MAX_BODY = 1024 * 1024, MAX_ACTIVE = 128;
    private static final Semaphore ACTIVE = new Semaphore(MAX_ACTIVE);
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "DarkWinter-HTTP-deadlines"); thread.setDaemon(true); return thread;
    });
    static {
        TIMER.setRemoveOnCancelPolicy(true);
        TIMER.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }
    private static long validate(int limit, Duration timeout) {
        Objects.requireNonNull(timeout);
        if (limit < 1 || limit > MAX_BODY || timeout.compareTo(Duration.ofMillis(10)) < 0 || timeout.compareTo(Duration.ofMinutes(2)) > 0)
            throw new IllegalArgumentException("HTTP body bounds");
        return timeout.toNanos();
    }

    /** Deadline covers the entire bounded response, including a node that sends headers then stalls. */
    public static CompletableFuture<HttpResponse<byte[]>> send(HttpClient client, HttpRequest request, int limit, Duration timeout) {
        Objects.requireNonNull(client); Objects.requireNonNull(request);
        final long nanos = validate(limit, timeout);
        if (!ACTIVE.tryAcquire()) return CompletableFuture.failedFuture(new IOException("HTTP body capacity reached"));
        CompletableFuture<HttpResponse<byte[]>> actual;
        try { actual = client.sendAsync(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), limit)); }
        catch (Throwable failure) { ACTIVE.release(); return CompletableFuture.failedFuture(failure); }
        CompletableFuture<HttpResponse<byte[]>> result = new CompletableFuture<>();
        AtomicBoolean expired = new AtomicBoolean();
        // Cancel actual I/O before publishing timeout. Application continuations never execute on the timer.
        ScheduledFuture<?> watchdog = TIMER.schedule(() -> Thread.ofVirtual().name("DarkWinter-HTTP-cancel").start(() -> {
            if (!actual.isDone() && !result.isDone()) {
                expired.set(true);
                actual.cancel(true);
                result.completeExceptionally(new HttpTimeoutException("HTTP response body deadline exceeded"));
            }
        }), nanos, TimeUnit.NANOSECONDS);
        actual.whenComplete((response, failure) -> Thread.ofVirtual().name("DarkWinter-HTTP-result").start(() -> {
            if (expired.get()) result.completeExceptionally(new HttpTimeoutException("HTTP response body deadline exceeded"));
            else if (failure == null) result.complete(response);
            else result.completeExceptionally(failure);
        }));
        result.whenComplete((response, failure) -> {
            watchdog.cancel(false);
            if (result.isCancelled()) actual.cancel(true);
            ACTIVE.release();
        });
        return result;
    }

    private static final class ReadGate { boolean done, expired; }
    /**
     * A watchdog interrupts the reading virtual worker; JDK HttpServer reads its blocking SocketChannel.
     * It deliberately does not call HttpExchange.close() on the watchdog: request-stream close may drain/block.
     * The reader's caller still owns the normal exchange.close() in its finally block.
     */
    public static byte[] read(HttpExchange exchange, int limit, Duration timeout) throws IOException {
        Objects.requireNonNull(exchange);
        long nanos = validate(limit, timeout);
        Thread reader = Thread.currentThread();
        if (!reader.isVirtual()) throw new IOException("Bounded HTTP request bodies require a virtual JDK HttpServer worker");
        if (reader.isInterrupted()) throw new InterruptedIOException("HTTP request worker was interrupted");
        if (!ACTIVE.tryAcquire()) throw new IOException("HTTP body capacity reached");
        ReadGate gate = new ReadGate();
        ScheduledFuture<?> watchdog = TIMER.schedule(() -> {
            synchronized (gate) {
                if (!gate.done) { gate.expired = true; reader.interrupt(); }
            }
        }, nanos, TimeUnit.NANOSECONDS);
        byte[] bytes = null;
        IOException problem = null;
        boolean oversized = false;
        try {
            bytes = exchange.getRequestBody().readNBytes(limit + 1);
            if (bytes.length > limit) { oversized = true; problem = new IOException("HTTP request body exceeds its size limit"); }
        } catch (IOException failure) { problem = failure; }
        finally {
            synchronized (gate) {
                gate.done = true; watchdog.cancel(false);
                if (gate.expired) {
                    // Clear only the interrupt introduced by this deadline, after the channel read has aborted.
                    Thread.interrupted();
                    problem = new InterruptedIOException("HTTP request body deadline exceeded");
                }
            }
            ACTIVE.release();
        }
        if (oversized) {
            // A normal exchange.close() may drain a malicious unfinished oversized body.
            // Interrupt FIRST so any draining SocketChannel read aborts rather than blocking this worker.
            reader.interrupt();
            try { exchange.close(); } finally { Thread.interrupted(); }
        }
        if (problem != null) throw problem;
        return bytes;
    }

    /** Reject an unread request without blocking to drain a peer's claimed unfinished body. */
    public static void abort(HttpExchange exchange) {
        Objects.requireNonNull(exchange);
        Thread worker = Thread.currentThread();
        if (!worker.isVirtual()) throw new IllegalStateException("HTTP abort requires a virtual JDK request worker");
        boolean wasInterrupted = worker.isInterrupted();
        worker.interrupt();
        try { exchange.close(); } finally { if (!wasInterrupted) Thread.interrupted(); }
    }

    /** Diagnostic count; does not retain clients, exchanges or profile state. */
    public static int activeOperations() { return MAX_ACTIVE - ACTIVE.availablePermits(); }
}
