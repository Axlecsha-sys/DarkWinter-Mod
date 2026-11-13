// Shared world-space shading for connected snow surfaces; no host, world or rendering calls.
package com.winterkore.engine.surface.geometry;

/**
 * All observations come from one immutable caller-owned snapshot. Heights are never rounded:
 * a micrometre support key only classifies equal incident analytic surfaces, independently
 * of which side of an edge requested the sample. Generic/disconnected faces stay separate.
 */
public final class SnowSurfaceShading<T> {
    public static final double DEFAULT_NORMAL_STEP=1.0/64.0;
    private static final double EDGE_EPS=1e-9,CONNECTION_QUANTUM=1e-6;
    public interface Source<T> {
        T at(int ownerX,int ownerY,int ownerZ);
        int x(T face); int y(T face); int z(T face);
        boolean contains(T face,double worldX,double worldZ);
        default boolean smooth(T face){return true;}
        double skinHeight(T face,double worldX,double worldZ);
        double bottomHeight(T face,double worldX,double worldZ);
        double height(T face,double worldX,double worldZ);
        double blockLight(T face);
        double skyLight(T face);
    }
    private final Source<T> source;
    private final double normalStep;

    public SnowSurfaceShading(Source<T> source){this(source,DEFAULT_NORMAL_STEP);}
    public SnowSurfaceShading(Source<T> source,double normalStep){
        if(source==null||!Double.isFinite(normalStep)||normalStep<=0||normalStep>.25)
            throw new IllegalArgumentException("Snow shading source/step");
        this.source=source;this.normalStep=normalStep;
    }

    /** The selected analytic height remains exact; only the owner choice is shared. */
    public double height(T face,double x,double z){
        if(face==null||!Double.isFinite(x)||!Double.isFinite(z))return Double.NaN;
        T owner=canonicalOwner(face,x,z);
        return bounded(owner,x,z)?source.height(owner,x,z):Double.NaN;
    }

    /** Reuses caller storage: height, normalX/Y/Z, block light, sky light. */
    public void sample(T face,double x,double z,double[] output){
        if(output==null||output.length<6)throw new IllegalArgumentException("Six snow shading outputs required");
        T owner=face==null?null:canonicalOwner(face,x,z);
        double center=owner==null||!bounded(owner,x,z)?Double.NaN:source.height(owner,x,z);
        output[0]=center;output[1]=0;output[2]=1;output[3]=0;output[4]=output[5]=0;
        if(owner==null||!Double.isFinite(center))return;
        double left=connectedHeight(owner,x-normalStep,z,x,z),right=connectedHeight(owner,x+normalStep,z,x,z);
        double north=connectedHeight(owner,x,z-normalStep,x,z),south=connectedHeight(owner,x,z+normalStep,x,z);
        double dx=derivative(left,center,right,normalStep),dz=derivative(north,center,south,normalStep);
        double length=Math.sqrt(dx*dx+1+dz*dz);
        if(Double.isFinite(length)&&length>0){output[1]=-dx/length;output[2]=1/length;output[3]=-dz/length;}
        if(!source.smooth(owner)){
            output[4]=light(source.blockLight(owner));output[5]=light(source.skyLight(owner));return;
        }
        int ix=(int)Math.floor(x-.5),iz=(int)Math.floor(z-.5);
        double tx=x-(ix+.5),tz=z-(iz+.5),block=0,sky=0;
        for(int corner=0;corner<4;corner++){
            int cx=ix+(corner&1),cz=iz+(corner>>>1);
            double wx=cx+.5,wz=cz+.5;
            T near=connectedOwner(owner,wx,wz,x,z);
            // Empty space, another floor and a real step do not contribute a black sample.
            if(near==null)near=owner;
            double weight=((corner&1)==0?1-tx:tx)*((corner&2)==0?1-tz:tz);
            block+=light(source.blockLight(near))*weight;sky+=light(source.skyLight(near))*weight;
        }
        output[4]=block;output[5]=sky;
    }

    private static double derivative(double a,double middle,double b,double h){
        if(Double.isFinite(a)&&Double.isFinite(b))return (b-a)/(2*h);
        if(Double.isFinite(b))return (b-middle)/h;
        if(Double.isFinite(a))return (middle-a)/h;
        return 0;
    }
    private static double light(double value){return Double.isFinite(value)?Math.clamp(value,0,240):0;}
    private boolean bounded(T face,double x,double z){
        return face!=null&&x>=source.x(face)-EDGE_EPS&&x<=source.x(face)+1+EDGE_EPS
                &&z>=source.z(face)-EDGE_EPS&&z<=source.z(face)+1+EDGE_EPS&&source.contains(face,x,z);
    }
    private static boolean sameSupportValue(double a,double b){
        return Double.isFinite(a)&&Double.isFinite(b)
                &&Math.rint(a/CONNECTION_QUANTUM)==Math.rint(b/CONNECTION_QUANTUM);
    }
    private boolean sameSupport(T a,T b,double x,double z){
        return source.smooth(a)&&source.smooth(b)&&bounded(a,x,z)&&bounded(b,x,z)
                &&sameSupportValue(source.skinHeight(a,x,z),source.skinHeight(b,x,z))
                &&sameSupportValue(source.bottomHeight(a,x,z),source.bottomHeight(b,x,z));
    }
    private boolean before(T a,T b){
        int ay=source.y(a),by=source.y(b);if(ay!=by)return ay<by;
        int ax=source.x(a),bx=source.x(b);return ax!=bx?ax<bx:source.z(a)<source.z(b);
    }
    private T canonicalOwner(T face,double x,double z){
        if(face==null||!source.smooth(face)||!bounded(face,x,z))return face;
        double gx=Math.rint(x),gz=Math.rint(z);
        boolean edgeX=Math.abs(x-gx)<=EDGE_EPS,edgeZ=Math.abs(z-gz)<=EDGE_EPS;
        if(!edgeX&&!edgeZ)return face;
        int minX=edgeX?(int)gx-1:(int)Math.floor(x),maxX=edgeX?(int)gx:minX;
        int minZ=edgeZ?(int)gz-1:(int)Math.floor(z),maxZ=edgeZ?(int)gz:minZ;
        T selected=face;int ownerY=source.y(face);
        for(int bx=minX;bx<=maxX;bx++)for(int bz=minZ;bz<=maxZ;bz++)for(int dy=-2;dy<=2;dy++){
            T candidate=source.at(bx,ownerY+dy,bz);
            if(candidate!=null&&sameSupport(face,candidate,x,z)
                    &&(before(candidate,selected)||sameOwner(candidate,selected)))selected=candidate;
        }
        return selected;
    }
    private boolean sameOwner(T a,T b){
        return source.x(a)==source.x(b)&&source.y(a)==source.y(b)&&source.z(a)==source.z(b);
    }
    /** A shared boundary test, not a comparison of unrelated surface heights at sample centres. */
    private boolean connects(T base,T candidate,double sampleX,double sampleZ){
        if(base==candidate||sameOwner(base,candidate))return true;
        if(!source.smooth(base)||!source.smooth(candidate))return false;
        int ax=source.x(base),az=source.z(base),bx=source.x(candidate),bz=source.z(candidate);
        if(Math.abs((long)ax-bx)>1||Math.abs((long)az-bz)>1)return false;
        double lowX=Math.max(ax,bx),highX=Math.min(ax+1.0,bx+1.0);
        double lowZ=Math.max(az,bz),highZ=Math.min(az+1.0,bz+1.0);
        if(lowX>highX||lowZ>highZ)return false;
        double x=Math.clamp(sampleX,lowX,highX),z=Math.clamp(sampleZ,lowZ,highZ);
        return sameSupport(base,candidate,x,z);
    }
    private T connectedOwner(T base,double x,double z,double referenceX,double referenceZ){
        if(bounded(base,x,z))return canonicalOwner(base,x,z);
        if(!source.smooth(base))return null;
        double gx=Math.rint(x),gz=Math.rint(z);
        boolean edgeX=Math.abs(x-gx)<=EDGE_EPS,edgeZ=Math.abs(z-gz)<=EDGE_EPS;
        int minX=edgeX?(int)gx-1:(int)Math.floor(x),maxX=edgeX?(int)gx:minX;
        int minZ=edgeZ?(int)gz-1:(int)Math.floor(z),maxZ=edgeZ?(int)gz:minZ;
        T selected=null;int ownerY=source.y(base);
        for(int bx=minX;bx<=maxX;bx++)for(int bz=minZ;bz<=maxZ;bz++)for(int dy=-2;dy<=2;dy++){
            T candidate=source.at(bx,ownerY+dy,bz);
            if(candidate!=null&&bounded(candidate,x,z)&&connects(base,candidate,referenceX,referenceZ)
                    &&(selected==null||before(candidate,selected)))selected=candidate;
        }
        return selected==null?null:canonicalOwner(selected,x,z);
    }
    private double connectedHeight(T base,double x,double z,double referenceX,double referenceZ){
        T owner=connectedOwner(base,x,z,referenceX,referenceZ);
        return owner==null?Double.NaN:source.height(owner,x,z);
    }
}
