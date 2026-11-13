package com.winterkore.engine.surface.geometry;

import java.util.Arrays;
import java.util.List;

/**
 * Exclusive, bounded exact scalar samples of one immutable owner neighbourhood.
 * The snow client stores signed contact offsets in millimetres, before weather and support clamping.
 * It preserves face identity and invalidates both old and new contact supports before rebinding.
 * Corners-only weather changes may retain these offsets; final physical heights must be recomputed.
 * No world, kernel, mesh, topology or historical snapshot is retained.
 */
public final class SnowHeightMemo {
    private static final int WAYS=4;
    private final long[] xs,zs;
    private final int[] faces;
    private final double[] heights;
    private final byte[] present,next;
    private final int mask;
    private long hits,misses,invalidations,evictions;
    private int entries;
    public SnowHeightMemo(int capacity){
        if(capacity<WAYS||Integer.bitCount(capacity)!=1)throw new IllegalArgumentException("Power of two capacity");
        xs=new long[capacity];zs=new long[capacity];faces=new int[capacity];heights=new double[capacity];
        present=new byte[capacity];next=new byte[capacity/WAYS];mask=next.length-1;
    }
    private int first(int face,long x,long z){
        long h=x^Long.rotateLeft(z,27)^((long)face*0x9e3779b97f4a7c15L);
        h=(h^(h>>>30))*0xbf58476d1ce4e5b9L;h=(h^(h>>>27))*0x94d049bb133111ebL;
        return ((int)(h^(h>>>31))&mask)*WAYS;
    }
    public double get(int face,double x,double z){
        long xb=Double.doubleToRawLongBits(x),zb=Double.doubleToRawLongBits(z);
        int first=first(face,xb,zb);
        for(int i=first;i<first+WAYS;i++)if(present[i]!=0&&faces[i]==face&&xs[i]==xb&&zs[i]==zb){
            hits++;return heights[i];
        }
        misses++;return Double.NaN;
    }
    public void put(int face,double x,double z,double height){
        if(!Double.isFinite(x)||!Double.isFinite(z)||!Double.isFinite(height))throw new IllegalArgumentException("Finite height sample");
        long xb=Double.doubleToRawLongBits(x),zb=Double.doubleToRawLongBits(z);
        int first=first(face,xb,zb),slot=-1;
        for(int i=first;i<first+WAYS;i++)if(present[i]!=0&&faces[i]==face&&xs[i]==xb&&zs[i]==zb){slot=i;break;}
        if(slot<0)for(int i=first;i<first+WAYS;i++)if(present[i]==0){slot=i;entries++;break;}
        if(slot<0){int set=first/WAYS;slot=first+(next[set]&3);next[set]=(byte)((next[set]+1)&3);evictions++;}
        faces[slot]=face;xs[slot]=xb;zs[slot]=zb;heights[slot]=height;present[slot]=1;
    }
    /** Closed supports include the entire displaced rim and shared-edge roundoff. */
    public void invalidate(List<SnowTriangleErrorMemo.Bounds> changes){
        if(entries==0||changes.isEmpty())return;
        for(int i=0;i<present.length;i++)if(present[i]!=0){
            double x=Double.longBitsToDouble(xs[i]),z=Double.longBitsToDouble(zs[i]);
            for(var b:changes)if(x>=b.minX()-1e-6&&x<=b.maxX()+1e-6&&z>=b.minZ()-1e-6&&z<=b.maxZ()+1e-6){
                present[i]=0;entries--;invalidations++;break;
            }
        }
    }
    public void clear(){invalidations+=entries;entries=0;Arrays.fill(present,(byte)0);}
    public int entries(){return entries;}
    public long hits(){return hits;}
    public long misses(){return misses;}
    public long invalidations(){return invalidations;}
    public long evictions(){return evictions;}
    public long retainedBytes(){return 1024L+present.length*29L+next.length;}
}
