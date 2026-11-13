package com.winterkore.engine.surface.host;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** Owner-thread maintenance over a bounded residency set. LRU reads and eviction may mutate live map order. */
public final class SnowResidencyCycle {
    private SnowResidencyCycle() {}
    public static <T> void forEachCurrent(Map<Long,T> residents,int maximum,BiConsumer<Long,T> action){
        if(maximum<1||residents.size()>maximum)throw new IllegalStateException("Snow residency budget exceeded");
        // Snapshot keys, not entries: a callback can retire or replace a later owner while flushing edits.
        List<Long> keys=List.copyOf(residents.keySet());
        for(long key:keys){
            T current=residents.get(key);
            if(current!=null)action.accept(key,current);
        }
    }
}
