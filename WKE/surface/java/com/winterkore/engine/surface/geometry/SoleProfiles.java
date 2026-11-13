// Host-supplied sole profiles, matching the authored lowermost cap and exposed tread facets.
package com.winterkore.engine.surface.geometry;

/** Pure contact geometry. Model coordinates use +Y down and -Z toward the toe. */
public final class SoleProfiles {
    public static final int CINEMATIC_BOOT=0,NORTHERN_BOOT=1,LEGACY_BOOT=2,SOCK=3,BARE=4,COUNT=5;
    public static final double AVATAR_SCALE=.9375,LEG_SCALE_X=1.15,LEG_SCALE_Y=12.7/13.8,LEG_SCALE_Z=1.10;
    public static final double HIP_Y=11.3,KNEE_Y=6.1*LEG_SCALE_Y,ANKLE_Y=11.45*LEG_SCALE_Y;
    public static final double LEG_LENGTH=12.7,LEG_PIVOT_X=2.35,SOLE_Y=13.8;
    public record Profile(int id,int sides,double width,double length,double power,double outboard,double centreZ) {
        public double worldWidth(double scale){return width*LEG_SCALE_X*AVATAR_SCALE*scale/16;}
        public double worldLength(double scale){return length*LEG_SCALE_Z*AVATAR_SCALE*scale/16;}
    }
    private static final Profile[] PROFILES={
        new Profile(CINEMATIC_BOOT,20,4.40,6.05,4.0,.34,-.98),
        new Profile(NORTHERN_BOOT,20,4.40,6.05,3.9,.34,-.98),
        new Profile(LEGACY_BOOT,20,4.15,5.80,2.6,0,-.88),
        new Profile(SOCK,16,3.40,4.87,2.5,0,-.67),
        new Profile(BARE,16,2.87,4.35,2.5,0,-.67)
    };
    private static final double[][][] OUTLINES=new double[COUNT][][];
    private static final double[][][] BOOT_LUGS=new double[12][][];
    static {
        for(var p:PROFILES)OUTLINES[p.id]=outline(p.sides,1,1,p.power,0,0);
        // Actual CinematicBoots.sole: twelve bottom caps at Y=13.80, not invented groove holes.
        int i=0;var p=PROFILES[CINEMATIC_BOOT];
        for(double x:new double[]{-1.51,0,1.51})for(double z:new double[]{-3.37,-1.82,-.20,1.35})
            BOOT_LUGS[i++]=outline(8,.75/p.width,.90/p.length,4.1,x/(p.width*.5),-(z-p.centreZ)/(p.length*.5));
    }
    private SoleProfiles(){}
    public static boolean valid(int id){return id>=0&&id<COUNT;}
    public static Profile profile(int id){if(!valid(id))throw new IllegalArgumentException("Unknown sole profile");return PROFILES[id];}
    /** Exact FacetedMesh sampling, reflected to contact-local +Z forward. */
    private static double[][] outline(int sides,double rx,double rz,double power,double cx,double cz){
        double[][] points=new double[sides][2];
        for(int i=0;i<sides;i++){
            double theta=-Math.PI*.75+Math.PI*2*i/sides,c=Math.cos(theta),s=Math.sin(theta);
            points[i][0]=cx+rx*Math.copySign(Math.pow(Math.abs(c),2/power),c);
            points[i][1]=cz-rz*Math.copySign(Math.pow(Math.abs(s),2/power),s);
        }
        return points;
    }
    public static double[][] outline(int id){
        var source=OUTLINES[profile(id).id];double[][] copy=new double[source.length][2];
        for(int i=0;i<source.length;i++)copy[i]=source[i].clone();return copy;
    }
    /** Signed distance to the actual polygon union; no per-query allocation. */
    public static double edge(int id,double x,double z){
        if(!valid(id)||!Double.isFinite(x)||!Double.isFinite(z))return Double.POSITIVE_INFINITY;
        double edge=polygonEdge(OUTLINES[id],x,z);
        if(id==CINEMATIC_BOOT&&edge>-.22)
            for(var lug:BOOT_LUGS)edge=Math.min(edge,polygonEdge(lug,x,z));
        return edge;
    }
    private static double polygonEdge(double[][] points,double x,double z){
        boolean inside=true;double minimum=Double.POSITIVE_INFINITY;
        for(int i=0;i<points.length;i++){
            var a=points[i];var b=points[(i+1)%points.length];double dx=b[0]-a[0],dz=b[1]-a[1];
            // The reflected FacetedMesh winding is clockwise.
            if(dx*(z-a[1])-dz*(x-a[0])>1e-13)inside=false;
            double t=Math.clamp(((x-a[0])*dx+(z-a[1])*dz)/(dx*dx+dz*dz),0,1);
            double qx=x-a[0]-t*dx,qz=z-a[1]-t*dz;minimum=Math.min(minimum,qx*qx+qz*qz);
        }
        return (inside?-1:1)*Math.sqrt(minimum);
    }
}
