// Bounded, non-ticking preparation around the loaded singleplayer owner's actual saved position.
package com.dark.winter.loading;

import com.dark.winter.DarkWinter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/** Bounded, non-ticking preparation around the loaded singleplayer owner's actual saved position. */
public final class WorldPreloadServer {
    public static final int RADIUS = 25;
    public static final int TOTAL_CHUNKS = (RADIUS * 2 + 1) * (RADIUS * 2 + 1);
    public static final int MAX_IN_FLIGHT = 12;
    private static final int STARTS_PER_TICK = 24;
    private static final Map<MinecraftServer, Job> JOBS = new IdentityHashMap<>();
    private static volatile Published published;
    private static TicketType preloadTicket;

    private WorldPreloadServer() { }

    public static void init() {
        // No PERSIST, SIMULATION or KEEP_DIMENSION_ACTIVE flags. Radius zero gives FULL level 33.
        preloadTicket = Registry.register(BuiltInRegistries.TICKET_TYPE,
                Identifier.fromNamespaceAndPath("darkwinter", "world_preload"),
                new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING));
        PayloadTypeRegistry.clientboundPlay().register(WorldPreloadPayload.TYPE, WorldPreloadPayload.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            start(handler.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            // Local connection shutdown can fire on a Netty IO thread. TicketStorage, the job map,
            // and TickRateManager belong to the server thread, including during cancellation.
            ServerPlayer disconnected = handler.player;
            server.execute(() -> {
                Job job = JOBS.get(server);
                if (job != null && job.player == disconnected) cancel(server);
            });
        });
        ServerTickEvents.END_SERVER_TICK.register(WorldPreloadServer::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(WorldPreloadServer::cancel);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            JOBS.remove(server);
            Published value = published;
            if (value != null && value.server == server) published = null;
        });
    }

    /** Called on the server thread, after vanilla has restored the player and their dimension. */
    public static boolean start(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        // Fabric 26.3 fires JOIN before PlayerList adds this player. A fresh owner can therefore
        // see an empty list here; reject other players rather than requiring an already inserted owner.
        if (!server.isSingleplayer() || server.isPublished() || !server.isSingleplayerOwner(player.nameAndId())
                || server.getPlayerList().getPlayers().stream().anyMatch(other -> other != player)
                || !ServerPlayNetworking.canSend(player, WorldPreloadPayload.TYPE)) return false;
        Job previous = JOBS.get(server);
        if (previous != null && previous.active()) return false;
        Job job = new Job(player);
        JOBS.put(server, job);
        server.tickRateManager().setFrozen(true);
        publish(job, true);
        DarkWinter.LOGGER.info("Preparing {} chunks around {}, {} in {} (at most {} loading tickets)",
                job.chunks.size(), job.center.x(), job.center.z(), job.level.dimension().identifier(), MAX_IN_FLIGHT);
        return true;
    }

    /** A coherent immutable bridge for the integrated client's very first loading frame. */
    public static WorldPreloadPayload currentProgress(MinecraftServer server) {
        Published value = published;
        return value != null && value.server == server ? value.progress : null;
    }

    /** Server-thread guard used by the two narrowly scoped login safety Mixins. */
    public static boolean isPreparing(ServerPlayer player) {
        Job job = JOBS.get(player.level().getServer());
        return job != null && job.player == player && job.active();
    }

    /** Read on the server thread; retained through READY so integration tests can inspect cleanup. */
    public static Diagnostics diagnostics(MinecraftServer server) {
        Job job = JOBS.get(server);
        return job == null ? null : new Diagnostics(job.progress(), job.peakInFlight, job.inFlight.size(),
                job.startedCount, job.residentCount, job.frozenBefore, job.level.getChunkSource().getLoadedChunksCount());
    }

    public record Diagnostics(WorldPreloadPayload progress, int peakInFlight, int heldTickets,
                              int requestedCount, int residentCount, boolean frozenBefore, int loadedChunks) { }

    public static void cancel(MinecraftServer server) {
        Job job = JOBS.get(server);
        if (job != null && job.active()) finish(job, WorldPreloadPayload.Stage.CANCELLED, true);
    }

    private static void tick(MinecraftServer server) {
        Job job = JOBS.get(server);
        if (job == null || !job.active()) return;
        if (job.player.isRemoved() || job.player.level() != job.level || server.isPublished()
                || server.getPlayerList().getPlayerCount() != 1) {
            finish(job, WorldPreloadPayload.Stage.CANCELLED, true);
            return;
        }
        try {
            if (job.stage == WorldPreloadPayload.Stage.SAVING) {
                // Vanilla serializes and flushes generated chunks. The client keeps drawing its loading screen.
                // This happens once, after all own loading tickets have already been released.
                job.level.getChunkSource().save(true);
                finish(job, WorldPreloadPayload.Stage.READY, true);
                return;
            }
            ServerChunkCache chunks = job.level.getChunkSource();
            var pending = job.inFlight.entrySet().iterator();
            while (pending.hasNext()) {
                var request = pending.next();
                if (!request.getValue().isDone()) continue;
                Object value = request.getValue().join(); // Never block the server tick on generation.
                if (!(value instanceof ChunkResult<?> result) || !result.isSuccess()) {
                    throw new IllegalStateException("Chunk preparation failed at " + request.getKey() + ": " + value);
                }
                chunks.removeTicketWithRadius(preloadTicket, request.getKey(), 0);
                pending.remove();
                job.completed++;
            }
            int starts = 0;
            while (job.next < job.chunks.size() && job.inFlight.size() < MAX_IN_FLIGHT && starts++ < STARTS_PER_TICK) {
                ChunkPos pos = job.chunks.get(job.next++);
                if (chunks.getChunkNow(pos.x(), pos.z()) != null) {
                    job.completed++;
                    job.residentCount++;
                    continue;
                }
                // Unlike getChunkFuture(), this public 26.3 API does not managedBlock on the server thread.
                CompletableFuture<?> future;
                try {
                    future = chunks.addTicketAndLoadWithRadius(preloadTicket, pos, 0);
                } catch (RuntimeException ex) {
                    chunks.removeTicketWithRadius(preloadTicket, pos, 0);
                    throw ex;
                }
                job.inFlight.put(pos, future);
                job.startedCount++;
                job.peakInFlight = Math.max(job.peakInFlight, job.inFlight.size());
            }
            if (job.completed == job.chunks.size()) {
                job.stage = WorldPreloadPayload.Stage.SAVING;
                publish(job, true);
            } else if (++job.ticksSinceUpdate >= 5) {
                publish(job, true);
            }
        } catch (RuntimeException ex) {
            DarkWinter.LOGGER.error("World preparation stopped; temporary loading tickets have been released", ex);
            finish(job, WorldPreloadPayload.Stage.FAILED, true);
        }
    }

    private static void finish(Job job, WorldPreloadPayload.Stage result, boolean send) {
        ServerChunkCache chunks = job.level.getChunkSource();
        for (ChunkPos pos : job.inFlight.keySet()) chunks.removeTicketWithRadius(preloadTicket, pos, 0);
        // Do not cancel vanilla's shared generation futures: a player or another mod may still need them.
        job.inFlight.clear();
        job.stage = result;
        job.server.tickRateManager().setFrozen(job.frozenBefore);
        publish(job, send);
        DarkWinter.LOGGER.info("World preparation {}: {}/{} chunks in {} ms; peak {} tickets, 0 held",
                result, job.completed, job.chunks.size(), job.elapsedMillis(), job.peakInFlight);
    }

    private static void publish(Job job, boolean send) {
        WorldPreloadPayload progress = job.progress();
        published = new Published(job.server, progress);
        job.ticksSinceUpdate = 0;
        if (send && !job.player.isRemoved() && job.player.connection.isAcceptingMessages()
                && ServerPlayNetworking.canSend(job.player, WorldPreloadPayload.TYPE)) {
            ServerPlayNetworking.send(job.player, progress);
        }
    }

    private record Published(MinecraftServer server, WorldPreloadPayload progress) { }

    private static final class Job {
        final ServerPlayer player;
        final MinecraftServer server;
        final ServerLevel level;
        final ChunkPos center;
        final List<ChunkPos> chunks;
        final Map<ChunkPos, CompletableFuture<?>> inFlight = new LinkedHashMap<>();
        final boolean frozenBefore;
        final long startedNanos = System.nanoTime();
        int completed, next, peakInFlight, startedCount, residentCount, ticksSinceUpdate;
        WorldPreloadPayload.Stage stage = WorldPreloadPayload.Stage.LOADING;

        Job(ServerPlayer player) {
            this.player = player;
            this.level = player.level();
            this.server = level.getServer();
            this.center = player.chunkPosition();
            this.frozenBefore = server.tickRateManager().isFrozen();
            List<ChunkPos> ordered = new ArrayList<>(TOTAL_CHUNKS);
            for (int z = -RADIUS; z <= RADIUS; z++) for (int x = -RADIUS; x <= RADIUS; x++) {
                ordered.add(new ChunkPos(center.x() + x, center.z() + z));
            }
            ordered.sort(Comparator.comparingInt((ChunkPos pos) -> {
                int dx = pos.x() - center.x(), dz = pos.z() - center.z();
                return dx * dx + dz * dz;
            }).thenComparingInt(ChunkPos::z).thenComparingInt(ChunkPos::x));
            this.chunks = List.copyOf(ordered);
        }

        boolean active() { return stage == WorldPreloadPayload.Stage.LOADING || stage == WorldPreloadPayload.Stage.SAVING; }
        long elapsedMillis() { return (System.nanoTime() - startedNanos) / 1_000_000L; }
        WorldPreloadPayload progress() {
            return new WorldPreloadPayload(completed, chunks.size(), inFlight.size(), center.x(), center.z(),
                    level.dimension().identifier().toString(), stage, elapsedMillis());
        }
    }
}
