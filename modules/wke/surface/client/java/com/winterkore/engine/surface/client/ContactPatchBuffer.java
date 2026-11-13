// Temporary pressure-patch bytes are copied into an independently owned published mesh.
package com.winterkore.engine.surface.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

final class ContactPatchBuffer {
    private static final int RETAIN_LIMIT=64*1024*1024,QUANTUM=1024*1024;
    private static final ThreadLocal<State> LOCAL=ThreadLocal.withInitial(State::new);
    private static final class State {
        ByteBuffer storage;boolean leased;
        long allocations,reuses,avoidedBytes,fallbacks;
    }
    private ContactPatchBuffer(){}
    static Lease open(){
        State state=LOCAL.get();boolean pooled=!state.leased;
        if(pooled)state.leased=true;
        return new Lease(state,pooled);
    }
    static void reset(){LOCAL.remove();}
    static Map<String,Long> diagnostics(){
        State s=LOCAL.get();
        return Map.of("retainedBytes",s.storage==null?0L:(long)s.storage.capacity(),
                "allocations",s.allocations,"reuses",s.reuses,"avoidedBytes",s.avoidedBytes,"fallbacks",s.fallbacks);
    }
    static final class Lease implements AutoCloseable {
        private final State state;private final boolean pooled;private boolean closed,supplied;
        private Lease(State state,boolean pooled){this.state=state;this.pooled=pooled;}
        ByteBuffer buffer(int bytes){
            if(closed||supplied)throw new IllegalStateException("A patch lease supplies exactly one buffer");
            if(bytes<0)throw new IllegalArgumentException("Negative patch size");
            supplied=true;
            if(!pooled||bytes>RETAIN_LIMIT){
                state.fallbacks++;return ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
            }
            if(state.storage==null||state.storage.capacity()<bytes){
                int rounded=(int)Math.min(RETAIN_LIMIT,Math.max(QUANTUM,((long)bytes+QUANTUM-1)/QUANTUM*QUANTUM));
                state.storage=ByteBuffer.allocate(rounded).order(ByteOrder.nativeOrder());state.allocations++;
            }else{state.reuses++;state.avoidedBytes+=bytes;}
            return state.storage.duplicate().order(ByteOrder.nativeOrder()).clear().limit(bytes);
        }
        @Override public void close(){
            if(!closed){closed=true;if(pooled)state.leased=false;}
        }
    }
}
