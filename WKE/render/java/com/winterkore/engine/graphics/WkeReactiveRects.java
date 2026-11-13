// Native-pixel conservative screen coverage for actual reactive producers; independent of any host or GPU API.
package com.winterkore.engine.graphics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class WkeReactiveRects {
    public static final int MAX_RECTS=16384, RECT_BYTES=16;
    private final float[] bounds=new float[MAX_RECTS*4];
    private int count,rejected,overflow;
    private float paddingX,paddingY;
    public void reset(int width,int height) {
        if(width<=0||height<=0)throw new IllegalArgumentException("Reactive target dimensions");
        count=rejected=overflow=0;
        paddingX=2f/width;paddingY=2f/height; // Exactly one native pixel on each side.
    }
    public void include(float x0,float y0,float x1,float y1) {
        if(!Float.isFinite(x0)||!Float.isFinite(y0)||!Float.isFinite(x1)||!Float.isFinite(y1)) {rejected++;return;}
        float minX=Math.min(x0,x1),maxX=Math.max(x0,x1),minY=Math.min(y0,y1),maxY=Math.max(y0,y1);
        if(maxX< -1||minX>1||maxY< -1||minY>1)return;
        minX=Math.max(-1,minX-paddingX);maxX=Math.min(1,maxX+paddingX);
        minY=Math.max(-1,minY-paddingY);maxY=Math.min(1,maxY+paddingY);
        if(maxX<=minX||maxY<=minY)return;
        if(count<MAX_RECTS) {
            int p=count++*4;bounds[p]=minX;bounds[p+1]=minY;bounds[p+2]=maxX;bounds[p+3]=maxY;
        } else {
            // Never silently drop a moving producer. The final rectangle conservatively covers overflow.
            int p=(MAX_RECTS-1)*4;bounds[p]=Math.min(bounds[p],minX);bounds[p+1]=Math.min(bounds[p+1],minY);
            bounds[p+2]=Math.max(bounds[p+2],maxX);bounds[p+3]=Math.max(bounds[p+3],maxY);overflow++;
        }
    }
    public int count(){return count;}
    public int rejected(){return rejected;}
    public int overflow(){return overflow;}
    public void write(ByteBuffer destination) {
        if(destination.isReadOnly()||destination.remaining()<(long)count*RECT_BYTES)
            throw new IllegalArgumentException("Reactive instance buffer too small");
        ByteBuffer target=destination.duplicate().order(ByteOrder.nativeOrder());
        for(int i=0;i<count*4;i++)target.putFloat(bounds[i]);
    }
}
