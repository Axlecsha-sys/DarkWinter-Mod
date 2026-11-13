// Extracts bounded model edges while hiding coplanar seams and retaining view-dependent silhouettes.
package com.dark.winter.client.highlight;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

/** Pure numeric geometry; no world references, rendering API or mutable output arrays are retained. */
public final class HighlightEdges {
    private static final float COPLANAR=.9995f;
    private static final float HARD_CREASE=(float)Math.cos(Math.toRadians(35));
    private static final float EPS=1e-7f;
    private HighlightEdges() {}

    public static Builder builder(int maxFaces,int maxEdges) { return new Builder(maxFaces,maxEdges); }

    public static final class Builder {
        private final int maxFaces,maxEdges;
        private final HashMap<EdgeKey,Accumulated> edges=new HashMap<>();
        private final HashSet<FaceKey> faces=new HashSet<>();
        private int visited;
        private boolean invalid;
        private Builder(int maxFaces,int maxEdges) {
            if(maxFaces<1||maxFaces>4096||maxEdges<1||maxEdges>8192)
                throw new IllegalArgumentException("Highlight geometry budget");
            this.maxFaces=maxFaces;this.maxEdges=maxEdges;
        }
        public void face(float[] xyz) {
            if(invalid)return;
            if(xyz==null||xyz.length!=12||++visited>maxFaces){invalidate();return;}
            Point[] polygon=new Point[4];int size=0;
            for(int index=0;index<4;index++){
                float x=xyz[index*3],y=xyz[index*3+1],z=xyz[index*3+2];
                if(!valid(x)||!valid(y)||!valid(z)){invalidate();return;}
                Point point=new Point(key(x,y,z),x,y,z);boolean duplicate=false;
                for(int previous=0;previous<size;previous++)duplicate|=polygon[previous].key==point.key;
                if(!duplicate)polygon[size++]=point;
            }
            if(size<3)return;
            long[] identity=new long[size];for(int i=0;i<size;i++)identity[i]=polygon[i].key;
            Arrays.sort(identity);
            // Some double-sided models emit the same polygon with the opposite winding.
            // Count its perimeter once instead of mistaking it for an internal seam.
            if(!faces.add(new FaceKey(size,identity[0],identity[1],identity[2],size==4?identity[3]:0)))return;
            float nx=0,ny=0,nz=0;
            for(int i=0;i<size;i++){
                Point a=polygon[i],b=polygon[(i+1)%size];
                nx+=(a.y-b.y)*(a.z+b.z);ny+=(a.z-b.z)*(a.x+b.x);nz+=(a.x-b.x)*(a.y+b.y);
            }
            float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(length<EPS)return;
            nx/=length;ny/=length;nz/=length;
            for(int i=0;i<size;i++){
                Point a=polygon[i],b=polygon[(i+1)%size];boolean forward=a.key<b.key;
                EdgeKey key=new EdgeKey(forward?a.key:b.key,forward?b.key:a.key);
                Accumulated edge=edges.get(key);
                if(edge==null){
                    if(edges.size()==maxEdges){invalidate();return;}
                    edge=new Accumulated(forward?a:b,forward?b:a);edges.put(key,edge);
                }
                edge.add(nx,ny,nz,forward);
            }
        }
        public Geometry build() {
            if(invalid||edges.isEmpty())return null;
            var result=new ArrayList<Edge>(edges.size());
            for(Accumulated edge:edges.values())result.add(edge.freeze());
            return new Geometry(result.toArray(Edge[]::new));
        }
        private void invalidate(){invalid=true;edges.clear();faces.clear();}
    }

    public static final class Geometry {
        private final Edge[] edges;
        private Geometry(Edge[] edges){this.edges=edges;}
        /** A fresh six-coordinate array for this eye position; callers may own it as a frame snapshot. */
        public float[] lines(float eyeX,float eyeY,float eyeZ) {
            if(!Float.isFinite(eyeX)||!Float.isFinite(eyeY)||!Float.isFinite(eyeZ))return new float[0];
            int visible=0;for(Edge edge:edges)if(edge.visible(eyeX,eyeY,eyeZ))visible++;
            float[] lines=new float[visible*6];int cursor=0;
            for(Edge edge:edges)if(edge.visible(eyeX,eyeY,eyeZ)){
                lines[cursor++]=edge.a.x;lines[cursor++]=edge.a.y;lines[cursor++]=edge.a.z;
                lines[cursor++]=edge.b.x;lines[cursor++]=edge.b.y;lines[cursor++]=edge.b.z;
            }return lines;
        }
    }

    private record Point(long key,float x,float y,float z) {}
    private record EdgeKey(long a,long b) {}
    private record FaceKey(int size,long a,long b,long c,long d) {}
    private static final class Accumulated {
        final Point a,b;int count;boolean forward;
        float ax,ay,az,bx,by,bz;
        Accumulated(Point a,Point b){this.a=a;this.b=b;}
        void add(float x,float y,float z,boolean direction){
            if(count==0){ax=x;ay=y;az=z;forward=direction;}
            else if(count==1){
                // Adjacent outward-facing polygons traverse their common edge in opposite directions.
                float sign=forward==direction?-1:1;bx=x*sign;by=y*sign;bz=z*sign;
            }count++;
        }
        Edge freeze(){return new Edge(a,b,count,ax,ay,az,bx,by,bz);}
    }
    private record Edge(Point a,Point b,int count,float ax,float ay,float az,float bx,float by,float bz) {
        boolean visible(float eyeX,float eyeY,float eyeZ){
            if(count!=2)return true;
            float dot=ax*bx+ay*by+az*bz;
            if(Math.abs(dot)>COPLANAR)return false;
            if(dot<HARD_CREASE)return true;
            float x=eyeX-(a.x+b.x)*.5f,y=eyeY-(a.y+b.y)*.5f,z=eyeZ-(a.z+b.z)*.5f;
            float first=ax*x+ay*y+az*z,second=bx*x+by*y+bz*z;
            return first*second<=EPS;
        }
    }
    private static boolean valid(float value){return Float.isFinite(value)&&Math.abs(value)<=16;}
    private static long key(float x,float y,float z){
        return ((long)(Math.round(x*8192)+131072)<<38)
                |((long)(Math.round(y*8192)+131072)<<19)|(Math.round(z*8192)+131072);
    }
}
