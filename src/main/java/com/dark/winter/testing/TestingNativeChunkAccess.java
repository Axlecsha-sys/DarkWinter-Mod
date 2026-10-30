// Owner-thread access to vanilla's one-chunk snapshot/save scheduling, without waiting for disk I/O.
package com.dark.winter.testing;
import net.minecraft.world.level.chunk.ChunkAccess;
/** Owner-thread access to vanilla's one-chunk snapshot/save scheduling, without waiting for disk I/O. */
public interface TestingNativeChunkAccess {
    boolean darkwinter$save(ChunkAccess chunk);
}
