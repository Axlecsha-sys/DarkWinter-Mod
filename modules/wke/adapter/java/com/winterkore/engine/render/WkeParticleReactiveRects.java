// Captures actual submitted particle geometry; no color/depth heuristic or particle simulation.
package com.winterkore.engine.render;

import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** One render-thread frame of world-space quads, projected for the real reactive-mask pass. */
public final class WkeParticleReactiveRects {
    public static final int MAX_RECTS=16384;
    private static final int DIRECT_QUADS=MAX_RECTS-1;
    private static final double[] worldQuads=new double[DIRECT_QUADS*12];
    private static final double[] overflowBounds=new double[6];
    private static final float[] projectedBounds=new float[MAX_RECTS*4],matrix=new float[16];
    private static final double[] clips=new double[32],polygonA=new double[64],polygonB=new double[64];
    private static final double[] rectangle=new double[4];
    private static final Quaternionf rotation=new Quaternionf();
    private static final Vector3f v0=new Vector3f(),v1=new Vector3f(),v2=new Vector3f(),v3=new Vector3f();
    private static final int[][] BOX_FACES={{0,1,3,2},{4,6,7,5},{0,4,5,1},{2,3,7,6},{0,2,6,4},{1,5,7,3}};
    private static WeakReference<Object> world=new WeakReference<>(null);
    private static long frameId,submitted,particleQuads,customQuads,rejected,overflows,snapshots;
    private static int count,lastProjected;
    private static boolean valid;
    private static double captureCameraX,captureCameraY,captureCameraZ;
    private WkeParticleReactiveRects() { }

    /** Bounds are raw raster NDC. The GPU packer adds exactly one native pixel of padding. */
    public record Snapshot(float[] bounds,int count,long frameId,boolean valid) {
        @Override public float[] bounds() { return bounds.clone(); }
    }

    /** Called before this world's feature preparation and ordinary END_MAIN custom draws. */
    public static void beginWorldFrame(Object level,double cameraX,double cameraY,double cameraZ) {
        RenderSystem.assertOnRenderThread();
        world=new WeakReference<>(level);frameId++;count=0;submitted=0;particleQuads=0;customQuads=0;
        rejected=0;overflows=0;lastProjected=0;valid=level!=null;
        captureCameraX=cameraX;captureCameraY=cameraY;captureCameraZ=cameraZ;
        overflowBounds[0]=overflowBounds[1]=overflowBounds[2]=Double.POSITIVE_INFINITY;
        overflowBounds[3]=overflowBounds[4]=overflowBounds[5]=Double.NEGATIVE_INFINITY;
    }

    /** Public producer API: register the exact current quad alongside its real non-depth-writing draw. */
    public static void submitWorldQuad(double x0,double y0,double z0,double x1,double y1,double z1,
            double x2,double y2,double z2,double x3,double y3,double z3) {
        if(!valid||world.get()==null)return;
        if(!finite(x0,y0,z0)||!finite(x1,y1,z1)||!finite(x2,y2,z2)||!finite(x3,y3,z3)) { rejected++;return; }
        customQuads++;
        append(x0,y0,z0,x1,y1,z1,x2,y2,z2,x3,y3,z3);
    }

    /** Values are the exact inputs used by QuadParticleRenderState.renderRotatedQuad. */
    public static void captureParticle(float x,float y,float z,float qx,float qy,float qz,float qw,float scale,int color) {
        if(!valid||world.get()==null||(color>>>24)==0||scale==0)return;
        rotation.set(qx,qy,qz,qw);
        v0.set(1,-1,0).rotate(rotation).mul(scale).add(x,y,z);
        v1.set(1,1,0).rotate(rotation).mul(scale).add(x,y,z);
        v2.set(-1,1,0).rotate(rotation).mul(scale).add(x,y,z);
        v3.set(-1,-1,0).rotate(rotation).mul(scale).add(x,y,z);
        double x0=captureCameraX+v0.x,y0=captureCameraY+v0.y,z0=captureCameraZ+v0.z;
        double x1=captureCameraX+v1.x,y1=captureCameraY+v1.y,z1=captureCameraZ+v1.z;
        double x2=captureCameraX+v2.x,y2=captureCameraY+v2.y,z2=captureCameraZ+v2.z;
        double x3=captureCameraX+v3.x,y3=captureCameraY+v3.y,z3=captureCameraZ+v3.z;
        if(!finite(x0,y0,z0)||!finite(x1,y1,z1)||!finite(x2,y2,z2)||!finite(x3,y3,z3)) { rejected++;return; }
        particleQuads++;append(x0,y0,z0,x1,y1,z1,x2,y2,z2,x3,y3,z3);
    }

    private static void append(double x0,double y0,double z0,double x1,double y1,double z1,
            double x2,double y2,double z2,double x3,double y3,double z3) {
        submitted++;
        if(count<DIRECT_QUADS) {
            int at=count++*12;
            worldQuads[at++]=x0;worldQuads[at++]=y0;worldQuads[at++]=z0;
            worldQuads[at++]=x1;worldQuads[at++]=y1;worldQuads[at++]=z1;
            worldQuads[at++]=x2;worldQuads[at++]=y2;worldQuads[at++]=z2;
            worldQuads[at++]=x3;worldQuads[at++]=y3;worldQuads[at]=z3;
        }else {
            // An exceptional overflow conservatively covers every actual remaining quad, never drops one.
            overflows++;extend(x0,y0,z0);extend(x1,y1,z1);extend(x2,y2,z2);extend(x3,y3,z3);
        }
    }

    private static void extend(double x,double y,double z) {
        overflowBounds[0]=Math.min(overflowBounds[0],x);overflowBounds[1]=Math.min(overflowBounds[1],y);
        overflowBounds[2]=Math.min(overflowBounds[2],z);overflowBounds[3]=Math.max(overflowBounds[3],x);
        overflowBounds[4]=Math.max(overflowBounds[4],y);overflowBounds[5]=Math.max(overflowBounds[5],z);
    }

    /** Called once before the temporal graph, with this frame's actual world VP and camera. */
    public static Snapshot snapshot(Matrix4fc currentViewProjection,double cameraX,double cameraY,double cameraZ,int width,int height) {
        RenderSystem.assertOnRenderThread();
        if(!valid||world.get()==null||width<=0||height<=0)return new Snapshot(new float[0],0,frameId,false);
        currentViewProjection.get(matrix);
        int rectangles=0;
        for(int quad=0;quad<count;quad++) {
            for(int vertex=0;vertex<4;vertex++) {
                int at=quad*12+vertex*3;
                project(worldQuads[at]-cameraX,worldQuads[at+1]-cameraY,worldQuads[at+2]-cameraZ,vertex);
            }
            clearRectangle();
            if(allInside(4))includeProjected(clips,4);
            else { triangle(0,1,2);triangle(2,3,0); }
            if(writeRectangle(rectangles))rectangles++;
        }
        if(overflows!=0) {
            clearRectangle();
            for(int corner=0;corner<8;corner++)project(overflowBounds[(corner&1)==0?0:3]-cameraX,
                    overflowBounds[(corner&2)==0?1:4]-cameraY,overflowBounds[(corner&4)==0?2:5]-cameraZ,corner);
            if(allInside(8))includeProjected(clips,8);
            else for(int[] face:BOX_FACES) { triangle(face[0],face[1],face[2]);triangle(face[2],face[3],face[0]); }
            if(writeRectangle(rectangles))rectangles++;
        }
        lastProjected=rectangles;snapshots++;
        return new Snapshot(Arrays.copyOf(projectedBounds,rectangles*4),rectangles,frameId,true);
    }

    private static void project(double x,double y,double z,int vertex) {
        int at=vertex*4;
        clips[at]=matrix[0]*x+matrix[4]*y+matrix[8]*z+matrix[12];
        clips[at+1]=matrix[1]*x+matrix[5]*y+matrix[9]*z+matrix[13];
        clips[at+2]=matrix[2]*x+matrix[6]*y+matrix[10]*z+matrix[14];
        clips[at+3]=matrix[3]*x+matrix[7]*y+matrix[11]*z+matrix[15];
    }

    private static void clearRectangle() {
        rectangle[0]=rectangle[1]=Double.POSITIVE_INFINITY;
        rectangle[2]=rectangle[3]=Double.NEGATIVE_INFINITY;
    }

    private static boolean allInside(int vertices) {
        for(int vertex=0;vertex<vertices;vertex++) {
            int at=vertex*4;double w=clips[at+3];
            if(!Double.isFinite(w)||w<=1e-7||clips[at]<-w||clips[at]>w||clips[at+1]<-w||clips[at+1]>w
                    ||clips[at+2]<0||clips[at+2]>w)return false;
        }
        return true;
    }

    private static void includeProjected(double[] values,int vertices) {
        for(int vertex=0;vertex<vertices;vertex++) {
            int at=vertex*4;double w=values[at+3];
            if(w<=0||!Double.isFinite(w))continue;
            double x=values[at]/w,y=values[at+1]/w;
            if(!Double.isFinite(x+y))continue;
            rectangle[0]=Math.min(rectangle[0],x);rectangle[1]=Math.min(rectangle[1],y);
            rectangle[2]=Math.max(rectangle[2],x);rectangle[3]=Math.max(rectangle[3],y);
        }
    }

    /** Homogeneous triangle clipping covers near-plane crossings without exploding or dropping bounds. */
    private static void triangle(int a,int b,int c) {
        System.arraycopy(clips,a*4,polygonA,0,4);System.arraycopy(clips,b*4,polygonA,4,4);
        System.arraycopy(clips,c*4,polygonA,8,4);
        int vertices=3;double[] input=polygonA,output=polygonB;
        for(int plane=0;plane<7&&vertices>0;plane++) {
            int written=0,previous=(vertices-1)*4;
            double previousDistance=distance(input,previous,plane);
            for(int vertex=0;vertex<vertices;vertex++) {
                int current=vertex*4;double currentDistance=distance(input,current,plane);
                boolean previousInside=previousDistance>=0,currentInside=currentDistance>=0;
                if(previousInside!=currentInside) {
                    double t=previousDistance/(previousDistance-currentDistance);
                    for(int component=0;component<4;component++)
                        output[written*4+component]=input[previous+component]
                                +(input[current+component]-input[previous+component])*t;
                    written++;
                }
                if(currentInside) { System.arraycopy(input,current,output,written*4,4);written++; }
                previous=current;previousDistance=currentDistance;
            }
            vertices=written;double[] swap=input;input=output;output=swap;
        }
        includeProjected(input,vertices);
    }

    private static double distance(double[] polygon,int at,int plane) {
        return switch(plane) {
            case 0->polygon[at]+polygon[at+3];case 1->polygon[at+3]-polygon[at];
            case 2->polygon[at+1]+polygon[at+3];case 3->polygon[at+3]-polygon[at+1];
            case 4->polygon[at+2];case 5->polygon[at+3]-polygon[at+2];default->polygon[at+3]-1e-7;
        };
    }

    private static boolean writeRectangle(int rectangleIndex) {
        if(!(rectangle[0]<rectangle[2]&&rectangle[1]<rectangle[3]))return false;
        int at=rectangleIndex*4;
        projectedBounds[at]=(float)Math.clamp(rectangle[0],-1,1);
        projectedBounds[at+1]=(float)Math.clamp(rectangle[1],-1,1);
        projectedBounds[at+2]=(float)Math.clamp(rectangle[2],-1,1);
        projectedBounds[at+3]=(float)Math.clamp(rectangle[3],-1,1);
        return true;
    }

    private static boolean finite(double x,double y,double z) { return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z); }
    public static long frameId() { return frameId; }
    public static void reset() { valid=false;count=0;submitted=0;particleQuads=0;customQuads=0;overflows=0;lastProjected=0;world.clear(); }
    public static Map<String,Object> statusSnapshot() {
        return Map.of("frameId",frameId,"valid",valid,"submittedQuads",submitted,"actualParticleQuads",particleQuads,
                "customWorldQuads",customQuads,"overflowQuads",overflows,"projectedRects",lastProjected,
                "rejectedNonFiniteQuads",rejected,"snapshots",snapshots,"maxRects",MAX_RECTS);
    }
}
