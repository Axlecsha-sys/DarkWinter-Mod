package com.winterkore.engine.surface.geometry;

import java.util.Arrays;

/**
 * Thread-confined exact horizon scratch. Numeric keys and all 48-value rows live in
 * flat arrays; clearing a cell only advances a generation. No world or mesh is retained.
 * The caller copies into its reusable output, so later writes never mutate a published row.
 */
public final class SnowHorizonWorkCache {
    public static final int VALUES=48;
    private final int limit;
    private long[] xs,ys,zs;
    private int[] rows,stamps;
    private double[] values;
    private int generation=1,size;
    public SnowHorizonWorkCache(int limit){
        if(limit<1||limit>32768)throw new IllegalArgumentException("Horizon cache capacity must be 1..32768");
        this.limit=limit;
        int initial=Math.min(256,limit),slots=tableSize(initial);
        xs=new long[slots];ys=new long[slots];zs=new long[slots];rows=new int[slots];stamps=new int[slots];
        values=new double[initial*VALUES];
    }
    private static int tableSize(int entries){return Integer.highestOneBit(entries*2-1)<<1;}
    private static int hash(long x,long y,long z){
        long h=x^Long.rotateLeft(y,21)^Long.rotateLeft(z,43);
        h=(h^(h>>>30))*0xbf58476d1ce4e5b9L;h=(h^(h>>>27))*0x94d049bb133111ebL;
        return (int)(h^(h>>>31));
    }
    private int find(long x,long y,long z){
        int slot=hash(x,y,z)&(rows.length-1);
        while(stamps[slot]==generation){
            if(xs[slot]==x&&ys[slot]==y&&zs[slot]==z)return slot;
            slot=(slot+1)&(rows.length-1);
        }
        return slot;
    }
    private static void check(double[] data){
        if(data==null||data.length!=VALUES)throw new IllegalArgumentException("Exactly 48 values required");
    }
    public boolean get(double x,double y,double z,double[] output){
        check(output);
        int slot=find(Double.doubleToLongBits(x),Double.doubleToLongBits(y),Double.doubleToLongBits(z));
        if(stamps[slot]!=generation)return false;
        System.arraycopy(values,rows[slot]*VALUES,output,0,VALUES);return true;
    }
    public void put(double x,double y,double z,double[] input){
        check(input);
        long xb=Double.doubleToLongBits(x),yb=Double.doubleToLongBits(y),zb=Double.doubleToLongBits(z);
        int slot=find(xb,yb,zb);
        if(stamps[slot]!=generation){
            if(size==limit)return;
            if(size==values.length/VALUES){
                grow();
                slot=find(xb,yb,zb);
            }
            xs[slot]=xb;ys[slot]=yb;zs[slot]=zb;rows[slot]=size++;stamps[slot]=generation;
        }
        System.arraycopy(input,0,values,rows[slot]*VALUES,VALUES);
    }
    private void grow(){
        int capacity=Math.min(limit,values.length/VALUES*2);
        values=Arrays.copyOf(values,capacity*VALUES);
        int slots=tableSize(capacity);
        if(slots==rows.length)return;
        long[] oldX=xs,oldY=ys,oldZ=zs;int[] oldRows=rows,oldStamps=stamps;
        xs=new long[slots];ys=new long[slots];zs=new long[slots];rows=new int[slots];stamps=new int[slots];
        for(int i=0;i<oldRows.length;i++)if(oldStamps[i]==generation){
            int slot=find(oldX[i],oldY[i],oldZ[i]);
            xs[slot]=oldX[i];ys[slot]=oldY[i];zs[slot]=oldZ[i];rows[slot]=oldRows[i];stamps[slot]=generation;
        }
    }
    public void clear(){
        size=0;
        if(++generation==0){Arrays.fill(stamps,0);generation=1;}
    }
    public int size(){return size;}
    public long retainedBytes(){return 8L*values.length+32L*rows.length+256;}
}
