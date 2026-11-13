package com.winterkore.engine.surface.geometry;

import java.util.Arrays;
import java.util.List;

/**
 * Exact persistent horizon samples on a single owner's existing 1/64 metre lattice.
 * Caller ownership is exclusive. Geometry/corners/halo presence changes require clear().
 * Pressure changes invalidate the origin (which selects the ray floor) and all twelve
 * ray positions. Values are copied verbatim; this class never interpolates heights.
 */
public final class SnowHorizonSampleCache {
    public static final int SIDE=65,VALUES=48;
    private static final int POINTS=SIDE*SIDE;
    private static final double PAD=1e-6;
    private static final double[] RADII={.06,.25,.75};
    private final int ownerX,ownerZ;
    private final double[] values=new double[POINTS*VALUES];
    private final byte[] present=new byte[POINTS];
    private int entries;
    private long hits,misses,invalidations;

    public SnowHorizonSampleCache(int ownerX,int ownerZ){this.ownerX=ownerX;this.ownerZ=ownerZ;}
    public long retainedBytes(){return 8L*values.length+present.length+1024;}
    public int entries(){return entries;}
    public long hits(){return hits;}
    public long misses(){return misses;}
    public long invalidations(){return invalidations;}
    private int index(double x,double z){
        double localX=(x-ownerX)*64,localZ=(z-ownerZ)*64;
        if(!Double.isFinite(localX)||!Double.isFinite(localZ)||localX<0||localX>64||localZ<0||localZ>64)return -1;
        int ix=(int)localX,iz=(int)localZ;
        return ix==localX&&iz==localZ?iz*SIDE+ix:-1;
    }
    private static void size(double[] samples){
        if(samples==null||samples.length!=VALUES)throw new IllegalArgumentException("Exactly 48 horizon values required");
    }
    public boolean get(double x,double z,double[] output){
        size(output);int index=index(x,z);
        if(index<0||present[index]==0){misses++;return false;}
        System.arraycopy(values,index*VALUES,output,0,VALUES);hits++;return true;
    }
    public void put(double x,double z,double[] samples){
        size(samples);int index=index(x,z);if(index<0)return;
        System.arraycopy(samples,0,values,index*VALUES,VALUES);
        if(present[index]==0){present[index]=1;entries++;}
    }
    public void clear(){invalidations+=entries;entries=0;Arrays.fill(present,(byte)0);}
    /** Bounds contain the full old AND new kernel supports from every sampled halo owner. */
    public void invalidate(List<SnowTriangleErrorMemo.Bounds> changes){
        if(entries==0||changes.isEmpty())return;
        // A bulk weather transition may safely discard this optimization instead of
        // revisiting the lattice once for every historical mark.
        if(changes.size()>64){clear();return;}
        for(var bounds:changes){
            rectangle(bounds.minX(),bounds.minZ(),bounds.maxX(),bounds.maxZ());
            for(double radius:RADII){
                rectangle(bounds.minX()-radius,bounds.minZ(),bounds.maxX()-radius,bounds.maxZ());
                rectangle(bounds.minX()+radius,bounds.minZ(),bounds.maxX()+radius,bounds.maxZ());
                rectangle(bounds.minX(),bounds.minZ()-radius,bounds.maxX(),bounds.maxZ()-radius);
                rectangle(bounds.minX(),bounds.minZ()+radius,bounds.maxX(),bounds.maxZ()+radius);
            }
        }
    }
    private void rectangle(double minX,double minZ,double maxX,double maxZ){
        if(entries==0)return;
        minX-=PAD;minZ-=PAD;maxX+=PAD;maxZ+=PAD;
        if(maxX<ownerX||minX>ownerX+1.0||maxZ<ownerZ||minZ>ownerZ+1.0)return;
        int x0=(int)Math.ceil(Math.max(0,(minX-ownerX)*64));
        int z0=(int)Math.ceil(Math.max(0,(minZ-ownerZ)*64));
        int x1=(int)Math.floor(Math.min(64,(maxX-ownerX)*64));
        int z1=(int)Math.floor(Math.min(64,(maxZ-ownerZ)*64));
        for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++){
            int index=z*SIDE+x;
            if(present[index]!=0){present[index]=0;entries--;invalidations++;}
        }
    }
}
