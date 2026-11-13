// Bounded native handoff work serves initial loading and later nearby owner changes without gating movement.
package com.winterkore.engine.surface.client;

import com.dark.winter.client.loading.WorldPreloadClient;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class SnowInitialNativePreparation {
    private static final int MAX_PENDING=2;
    private static final Map<Long,SnowNativeSectionEpoch.Task> pending=new LinkedHashMap<>();
    private static ClientLevel world;
    private static long starts,adopted,snapshotNanos;
    private static int maximumPending;
    public record Metrics(int pending,long starts,long adopted,long snapshotNanos,int maximumPending){}
    private SnowInitialNativePreparation(){}
    public static Metrics metrics(){return new Metrics(pending.size(),starts,adopted,snapshotNanos,maximumPending);}
    public static void reset(){pending.clear();world=null;starts=adopted=snapshotNanos=0;maximumPending=0;}
    public static void tick(Minecraft client){
        if(client.level!=world){reset();world=client.level;}
        if(world==null)return;
        if(!SnowSurfaceClient.bootstrapReady())return;
        // Initial opaque loading keeps its preparation boundary. Afterwards this queue never opens a UI or blocks movement.
        if(!SnowSurfaceClient.bootstrapNativeReady()&&WorldPreloadClient.active()&&!WorldPreloadClient.allowWorldPreparation())return;
        var required=SnowSurfaceClient.pendingNativeSectionKeys();
        pending.entrySet().removeIf(e->!required.contains(e.getKey())
                ||e.getValue().wke$snowTaskCancelled()&&!e.getValue().wke$snowTaskRunning());
        if(pending.size()>=MAX_PENDING)return;
        var ordered=new LinkedHashSet<Long>();
        for(long key:SnowSurfaceClient.bootstrapRequiredSectionKeys())if(required.contains(key))ordered.add(key);
        ordered.addAll(required);
        var view=client.levelRenderer.viewArea();if(view==null)return;
        for(long node:ordered){
            long requiredEpoch=SnowSurfaceClient.requiredNativeEpoch(node);if(requiredEpoch<=0)continue;
            if(pending.containsKey(node))continue;
            int x=SectionPos.x(node),z=SectionPos.z(node);boolean loaded=true;
            // RenderRegionCache reads a3x3 horizontal neighbourhood. None may be requested or generated here.
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)
                if(world.getChunkSource().getChunk(x+dx,z+dz,ChunkStatus.FULL,false)==null)loaded=false;
            if(!loaded)continue;
            var section=view.getRenderSectionAt(SectionPos.of(node).origin());
            if(section==null||section.getSectionNode()!=node)continue;
            var owner=(SnowNativeSectionEpoch.Owner)section;var task=owner.wke$lastSnowTask();
            if(task!=null&&!task.wke$snowTaskCancelled()&&task.wke$snowEpoch()>=requiredEpoch){
                pending.put(node,task);adopted++;
            }else{
                long started=System.nanoTime();
                var region=new RenderRegionCache().createRegion(world,node);snapshotNanos+=System.nanoTime()-started;
                section.compileAsync(region);task=owner.wke$lastSnowTask();
                if(task==null)throw new IllegalStateException("Native snow compile did not retain its epoch task");
                pending.put(node,task);starts++;
            }
            maximumPending=Math.max(maximumPending,pending.size());
            // At most one start/adoption per client tick and two tracked pending native jobs.
            return;
        }
    }
}
