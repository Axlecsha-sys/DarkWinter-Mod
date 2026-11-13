package com.winterkore.engine.surface.geometry;

import java.util.Arrays;
import java.util.List;

/**
 * Bounded exact memo for the error callback on fresh canonical triangles.
 * It stores neither old output vertices nor topology. A caller owns this object
 * exclusively and invalidates every changed physical/feature dependency first.
 */
public final class SnowTriangleErrorMemo {
    private static final int WORDS=13,WAYS=4;
    public record Bounds(double minX,double minZ,double maxX,double maxZ) {
        public Bounds {
            if(!Double.isFinite(minX)||!Double.isFinite(minZ)||!Double.isFinite(maxX)||!Double.isFinite(maxZ)
                    ||minX>maxX||minZ>maxZ)throw new IllegalArgumentException("Finite ordered bounds required");
        }
    }
    private final int capacity,setMask;
    private final long[] keys,tags;
    private final double[] values;
    private final byte[] present,next;
    private final long[] query=new long[WORDS];
    private long hits,misses,invalidations,evictions;
    private int entries;

    public SnowTriangleErrorMemo(int capacity){
        if(capacity<WAYS||Integer.bitCount(capacity)!=1)throw new IllegalArgumentException("Power-of-two capacity required");
        this.capacity=capacity;setMask=capacity/WAYS-1;
        keys=new long[Math.multiplyExact(capacity,WORDS)];tags=new long[capacity];
        values=new double[capacity];present=new byte[capacity];next=new byte[capacity/WAYS];
    }
    /** Includes array headers, the object, and its tiny reusable query buffer conservatively. */
    public long retainedBytes(){return (long)capacity*(WORDS*Long.BYTES+Long.BYTES+Double.BYTES+1)+next.length+1024;}
    public int entries(){return entries;}
    public long hits(){return hits;}
    public long misses(){return misses;}
    public long invalidations(){return invalidations;}
    public long evictions(){return evictions;}

    private int query(int face,int level,ConformingSnowMesh.Point a,ConformingSnowMesh.Point b,ConformingSnowMesh.Point c,
            double ya,double yb,double yc,double stopAbove){
        query[0]=Double.doubleToRawLongBits(a.x());query[1]=Double.doubleToRawLongBits(a.y());query[2]=Double.doubleToRawLongBits(a.z());
        query[3]=Double.doubleToRawLongBits(b.x());query[4]=Double.doubleToRawLongBits(b.y());query[5]=Double.doubleToRawLongBits(b.z());
        query[6]=Double.doubleToRawLongBits(c.x());query[7]=Double.doubleToRawLongBits(c.y());query[8]=Double.doubleToRawLongBits(c.z());
        query[9]=Double.doubleToRawLongBits(ya);query[10]=Double.doubleToRawLongBits(yb);query[11]=Double.doubleToRawLongBits(yc);
        query[12]=Double.doubleToRawLongBits(stopAbove);
        long hash=tag(face,level)^0x9e3779b97f4a7c15L;
        for(long word:query){hash^=word+0x9e3779b97f4a7c15L+(hash<<6)+(hash>>>2);}
        hash=(hash^(hash>>>30))*0xbf58476d1ce4e5b9L;
        hash=(hash^(hash>>>27))*0x94d049bb133111ebL;hash^=hash>>>31;
        return ((int)hash&setMask)*WAYS;
    }
    private static long tag(int face,int level){return ((long)face<<32)|(level&0xffffffffL);}
    private boolean matches(int slot,long tag){
        if(present[slot]==0||tags[slot]!=tag)return false;
        int first=slot*WORDS;
        for(int i=0;i<WORDS;i++)if(keys[first+i]!=query[i])return false;
        return true;
    }
    /** NaN means absent. The threshold is part of the key, including positive infinity. */
    public double get(int face,int level,ConformingSnowMesh.Point a,ConformingSnowMesh.Point b,ConformingSnowMesh.Point c,
            double ya,double yb,double yc,double stopAbove){
        int first=query(face,level,a,b,c,ya,yb,yc,stopAbove);long tag=tag(face,level);
        for(int i=0;i<WAYS;i++)if(matches(first+i,tag)){hits++;return values[first+i];}
        misses++;return Double.NaN;
    }
    public void put(int face,int level,ConformingSnowMesh.Point a,ConformingSnowMesh.Point b,ConformingSnowMesh.Point c,
            double ya,double yb,double yc,double stopAbove,double value){
        if(!Double.isFinite(value)||value<0)throw new IllegalArgumentException("Finite nonnegative error required");
        int first=query(face,level,a,b,c,ya,yb,yc,stopAbove),slot=-1;long tag=tag(face,level);
        for(int i=0;i<WAYS;i++)if(matches(first+i,tag)){slot=first+i;break;}
        if(slot<0)for(int i=0;i<WAYS;i++)if(present[first+i]==0){slot=first+i;entries++;break;}
        if(slot<0){int set=first/WAYS;slot=first+(next[set]&3);next[set]=(byte)((next[set]+1)&3);evictions++;}
        System.arraycopy(query,0,keys,slot*WORDS,WORDS);tags[slot]=tag;values[slot]=value;present[slot]=1;
    }
    public void clear(){invalidations+=entries;entries=0;Arrays.fill(present,(byte)0);}
    /**
     * Closed AABB overlap, padded for accepted feature barycentric roundoff.
     * Supplied bounds must cover BOTH removed and added kernels, including halo
     * owners used by shared-boundary height selection. Invalidation is conservative.
     */
    public void invalidate(List<Bounds> changes){
        if(changes.isEmpty()||entries==0)return;
        final double pad=1e-6;
        for(int slot=0;slot<capacity;slot++){
            if(present[slot]==0)continue;int first=slot*WORDS;
            double ax=Double.longBitsToDouble(keys[first]),az=Double.longBitsToDouble(keys[first+2]);
            double bx=Double.longBitsToDouble(keys[first+3]),bz=Double.longBitsToDouble(keys[first+5]);
            double cx=Double.longBitsToDouble(keys[first+6]),cz=Double.longBitsToDouble(keys[first+8]);
            // The feature query deliberately uses its wider coarse window for
            // ill-conditioned triangles. Never infer a tighter dependency here.
            double determinant=(bz-cz)*(ax-cx)+(cx-bx)*(az-cz);
            if(Math.abs(determinant)<1e-6){present[slot]=0;entries--;invalidations++;continue;}
            double minX=Math.min(ax,Math.min(bx,cx))-pad,maxX=Math.max(ax,Math.max(bx,cx))+pad;
            double minZ=Math.min(az,Math.min(bz,cz))-pad,maxZ=Math.max(az,Math.max(bz,cz))+pad;
            for(var changed:changes)if(maxX>=changed.minX&&minX<=changed.maxX&&maxZ>=changed.minZ&&minZ<=changed.maxZ){
                present[slot]=0;entries--;invalidations++;break;
            }
        }
    }
}
