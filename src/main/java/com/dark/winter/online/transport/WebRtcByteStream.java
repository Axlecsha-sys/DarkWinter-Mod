// Ordered bounded byte stream; segment boundaries are not application message boundaries.
package com.dark.winter.online.transport;

import java.util.concurrent.CompletableFuture;

/** Ordered bounded byte stream; segment boundaries are not application message boundaries. */
public interface WebRtcByteStream extends AutoCloseable {
    CompletableFuture<Void> opened();
    CompletableFuture<Void> closed();
    /** Completion means queued to transport, never an application consumption/replica READY acknowledgment. */
    CompletableFuture<Void> send(byte[] bytes);
    CompletableFuture<byte[]> read();
    /** Ordered half-close, acknowledged only after the remote application consumes its EOF. */
    CompletableFuture<Void> finishOutput();
    /** Requires both consumed EOFs and their acknowledgments; closes this logical stream only. */
    CompletableFuture<Void> closeGracefully();
    @Override void close();
}
