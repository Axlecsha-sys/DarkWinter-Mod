// Complete revision queue; bounded network pages never publish a partial pressure field.
package com.winterkore.engine.surface.client;

import com.winterkore.engine.surface.host.SnowSurfaceChunkPayload;
import com.winterkore.engine.surface.host.SnowSurfacePayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class SnowSurfaceChunkInbox {
    private static final PageAssembler STATE=new PageAssembler();
    private static boolean initialized;
    private SnowSurfaceChunkInbox(){}
    public static synchronized void init(){
        if(initialized)return;initialized=true;
        ClientPlayNetworking.registerGlobalReceiver(SnowSurfaceChunkPayload.TYPE,(payload,context)->offer(payload));
    }
    private static void offer(SnowSurfaceChunkPayload payload){STATE.offer(payload);}
    public static List<SnowSurfaceChunkPayload.Chunk> takeReady(String dimension,
            BiPredicate<Integer,Integer> loaded,int limit){return STATE.takeReady(dimension,loaded,limit);}
    public static Set<Long> activeChunks(String dimension){return STATE.activeChunks(dimension);}
    public static int pendingChunks(){return STATE.pendingChunks();}
    public static void reset(){STATE.reset();}

    /** The production assembler is independent of the world and keeps one in-flight revision per active owner.
     * It allocates for received pages, never from an untrusted total-count header. */
    public static final class PageAssembler {
        private final Map<Long,SnowSurfaceChunkPayload.Chunk> pending=new LinkedHashMap<>();
        private final Map<Long,Assembly> assembling=new HashMap<>();
        private final Map<Long,Long> latestRevisions=new HashMap<>();
        private Set<Long> active=Set.of();
        private String dimension="";
        private long tick=-1;
        public synchronized void offer(SnowSurfaceChunkPayload payload){
            if(!dimension.equals(payload.dimension())){reset();dimension=payload.dimension();}
            // Pages belong to a frozen revision. A newer active-set announcement must not
            // discard a still-required page merely because its snapshot tick is older.
            if(payload.tick()>=tick){
                tick=payload.tick();active=Set.copyOf(payload.activeChunks());
                pending.keySet().retainAll(active);assembling.keySet().retainAll(active);latestRevisions.keySet().retainAll(active);
            }
            for(var frame:payload.chunks()){
                long key=frame.key();if(!active.contains(key))continue;
                var latest=latestRevisions.get(key);
                if(latest!=null&&frame.revision()<latest)continue;
                Assembly assembly=assembling.get(key);
                if(latest==null||frame.revision()>latest){
                    latestRevisions.put(key,frame.revision());assembly=new Assembly(frame);assembling.put(key,assembly);
                }else if(assembly==null)continue; // Already completed/consumed: duplicate page.
                assembly.add(frame);
                if(assembly.complete()){
                    // Only this atomic replacement can supersede a previous complete field.
                    pending.put(key,assembly.join());assembling.remove(key);
                }
            }
            if(latestRevisions.size()>SnowSurfaceChunkPayload.MAX_ACTIVE)throw new IllegalStateException("Snow inbox active bound");
        }
        public synchronized List<SnowSurfaceChunkPayload.Chunk> takeReady(String requestedDimension,
                BiPredicate<Integer,Integer> loaded,int limit){
            if(!dimension.equals(requestedDimension)||limit<=0)return List.of();
            var result=new ArrayList<SnowSurfaceChunkPayload.Chunk>(Math.min(limit,pending.size()));
            for(var iterator=pending.entrySet().iterator();iterator.hasNext()&&result.size()<limit;){
                var frame=iterator.next().getValue();
                if(loaded.test(frame.x(),frame.z())){result.add(frame);iterator.remove();}
            }
            return List.copyOf(result);
        }
        public synchronized Set<Long> activeChunks(String requestedDimension){return dimension.equals(requestedDimension)?active:Set.of();}
        public synchronized int pendingChunks(){
            int count=pending.size();for(long key:assembling.keySet())if(!pending.containsKey(key))count++;return count;
        }
        public synchronized void reset(){
            pending.clear();assembling.clear();latestRevisions.clear();active=Set.of();dimension="";tick=-1;
        }
    }
    private static final class Assembly {
        final int x,z,pageCount,totalMarks;final long revision,weatherTick;
        final Map<Integer,List<SnowSurfacePayload.Mark>> pages=new HashMap<>();
        List<SnowSurfacePayload.Column> columns,halo;
        long receivedMarks;
        Assembly(SnowSurfaceChunkPayload.Chunk frame){
            x=frame.x();z=frame.z();revision=frame.revision();weatherTick=frame.weatherTick();
            pageCount=frame.pageCount();totalMarks=frame.totalMarks();
        }
        void add(SnowSurfaceChunkPayload.Chunk frame){
            if(frame.x()!=x||frame.z()!=z||frame.revision()!=revision||frame.weatherTick()!=weatherTick
                    ||frame.pageCount()!=pageCount||frame.totalMarks()!=totalMarks)
                throw new IllegalArgumentException("Inconsistent snow pressure revision");
            var previous=pages.get(frame.pageIndex());
            if(previous!=null){
                if(!previous.equals(frame.marks())||frame.pageIndex()==0&&(!columns.equals(frame.columns())||!halo.equals(frame.halo())))
                    throw new IllegalArgumentException("Conflicting duplicate snow pressure page");
                return;
            }
            pages.put(frame.pageIndex(),frame.marks());receivedMarks+=frame.marks().size();
            if(frame.pageIndex()==0){columns=frame.columns();halo=frame.halo();}
        }
        boolean complete(){return columns!=null&&pages.size()==pageCount&&receivedMarks==totalMarks;}
        SnowSurfaceChunkPayload.Chunk join(){
            var marks=new ArrayList<SnowSurfacePayload.Mark>(Math.min(totalMarks,4096));
            for(int page=0;page<pageCount;page++)marks.addAll(pages.get(page));
            return new SnowSurfaceChunkPayload.Chunk(x,z,revision,weatherTick,columns,halo,marks);
        }
    }
}
