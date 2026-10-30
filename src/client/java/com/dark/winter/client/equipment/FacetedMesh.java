// Native low-poly soft surfaces: rounded cross-sections, shaped lofts and shallow cloth details.
package com.dark.winter.client.equipment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

/** Native low-poly soft surfaces: rounded cross-sections, shaped lofts and shallow cloth details. */
public final class FacetedMesh {
    public record Ring(float y,float width,float depth,float bevel) { }
    public record Section(float y,float width,float depth,float shiftX,float shiftZ,float power) { }
    private record UV(float u,float v,float width,float height,float depth,boolean patch) { }
    private final List<ModelPart.Cube> cubes=new ArrayList<>();

    public FacetedMesh box(float x,float y,float z,float w,float h,float d) {
        cubes.add(new ModelPart.Cube(0,0,x,y,z,w,h,d,0,0,0,false,64,64,Set.of(Direction.values())));
        return this;
    }

    /** Retained for compatibility; skin surfaces below use the same original UV islands. */
    public FacetedMesh skinBox(int u,int v,float oldW,float oldH,float oldD,float x,float y,float z,float w,float h,float d) {
        ModelPart.Cube uvTemplate=new ModelPart.Cube(u,v,0,0,0,oldW,oldH,oldD,0,0,0,false,64,64,Set.of(Direction.values()));
        ModelPart.Cube cube=new ModelPart.Cube(u,v,x,y,z,w,h,d,0,0,0,false,64,64,Set.of(Direction.values()));
        for(int i=0;i<cube.polygons.length;i++) {
            var polygon=uvTemplate.polygons[i];
            var vertices=polygon.vertices().clone();
            for(int j=0;j<vertices.length;j++) {
                var p=vertices[j];
                vertices[j]=new ModelPart.Vertex(x+p.x()*w/oldW,y+p.y()*h/oldH,z+p.z()*d/oldD,p.u(),p.v());
            }
            cube.polygons[i]=new ModelPart.Polygon(vertices,polygon.normal());
        }
        cubes.add(cube);
        return this;
    }

    public FacetedMesh skinPatchBox(float x,float y,float z,float w,float h,float d,float u0,float v0,float u1,float v1) {
        return skinPatchLoft(x+w/2,z+d/2,16,u0,v0,u1,v1,
                section(y,w,d),section(y+h,w,d));
    }

    /** Legacy ring callers also gain rounded sections; bevel now controls how soft the rectangle is. */
    public FacetedMesh loft(float cx,float cz,Ring... rings) {
        Section[] sections=new Section[rings.length];
        for(int i=0;i<rings.length;i++) {
            Ring r=rings[i];
            sections[i]=section(r.y,r.width,r.depth,0,0,Math.max(2.0f,3.0f-r.bevel));
        }
        return softLoft(cx,cz,16,sections);
    }

    public FacetedMesh softLoft(float cx,float cz,int sides,Section... sections) {
        return surface(cx,cz,sides,null,sections);
    }

    /** Sewn garment shells have neck/hem openings, rather than solid horizontal discs. */
    public FacetedMesh softLoftOpen(float cx,float cz,int sides,Section... sections) {
        return surface(cx,cz,sides,null,false,sections);
    }

    public FacetedMesh skinLoft(int u,int v,float oldW,float oldH,float oldD,int sides,Section... sections) {
        return surface(0,0,sides,new UV(u,v,oldW,oldH,oldD,false),sections);
    }

    public FacetedMesh skinPatchLoft(float cx,float cz,int sides,float u0,float v0,float u1,float v1,Section... sections) {
        return surface(cx,cz,sides,new UV(u0,v0,u1-u0,v1-v0,0,true),sections);
    }

    private FacetedMesh surface(float cx,float cz,int sides,UV uv,Section... sections) {
        return surface(cx,cz,sides,uv,true,sections);
    }

    private FacetedMesh surface(float cx,float cz,int sides,UV uv,boolean closed,Section... sections) {
        validate(sides,sections);
        for(int row=0;row<sections.length-1;row++) {
            float[][] a=profile(cx,cz,sides,sections[row]),b=profile(cx,cz,sides,sections[row+1]);
            float v0=verticalUV(uv,sections,row),v1=verticalUV(uv,sections,row+1);
            for(int i=0;i<sides;i++) {
                int j=(i+1)%sides;
                // A skin island has its own seam: the last back quad must not sample across the left-face island.
                float u0=horizontalUV(uv,i,sides,i),u1=horizontalUV(uv,i+1,sides,i);
                quad(a[j],a[i],b[i],b[j],u1,v0,u0,v1);
            }
        }
        if(closed) {
            cap(cx,cz,sides,sections[0],true,uv);
            cap(cx,cz,sides,sections[sections.length-1],false,uv);
        }
        return this;
    }

    /** A genuinely open, thin fabric collar/rim; no opaque cap fills the neck opening. */
    public FacetedMesh openTube(float cx,float cz,int sides,float thickness,Section... sections) {
        validate(sides,sections);
        Section[] inner=new Section[sections.length];
        for(int i=0;i<sections.length;i++) {
            Section s=sections[i];
            inner[i]=section(s.y,Math.max(.08f,s.width-thickness*2),Math.max(.08f,s.depth-thickness*2),s.shiftX,s.shiftZ,s.power);
        }
        for(int row=0;row<sections.length-1;row++) {
            float[][] a=profile(cx,cz,sides,sections[row]),b=profile(cx,cz,sides,sections[row+1]);
            float[][] ia=profile(cx,cz,sides,inner[row]),ib=profile(cx,cz,sides,inner[row+1]);
            for(int i=0;i<sides;i++) {
                int j=(i+1)%sides;
                quad(a[j],a[i],b[i],b[j],1,0,0,1);
                quad(ia[i],ia[j],ib[j],ib[i],0,0,1,1);
            }
        }
        for(int end:new int[]{0,sections.length-1}) {
            float[][] outer=profile(cx,cz,sides,sections[end]),in=profile(cx,cz,sides,inner[end]);
            for(int i=0;i<sides;i++) {
                int j=(i+1)%sides;
                if(end==0)quad(outer[i],outer[j],in[j],in[i],0,0,1,1);
                else quad(outer[j],outer[i],in[i],in[j],0,0,1,1);
            }
        }
        return this;
    }

    /** Low, rounded cloth bulge for pockets and patches, rather than a box attached to the garment. */
    public FacetedMesh pillow(float cx,float cy,float cz,float width,float height,float depth) {
        return softLoft(cx,cz,12,
                section(cy-height*.5f,width*.6f,depth*.35f,0,0,2.6f),
                section(cy-height*.1f,width,depth,0,0,2.8f),
                section(cy+height*.32f,width*.96f,depth*.9f,0,0,2.7f),
                section(cy+height*.5f,width*.67f,depth*.4f,0,0,2.5f));
    }

    public FacetedMesh skinHead() { return skinHead(false); }
    public FacetedMesh skinHead(boolean overlay) {
        float g=overlay?.1f:0;
        int first=cubes.size(),u=overlay?32:0;
        // Broad cheek planes and a tucked jaw read as a head, rather than a rounded capsule.
        // Total height stays fixed: the extra head volume comes from the jaw/neck transition.
        skinLoft(u,0,8,8,8,20,
                section(-6.5f-g,1.65f+g*2,2.1f+g*2,0,.12f,2.3f),
                section(-6.25f,3.4f+g*2,3.9f+g*2,0,.10f,2.7f),
                section(-5.7f,4.4f+g*2,4.7f+g*2,0,.04f,3.2f),
                section(-4.85f,4.6f+g*2,4.8f+g*2,0,0,3.6f),
                section(-3.65f,4.55f+g*2,4.75f+g*2,0,-.03f,3.8f),
                section(-2.75f,4.3f+g*2,4.45f+g*2,0,-.10f,3.6f),
                section(-1.95f,3.65f+g*2,3.8f+g*2,0,-.16f,3.0f),
                section(-1.55f+g,2.9f+g*2,3.15f+g*2,0,-.12f,2.7f));
        // Keep the central six face rows close to square texels. Extra crown/jaw volume
        // uses the forehead/chin rows, rather than stretching eyes and mouth over the skull.
        for(int index=first;index<cubes.size();index++) {
            var cube=cubes.get(index);
            for(int face=0;face<cube.polygons.length;face++) {
                var polygon=cube.polygons[face];var original=polygon.vertices();
                boolean side=true,front=true;
                for(var p:original) {
                    side&=p.v()>=8/64f-.00001f&&p.v()<=16/64f+.00001f;
                    front&=p.u()>=(u+8)/64f-.00001f&&p.u()<=(u+16)/64f+.00001f;
                }
                if(!side)continue;
                var mapped=new ModelPart.Vertex[original.length];
                for(int point=0;point<mapped.length;point++) {
                    var p=original[point];
                    float t=(p.y()+6.5f+g)/(4.95f+2*g);
                    float v=t<.172f?8+t/.172f*.4f:t<.263f?8.4f+(t-.172f)/.091f*.6f:
                            t<.869f?9+(t-.263f)/.606f*6:15+(t-.869f)/.131f;
                    float mappedU=front?(u+8+Math.clamp(p.x()/(4+2*g)+.5f,0,1)*8)/64:p.u();
                    mapped[point]=new ModelPart.Vertex(p.x(),p.y(),p.z(),mappedU,Math.clamp(v,8,16)/64);
                }
                cube.polygons[face]=new ModelPart.Polygon(mapped,polygon.normal());
            }
        }
        return this;
    }

    private static void validate(int sides,Section[] sections) {
        if(sides<8||sides>24||sides%4!=0||sections.length<2)throw new IllegalArgumentException("Loft needs 8..24 sides divisible by four and two sections");
        for(int i=0;i<sections.length;i++) {
            Section s=sections[i];
            if(s.width<=0||s.depth<=0||s.power<1.5f||s.power>5||(i>0&&s.y<=sections[i-1].y))
                throw new IllegalArgumentException("Positive, ordered soft loft sections required");
        }
    }

    private static float[][] profile(float cx,float cz,int sides,Section section) {
        float[][] result=new float[sides][3];
        double exponent=2.0/section.power;
        for(int i=0;i<sides;i++) {
            double theta=-Math.PI*.75+Math.PI*2*i/sides,co=Math.cos(theta),si=Math.sin(theta);
            result[i]=new float[]{cx+section.shiftX+section.width*.5f*(float)Math.copySign(Math.pow(Math.abs(co),exponent),co),
                    section.y,cz+section.shiftZ+section.depth*.5f*(float)Math.copySign(Math.pow(Math.abs(si),exponent),si)};
        }
        return result;
    }

    private static float horizontalUV(UV uv,int index,int sides,int segment) {
        if(uv==null)return index/(float)sides;
        if(uv.patch)return (uv.u+uv.width*index/sides)/64;
        int quarter=sides/4,side=segment/quarter;
        float t=(index-side*quarter)/(float)quarter;
        float u=switch(side) {case 0->uv.depth+uv.width*t;case 1->uv.depth+uv.width+uv.depth*t;
            case 2->uv.depth*2+uv.width+uv.width*t;default->uv.depth*t;};
        return (uv.u+u)/64;
    }

    private static float verticalUV(UV uv,Section[] sections,int row) {
        float t=(sections[row].y-sections[0].y)/(sections[sections.length-1].y-sections[0].y);
        return uv==null?t:(uv.v+(uv.patch?0:uv.depth)+uv.height*t)/64;
    }

    private void cap(float cx,float cz,int sides,Section section,boolean top,UV uv) {
        float[][] points=profile(cx,cz,sides,section);
        float[] center={cx+section.shiftX,section.y,cz+section.shiftZ};
        for(int i=0;i<sides;i++) {
            float[] a=points[top?i:(i+1)%sides],b=points[top?(i+1)%sides:i];
            ModelPart.Vertex[] vertices={capVertex(a,cx,cz,section,top,uv),capVertex(b,cx,cz,section,top,uv),
                    capVertex(center,cx,cz,section,top,uv),capVertex(center,cx,cz,section,top,uv)};
            polygon(vertices);
        }
    }

    private static ModelPart.Vertex capVertex(float[] p,float cx,float cz,Section s,boolean top,UV uv) {
        float u=Math.clamp((p[0]-cx-s.shiftX)/s.width+.5f,0,1),v=Math.clamp((p[2]-cz-s.shiftZ)/s.depth+.5f,0,1);
        if(uv!=null) {
            if(uv.patch){u=(uv.u+u*uv.width)/64;v=(uv.v+v*uv.height)/64;}
            else {u=(uv.u+uv.depth+(top?0:uv.width)+u*uv.width)/64;v=(uv.v+(1-v)*uv.depth)/64;}
        }
        return new ModelPart.Vertex(p[0],p[1],p[2],u,v);
    }

    private void quad(float[] a,float[] b,float[] c,float[] d,float u0,float v0,float u1,float v1) {
        polygon(new ModelPart.Vertex[]{vertex(a,u0,v0),vertex(b,u1,v0),vertex(c,u1,v1),vertex(d,u0,v1)});
    }

    private void polygon(ModelPart.Vertex[] vertices) {
        var a=vertices[0];var b=vertices[1];var c=vertices[2];
        var normal=new Vector3f(b.x()-a.x(),b.y()-a.y(),b.z()-a.z()).cross(c.x()-a.x(),c.y()-a.y(),c.z()-a.z()).normalize();
        float minX=Float.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for(var p:vertices){minX=Math.min(minX,p.x());minY=Math.min(minY,p.y());minZ=Math.min(minZ,p.z());maxX=Math.max(maxX,p.x());maxY=Math.max(maxY,p.y());maxZ=Math.max(maxZ,p.z());}
        ModelPart.Cube cube=new ModelPart.Cube(0,0,minX,minY,minZ,maxX-minX,maxY-minY,maxZ-minZ,0,0,0,false,1,1,Set.of(Direction.NORTH));
        cube.polygons[0]=new ModelPart.Polygon(vertices,normal);
        cubes.add(cube);
    }

    /** Native quad/triangle entry point used when an existing garment is clipped at a joint. */
    public FacetedMesh face(ModelPart.Vertex... vertices) { polygon(vertices);return this; }

    private static ModelPart.Vertex vertex(float[] p,float u,float v) {return new ModelPart.Vertex(p[0],p[1],p[2],u,v);}
    public ModelPart bake(){return new ModelPart(List.copyOf(cubes),Map.of());}
    public ModelPart bake(Map<String,ModelPart> children){return new ModelPart(List.copyOf(cubes),children);}
    private FacetedMesh(){ }
    public static FacetedMesh mesh(){return new FacetedMesh();}
    public static Ring ring(float y,float width,float depth,float bevel){return new Ring(y,width,depth,bevel);}
    public static Section section(float y,float width,float depth){return section(y,width,depth,0,0,2.4f);}
    public static Section section(float y,float width,float depth,float shiftX,float shiftZ,float power){return new Section(y,width,depth,shiftX,shiftZ,power);}
}
