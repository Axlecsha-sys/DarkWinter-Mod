// Bounded, reliable ordered bytes with one physical reader.
package com.dark.winter.online.transport;

import java.util.concurrent.CompletableFuture;

/**
 * Bounded, reliable ordered bytes with one physical reader.
 * Transport connectivity grants no DarkWinter identity, invitation, ACL or host authority.
 * The caller must finish the session-bound peer authentication before handing this to a multiplexer.
 */
public interface ReliableTransportLink extends AutoCloseable {
    CompletableFuture<Void> opened();
    CompletableFuture<Void> closed();
    CompletableFuture<Void> send(byte[] bytes);
    CompletableFuture<byte[]> read();
    @Override void close();
}
