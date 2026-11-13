// Pure, shared authored snow-crust corner field. No Minecraft, renderer or world references.
package com.winterkore.engine.surface.geometry;

public final class SnowCrustRelief {
    public static final float STEP=1f/64f,MAX_RISE=3f/64f;
    private static final long[] CORNER_WINDOWS={window(0,0),window(1,0),window(1,1),window(0,1)};
    private SnowCrustRelief(){}
    private static long window(int x,int z) {
        long bits=0;for(int dz=z;dz<z+4;dz++)for(int dx=x;dx<x+4;dx++)bits|=1L<<(dz*5+dx);return bits;
    }
    /** Only fully supported shared corners receive authored crust. */
    public static int pattern(long worldX,long worldZ,long columns) {
        int code=0;
        for(int corner=0;corner<4;corner++)if((columns&CORNER_WINDOWS[corner])==CORNER_WINDOWS[corner]) {
            long vx=worldX+(corner==1||corner==2?1:0),vz=worldZ+(corner>=2?1:0);
            code|=cornerLevel(vx,vz)<<(corner*2);
        }return code;
    }
    private static int cornerLevel(long x,long z) {
        long hash=x*0x632BE59BD9B4E019L^z*0x9E3779B97F4A7C15L^0xD1B54A32D192ED03L;
        hash=(hash^(hash>>>30))*0xBF58476D1CE4E5B9L;
        hash=(hash^(hash>>>27))*0x94D049BB133111EBL;hash^=hash>>>31;
        return 1+(int)Long.remainderUnsigned(hash,3);
    }
    /** Exact vertex offset used by the authored quad; interior triangles are sampled separately. */
    public static float offset(int code,float x,float z) {
        float north=(code&3)+(((code>>>2)&3)-(code&3))*x;
        float south=((code>>>6)&3)+(((code>>>4)&3)-((code>>>6)&3))*x;
        return (north+(south-north)*z)*STEP;
    }
}
