package com.winterkore.engine.surface.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Identity-owned immutable packed snow vertices. A source is never overwritten or recycled. */
final class SnowMeshBuffer {
    static final int STRIDE=48;
    final ByteBuffer data;
    final int vertices;
    SnowMeshBuffer(ByteBuffer owned){
        if(owned.remaining()%STRIDE!=0)throw new IllegalArgumentException("Partial snow vertex");
        data=owned.slice().asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        vertices=data.remaining()/STRIDE;
    }
}

/** An immutable cell range may share its source with untouched cells and extracted frames. */
record SnowMeshRange(SnowMeshBuffer source,int firstVertex,int vertexCount) {
    SnowMeshRange {
        if(source==null||firstVertex<0||vertexCount<0||vertexCount%3!=0
                ||(long)firstVertex+vertexCount>source.vertices)
            throw new IllegalArgumentException("Invalid snow cell range");
    }
    ByteBuffer bytes(){
        ByteBuffer view=source.data.duplicate().order(ByteOrder.nativeOrder());
        view.position(Math.multiplyExact(firstVertex,SnowMeshBuffer.STRIDE));
        view.limit(Math.multiplyExact(firstVertex+vertexCount,SnowMeshBuffer.STRIDE));
        return view.slice().asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }
    /** The temporary contact scratch lease cannot become a published source. */
    SnowMeshRange detached(){
        ByteBuffer owned=ByteBuffer.allocate(Math.multiplyExact(vertexCount,SnowMeshBuffer.STRIDE)).order(ByteOrder.nativeOrder());
        owned.put(bytes()).flip();
        return new SnowMeshRange(new SnowMeshBuffer(owned),0,vertexCount);
    }
}
