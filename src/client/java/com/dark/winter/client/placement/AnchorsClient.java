package com.dark.winter.client.placement;

import com.dark.winter.placement.AnchorPayload;
import com.dark.winter.placement.AnchorRuntime;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/** Publishes authoritative anchor snapshots before invalidating affected native section geometry. */
public final class AnchorsClient {
    private static boolean initialized;
    private AnchorsClient() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> AnchorRuntime.clearClient());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> AnchorRuntime.clearClient());
        ClientPlayNetworking.registerGlobalReceiver(AnchorPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    var client = context.client();
                    var level = client.level;
                    if (level == null) return;
                    var dirty = AnchorRuntime.publishClient(level, payload);
                    if (dirty.isEmpty()) return;
                    // A multipart anchor can touch many cells in the same section. Rebuild that section once.
                    var sections = new LongOpenHashSet();
                    for (BlockPos pos : dirty) sections.add(SectionPos.asLong(pos));
                    var iterator = sections.iterator();
                    while (iterator.hasNext()) {
                        long section = iterator.nextLong();
                        client.levelExtractor.setSectionDirtyWithNeighbors(
                                SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
                    }
                }));
    }
}
