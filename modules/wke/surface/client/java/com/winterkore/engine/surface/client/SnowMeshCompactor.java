package com.winterkore.engine.surface.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Background copy-only packing. Geometry, shading and pressure are never evaluated here. */
final class SnowMeshCompactor {
    record Result(Map<Long,SnowMeshRange> ranges,int copiedBytes) {}
    private SnowMeshCompactor(){}
    static Result compact(Map<Long,SnowMeshRange> captured,int maximumBytes,Runnable check){
        long total=0;
        for(var range:captured.values())total+=(long)range.vertexCount()*SnowMeshBuffer.STRIDE;
        if(total>maximumBytes||total>Integer.MAX_VALUE)throw new IllegalArgumentException("Snow repack byte budget");
        ByteBuffer data=ByteBuffer.allocate((int)total).order(ByteOrder.nativeOrder());
        var first=new LinkedHashMap<Long,Integer>();int vertex=0;
        for(var entry:captured.entrySet()){
            check.run();first.put(entry.getKey(),vertex);data.put(entry.getValue().bytes());vertex+=entry.getValue().vertexCount();
        }
        data.flip();var source=new SnowMeshBuffer(data);var result=new LinkedHashMap<Long,SnowMeshRange>();
        for(var entry:captured.entrySet())result.put(entry.getKey(),new SnowMeshRange(source,first.get(entry.getKey()),entry.getValue().vertexCount()));
        return new Result(Map.copyOf(result),(int)total);
    }
}
