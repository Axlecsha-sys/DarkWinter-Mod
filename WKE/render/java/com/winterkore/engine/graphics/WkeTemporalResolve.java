// Host-independent std140 layout and native-pixel sample sequence for temporal resolve.
package com.winterkore.engine.graphics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class WkeTemporalResolve {
    public static final String BLOCK="WkeTemporalResolve";
    public static final int BYTES=176;
    public static final int SAMPLE_COUNT=8;
    // Static integration and dynamic/depth/particle guards remain independent.
    public static final float STATIC_HISTORY_RETENTION=.9f;
    public static final int DEBUG_WEIGHTS_FLAG=16;
    public final float[] currentInverse=new float[16];
    public final float[] previousViewProjection=new float[16];
    public final float[] cameraDeltaHistory=new float[4];
    // retention; relative depth tolerance; depth Y flip; base mode0/1/2, optional diagnostic flag16
    public final float[] resolveControl={STATIC_HISTORY_RETENTION,.01f,0f,0f};
    public final float[] jitterControl=new float[4];

    public WkeTemporalResolve() {
        for(int i=0;i<4;i++) { currentInverse[i*5]=1;previousViewProjection[i*5]=1; }
    }

    /** Finite eight-phase Halton sequence, recentered so the complete cycle has zero mean. */
    public static float jitterX(int phase) { return halton(Math.floorMod(phase,SAMPLE_COUNT)+1,2)-.5f+.0546875f; }
    public static float jitterY(int phase) { return halton(Math.floorMod(phase,SAMPLE_COUNT)+1,3)-.5f; }
    private static float halton(int index,int base) {
        float result=0,fraction=1f/base;
        while(index>0) { result+=(index%base)*fraction;index/=base;fraction/=base; }
        return result;
    }

    public void write(ByteBuffer target) {
        if(!target.isDirect() || target.isReadOnly() || target.limit()<BYTES)
            throw new IllegalArgumentException("WkeTemporalResolve requires a writable direct 176-byte buffer");
        ByteBuffer bytes=target.duplicate().order(ByteOrder.nativeOrder());
        int at=0;
        for(float value:currentInverse) { bytes.putFloat(at,value);at+=4; }
        for(float value:previousViewProjection) { bytes.putFloat(at,value);at+=4; }
        for(float value:cameraDeltaHistory) { bytes.putFloat(at,value);at+=4; }
        for(float value:resolveControl) { bytes.putFloat(at,value);at+=4; }
        for(float value:jitterControl) { bytes.putFloat(at,value);at+=4; }
    }
}
