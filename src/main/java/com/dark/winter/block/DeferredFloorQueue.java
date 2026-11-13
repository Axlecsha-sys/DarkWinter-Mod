// Stores only exact removed-plant origins until their deferred repair is eligible.
package com.dark.winter.block;

import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import java.util.function.LongConsumer;

/** No world references or scanning: bounded requests, stable ordering and primitive coordinates. */
final class DeferredFloorQueue {
    private final int capacity;
    private final Long2LongLinkedOpenHashMap pending=new Long2LongLinkedOpenHashMap();
    private long overflow;
    DeferredFloorQueue(int capacity){if(capacity<1||capacity>4096)throw new IllegalArgumentException("Floor repair capacity");this.capacity=capacity;}
    boolean offer(long origin,long gameTime){
        if(!pending.containsKey(origin)&&pending.size()>=capacity){overflow++;return false;}
        pending.putAndMoveToLast(origin,gameTime==Long.MAX_VALUE?Long.MAX_VALUE:gameTime+1);return true;
    }
    boolean contains(long origin){return pending.containsKey(origin);}
    int size(){return pending.size();}
    long overflow(){return overflow;}
    int drain(long gameTime,int budget,LongConsumer action){
        int processed=0;
        while(processed<budget&&!pending.isEmpty()){
            long origin=pending.firstLongKey();if(pending.get(origin)>gameTime)break;
            pending.remove(origin);processed++;action.accept(origin);
        }return processed;
    }
    /** Caller snapshots only origins touched by its completed native edit before any writes. */
    boolean take(long origin){if(!pending.containsKey(origin))return false;pending.remove(origin);return true;}
}
