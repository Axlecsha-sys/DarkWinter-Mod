package com.winterkore.engine.surface.client;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Exclusively owned scratch between tessellation and packing. Published meshes contain a
 * separate immutable byte buffer, so no frame or GPU upload can observe this storage reused.
 */
final class SnowTriangleBuffer {
    static final int STRIDE=12;
    private static final long POOL_LIMIT=16L*1024*1024;
    // A full chunk leases 256 owners before packing; retain both a worker and contact batch.
    private static final int POOL_COUNT=512;
    private static final ArrayDeque<SnowTriangleBuffer> POOL=new ArrayDeque<>();
    private static long retainedBytes,allocations,reuses;
    // Most untouched caps have four vertices. Grow only owners with actual relief or closure walls.
    double[] vertices=new double[8*STRIDE];
    int[] indices=new int[24];
    float[] visibility=new float[8];
    private Object[] keys=new Object[16];
    private int[] values=new int[16];
    private int keyCount;
    int vertexCount,indexCount;
    private boolean leased;

    static synchronized SnowTriangleBuffer acquire(){
        SnowTriangleBuffer result=POOL.pollFirst();
        if(result==null){result=new SnowTriangleBuffer();allocations++;}
        else{retainedBytes-=result.capacityBytes();reuses++;}
        if(result.leased)throw new IllegalStateException("Snow scratch already leased");
        result.leased=true;
        return result;
    }
    static synchronized void release(SnowTriangleBuffer buffer){
        if(!buffer.leased)throw new IllegalStateException("Snow scratch released twice");
        buffer.clearIdentities();buffer.vertexCount=buffer.indexCount=0;buffer.leased=false;
        long bytes=buffer.capacityBytes();
        if(POOL.size()<POOL_COUNT&&bytes<=POOL_LIMIT-retainedBytes){POOL.addFirst(buffer);retainedBytes+=bytes;}
    }
    static synchronized void clearPool(){POOL.clear();retainedBytes=0;}
    static synchronized long[] diagnostics(){return new long[]{allocations,reuses,retainedBytes,POOL.size()};}
    private long capacityBytes(){
        return (long)vertices.length*Double.BYTES+(long)(indices.length+values.length+visibility.length)*Integer.BYTES
                +(long)keys.length*Long.BYTES;
    }
    private static int hash(Object key){
        int value=System.identityHashCode(key);
        value^=value>>>16;value*=0x7feb352d;value^=value>>>15;return value;
    }
    private int slot(Object key){
        int mask=keys.length-1,slot=hash(key)&mask;
        while(keys[slot]!=null&&keys[slot]!=key)slot=(slot+1)&mask;
        return slot;
    }
    int index(Object key){int slot=slot(key);return keys[slot]==null?-1:values[slot];}
    void remember(Object key,int index){
        if(keyCount+1>keys.length/2){
            Object[] previous=keys;int[] previousValues=values;
            keys=new Object[Math.multiplyExact(keys.length,2)];values=new int[keys.length];
            for(int i=0;i<previous.length;i++)if(previous[i]!=null){
                int slot=slot(previous[i]);keys[slot]=previous[i];values[slot]=previousValues[i];
            }
        }
        int slot=slot(key);if(keys[slot]==null){keys[slot]=key;keyCount++;}
        values[slot]=index;
    }
    void clearIdentities(){if(keyCount!=0){Arrays.fill(keys,null);keyCount=0;}}
    int vertex(double x,double y,double z,double u,double v,double nx,double ny,double nz,
            double depth,double block,double sky,int side){
        int index=vertexCount++;
        if(vertexCount>visibility.length){
            int capacity=Math.max(vertexCount,Math.multiplyExact(visibility.length,2));
            visibility=Arrays.copyOf(visibility,capacity);
            vertices=Arrays.copyOf(vertices,Math.multiplyExact(capacity,STRIDE));
        }
        int first=index*STRIDE;
        vertices[first]=x;vertices[first+1]=y;vertices[first+2]=z;vertices[first+3]=u;vertices[first+4]=v;
        vertices[first+5]=nx;vertices[first+6]=ny;vertices[first+7]=nz;vertices[first+8]=depth;
        vertices[first+9]=block;vertices[first+10]=sky;vertices[first+11]=side;
        visibility[index]=Float.NaN;
        return index;
    }
    int vertex(SnowSurfaceClient.DebugVertex v){
        int known=index(v);if(known>=0)return known;
        int index=vertex(v.x(),v.y(),v.z(),v.u(),v.v(),v.normalX(),v.normalY(),v.normalZ(),
                v.depthMillimeters(),v.blockLight(),v.skyLight(),v.sharedWallSide());
        remember(v,index);return index;
    }
    void triangle(int a,int b,int c){
        int required=Math.addExact(indexCount,3);
        if(required>indices.length)indices=Arrays.copyOf(indices,Math.max(required,Math.multiplyExact(indices.length,2)));
        indices[indexCount++]=a;indices[indexCount++]=b;indices[indexCount++]=c;
    }
    void triangle(SnowSurfaceClient.DebugTriangle t){triangle(vertex(t.a()),vertex(t.b()),vertex(t.c()));}
    void discardBelow(double height){
        int out=0;
        for(int i=0;i<indexCount;i+=3){
            int a=indices[i],b=indices[i+1],c=indices[i+2];
            if(vertices[a*STRIDE+1]<=height&&vertices[b*STRIDE+1]<=height&&vertices[c*STRIDE+1]<=height)continue;
            indices[out++]=a;indices[out++]=b;indices[out++]=c;
        }
        indexCount=out;
    }
}
