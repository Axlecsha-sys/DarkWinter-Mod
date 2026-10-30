// Clips the actual skinned surface, then closes its upper end with an overlapping soft joint.
package com.dark.winter.client.equipment;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;

/** Clips the actual skinned surface, then closes its upper end with an overlapping soft joint. */
final class ArticulatedMesh {
    record Split(FacetedMesh upper,FacetedMesh lower) { }
    private record Edge(ModelPart.Vertex a,ModelPart.Vertex b) { }
    static Split split(ModelPart model,float joint) {
        FacetedMesh upper=FacetedMesh.mesh(),lower=FacetedMesh.mesh();
        List<Edge> ring=new ArrayList<>();
        model.visit(new PoseStack(),(pose,path,index,cube)->{
            if(!path.isEmpty())throw new IllegalArgumentException("Split limb surfaces before adding children");
            for(var polygon:cube.polygons) {
                List<ModelPart.Vertex> face=unique(List.of(polygon.vertices()));
                List<ModelPart.Vertex> top=clip(face,joint,true),bottom=clip(face,joint,false);
                emit(upper,top,0);emit(lower,bottom,joint);
                boolean above=face.stream().anyMatch(p->p.y()<joint-.00001f),below=face.stream().anyMatch(p->p.y()>joint+.00001f);
                if(above&&below) {
                    var cut=top.stream().filter(p->Math.abs(p.y()-joint)<.00001f).toList();
                    if(cut.size()==2)ring.add(new Edge(cut.get(0),cut.get(1)));
                }
            }
        });
        if(!ring.isEmpty())capJoint(upper,ring,joint);
        return new Split(upper,lower);
    }
    private static ModelPart.Vertex at(ModelPart.Vertex a,ModelPart.Vertex b,float y) {
        float t=(y-a.y())/(b.y()-a.y());
        return new ModelPart.Vertex(a.x()+(b.x()-a.x())*t,y,a.z()+(b.z()-a.z())*t,a.u()+(b.u()-a.u())*t,a.v()+(b.v()-a.v())*t);
    }
    private static List<ModelPart.Vertex> clip(List<ModelPart.Vertex> face,float y,boolean top) {
        List<ModelPart.Vertex> result=new ArrayList<>();
        for(int i=0;i<face.size();i++) {
            var a=face.get(i);var b=face.get((i+1)%face.size());
            boolean ia=top?a.y()<=y:a.y()>=y,ib=top?b.y()<=y:b.y()>=y;
            if(ia)result.add(a);
            if(ia!=ib)result.add(at(a,b,y));
        }
        return unique(result);
    }
    private static List<ModelPart.Vertex> unique(List<ModelPart.Vertex> points) {
        List<ModelPart.Vertex> result=new ArrayList<>();
        for(var p:points)if(result.stream().noneMatch(q->Math.abs(q.x()-p.x())+Math.abs(q.y()-p.y())+Math.abs(q.z()-p.z())<.000001f))result.add(p);
        return result;
    }
    private static void emit(FacetedMesh mesh,List<ModelPart.Vertex> face,float shift) {
        if(face.size()<3)return;
        var p=face.stream().map(v->new ModelPart.Vertex(v.x(),v.y()-shift,v.z(),v.u(),v.v())).toList();
        if(p.size()==4)mesh.face(p.get(0),p.get(1),p.get(2),p.get(3));
        else for(int i=1;i<p.size()-1;i++)mesh.face(p.get(0),p.get(i),p.get(i+1),p.get(i+1));
    }
    private static ModelPart.Vertex sphere(ModelPart.Vertex p,float x,float z,float y,float radius,float amount) {
        float vertical=(float)Math.sin(amount*Math.PI/2);
        float scale=(float)Math.cbrt(Math.max(0,1-vertical*vertical*vertical));
        return new ModelPart.Vertex(x+(p.x()-x)*scale,y+radius*vertical,z+(p.z()-z)*scale,p.u(),p.v());
    }
    private static void capJoint(FacetedMesh mesh,List<Edge> ring,float y) {
        float minX=Float.POSITIVE_INFINITY,minZ=minX,maxX=Float.NEGATIVE_INFINITY,maxZ=maxX;
        for(var e:ring)for(var p:new ModelPart.Vertex[]{e.a,e.b}){minX=Math.min(minX,p.x());maxX=Math.max(maxX,p.x());minZ=Math.min(minZ,p.z());maxZ=Math.max(maxZ,p.z());}
        float x=(minX+maxX)/2,z=(minZ+maxZ)/2,radius=(maxZ-minZ)*.5f;
        for(var edge:ring) {
            var a=edge.a;var b=edge.b;
            // Orient every section outwards even at a UV seam with duplicated vertices.
            float nx=b.z()-a.z(),nz=-(b.x()-a.x());
            if(nx*((a.x()+b.x())/2-x)+nz*((a.z()+b.z())/2-z)<0){var swap=a;a=b;b=swap;}
            for(float[] interval:new float[][]{{0,.45f},{.45f,.8f}}) {
                var a0=sphere(a,x,z,y,radius,interval[0]);var b0=sphere(b,x,z,y,radius,interval[0]);
                var a1=sphere(a,x,z,y,radius,interval[1]);var b1=sphere(b,x,z,y,radius,interval[1]);
                mesh.face(b0,a0,a1,b1);
            }
            var a0=sphere(a,x,z,y,radius,.8f);var b0=sphere(b,x,z,y,radius,.8f);
            var tip=new ModelPart.Vertex(x,y+radius,z,(a.u()+b.u())/2,(a.v()+b.v())/2);
            mesh.face(b0,a0,tip,tip);
        }
    }
    private ArticulatedMesh() { }
}
