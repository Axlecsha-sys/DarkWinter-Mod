// Small snow cushions derive from real upward twig faces, never a plant picking box.
package com.winterkore.engine.surface.geometry;

import java.util.ArrayList;
import java.util.List;

/** Model-unit geometry (16 units per metre); immutable, resource-load-time only. */
public final class CanopySnowClumps {
    private static final int AROUND=8;
    public static final int QUADS_PER_CLUMP=32,MAX_CLUMPS_PER_PART=3;
    public record Point(double x,double y,double z){}
    public record Vertex(double x,double y,double z,double nx,double ny,double nz){}
    public record Quad(Vertex a,Vertex b,Vertex c,Vertex d){}
    public record Clump(Point support,List<Quad> quads){
        public Clump {quads=List.copyOf(quads);}
    }
    private CanopySnowClumps(){}
    public static Clump onBranch(List<Point> face,int pattern){
        if(face==null||face.size()!=4)return null;
        for(var p:face)if(p==null||!Double.isFinite(p.x+p.y+p.z))return null;
        Point p=face.get(0),q=face.get(1),r=face.get(2);
        double ux=q.x-p.x,uy=q.y-p.y,uz=q.z-p.z,vx=r.x-p.x,vy=r.y-p.y,vz=r.z-p.z;
        double nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx,n=Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(n<1e-8||ny/n<.45)return null;
        double longest=0,ax=0,az=0,dy=0;
        for(int i=0;i<4;i++){
            Point a=face.get(i),b=face.get((i+1)&3);
            double dx=b.x-a.x,dz=b.z-a.z,length=Math.hypot(dx,dz);
            if(length>longest){longest=length;ax=dx/length;az=dz/length;dy=b.y-a.y;}
        }
        // End-grain triangles and nearly upright stems cannot carry an overhanging snow pillow.
        if(longest<.65||Math.abs(dy)>longest*.9)return null;
        double cx=0,cy=0,cz=0;
        for(var a:face){cx+=a.x*.25;cy+=a.y*.25;cz+=a.z*.25;}
        double halfLength=Math.clamp(longest*.30,.36,1.35);
        double halfWidth=.32+.025*Math.floorMod(pattern,3),height=.31+.025*Math.floorMod(pattern/3,4);
        double sx=-nx/ny,sz=-nz/ny,sideX=-az,sideZ=ax;
        var vertices=new ArrayList<Vertex>(26);
        vertices.add(at(cx,cy+height,cz,-sx,1,-sz));
        for(double radius:new double[]{.62,1})for(int i=0;i<AROUND;i++){
            double angle=Math.PI*2*i/AROUND,co=Math.cos(angle),si=Math.sin(angle);
            // Broad unequal shoulders stay fixed with the authored branch, rather than frame noise.
            double lobe=1+.045*Math.sin(3*angle+Math.floorMod(pattern,7));
            double dx=radius*halfLength*co*ax+radius*halfWidth*si*lobe*sideX;
            double dz=radius*halfLength*co*az+radius*halfWidth*si*lobe*sideZ;
            double dome=Math.sqrt(Math.max(0,1-radius*radius));
            double normalX=radius*co/halfLength*ax+radius*si/halfWidth*sideX-dome*sx/height;
            double normalY=dome/height;
            double normalZ=radius*co/halfLength*az+radius*si/halfWidth*sideZ-dome*sz/height;
            vertices.add(at(cx+dx,cy+sx*dx+sz*dz+height*dome,cz+dz,normalX,normalY,normalZ));
        }
        int bottom=vertices.size();
        for(int i=0;i<AROUND;i++){
            var edge=vertices.get(1+AROUND+i);
            vertices.add(at(edge.x,cy+sx*(edge.x-cx)+sz*(edge.z-cz)-.025,edge.z,0,-1,0));
        }
        int bottomCenter=vertices.size();vertices.add(at(cx,cy-.025,cz,0,-1,0));
        for(var v:vertices)if(v.x<0||v.x>16||v.y<0||v.y>16||v.z<0||v.z>16)return null;
        var quads=new ArrayList<Quad>(QUADS_PER_CLUMP);
        for(int i=0;i<AROUND;i++){
            int j=(i+1)%AROUND,inner=1,outer=1+AROUND;
            quads.add(quad(vertices,0,inner+j,inner+i,inner+i));
            quads.add(quad(vertices,inner+i,inner+j,outer+j,outer+i));
            quads.add(quad(vertices,outer+i,outer+j,bottom+j,bottom+i));
            quads.add(quad(vertices,bottomCenter,bottom+i,bottom+j,bottom+j));
        }
        return new Clump(new Point(cx,cy,cz),quads);
    }
    private static Vertex at(double x,double y,double z,double nx,double ny,double nz){
        double length=Math.sqrt(nx*nx+ny*ny+nz*nz);
        return new Vertex(x,y,z,nx/length,ny/length,nz/length);
    }
    private static Quad quad(List<Vertex> vertices,int a,int b,int c,int d){
        return new Quad(vertices.get(a),vertices.get(b),vertices.get(c),vertices.get(d));
    }
}
