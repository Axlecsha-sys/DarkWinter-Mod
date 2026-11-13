package com.winterkore.engine.surface.client;

import java.util.Arrays;

/**
 * Exact four-double memoization for one thread and one caller-defined sampling generation.
 * Numeric key storage is fixed; a collision only evicts memoized work, never changes a result.
 * Stored values must not carry world/context references when this storage is reused by a thread.
 */
final class QuadDoubleMemo<V> {
    private static final int WAYS=4,SETS=8192,CAPACITY=WAYS*SETS;
    private final long[] keys=new long[CAPACITY*4];
    private final Object[] values=new Object[CAPACITY];
    private final int[] generations=new int[CAPACITY];
    private final byte[] victims=new byte[SETS];
    private int generation=1;

    /** Record double equality canonicalizes every NaN and distinguishes the two signed zeroes. */
    @SuppressWarnings("unchecked")
    V get(double a,double b,double c,double d){
        long aa=Double.doubleToLongBits(a),bb=Double.doubleToLongBits(b),
                cc=Double.doubleToLongBits(c),dd=Double.doubleToLongBits(d);
        int first=firstSlot(aa,bb,cc,dd);
        for(int way=0;way<WAYS;way++){
            int slot=first+way;
            if(generations[slot]!=generation)continue;
            int offset=slot*4;
            if(keys[offset]==aa&&keys[offset+1]==bb&&keys[offset+2]==cc&&keys[offset+3]==dd)
                return (V)values[slot];
        }
        return null;
    }

    void put(double a,double b,double c,double d,V value){
        if(value==null)throw new NullPointerException("Memoized values must be non-null");
        long aa=Double.doubleToLongBits(a),bb=Double.doubleToLongBits(b),
                cc=Double.doubleToLongBits(c),dd=Double.doubleToLongBits(d);
        int first=firstSlot(aa,bb,cc,dd),vacant=-1;
        for(int way=0;way<WAYS;way++){
            int slot=first+way;
            if(generations[slot]!=generation){if(vacant<0)vacant=slot;continue;}
            int offset=slot*4;
            if(keys[offset]==aa&&keys[offset+1]==bb&&keys[offset+2]==cc&&keys[offset+3]==dd){
                values[slot]=value;return;
            }
        }
        int slot=vacant;
        if(slot<0){
            int set=first/WAYS,way=victims[set]&3;
            slot=first+way;victims[set]=(byte)((way+1)&3);
        }
        int offset=slot*4;
        keys[offset]=aa;keys[offset+1]=bb;keys[offset+2]=cc;keys[offset+3]=dd;
        values[slot]=value;generations[slot]=generation;
    }

    /** Context changes invalidate all entries in constant time while retaining bounded numeric storage. */
    void clear(){
        if(++generation==0){
            Arrays.fill(generations,0);Arrays.fill(values,null);generation=1;
        }
    }

    private static int firstSlot(long a,long b,long c,long d){
        long hash=a^Long.rotateLeft(b,17)^Long.rotateLeft(c,33)^Long.rotateLeft(d,49);
        hash=(hash^(hash>>>30))*0xbf58476d1ce4e5b9L;
        hash=(hash^(hash>>>27))*0x94d049bb133111ebL;
        hash^=hash>>>31;
        return ((int)hash&(SETS-1))*WAYS;
    }
}
