package com.winterkore.engine.surface.geometry;

import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Experimental immutable certificates for constant positive contact fields.
 * Not connected to production sampling. A certificate proves D=depth and C=1 over
 * the entire closed bin; it does not approximate or interpolate a sampled field.
 */
public final class SnowConstantFieldBins {
    private static final int WORLD_LIMIT=30_000_003;
    private static final double[][][] OUTLINES=new double[SoleProfiles.COUNT][][];
    static {for(int p=0;p<OUTLINES.length;p++)OUTLINES[p]=SoleProfiles.outline(p);}
    private final int ownerX,ownerZ,side;
    private final double[] depths;
    private final int certifiedBins;

    private SnowConstantFieldBins(int ownerX,int ownerZ,int side,double[] depths,int certifiedBins){
        this.ownerX=ownerX;this.ownerZ=ownerZ;this.side=side;this.depths=depths;this.certifiedBins=certifiedBins;
    }
    /** Build counters include rejected candidates and proof preparation, not only successful bins. */
    public record Build(SnowConstantFieldBins bins,long candidateVisits,long coreTests,
                        int preparedCores,long nanoseconds) {}
    public int side(){return side;}
    public int certifiedBins(){return certifiedBins;}
    /** Includes the immutable object, primitive array and conservative alignment allowance. */
    public long retainedBytes(){return 128L+8L*depths.length;}
    /**
     * NaN means use the unchanged exact field path. Outside-owner and nonfinite queries
     * always fall back. Shared edges belong to a closed bin, including the outer edge.
     */
    public double signedMillimetersOrNaN(double x,double z){
        if(!Double.isFinite(x)||!Double.isFinite(z))return Double.NaN;
        double dx=x-ownerX,dz=z-ownerZ;
        if(dx<0||dx>1||dz<0||dz>1)return Double.NaN;
        int bx=Math.min(side-1,(int)(dx*side)),bz=Math.min(side-1,(int)(dz*side));
        return depths[bz*side+bx];
    }
    /**
     * No source, Prepared, key list or build scratch is retained. An unchanged immutable
     * source may share this whole object across revisions; changed sources must rebuild.
     * Sorting and core proofs are outside any sampling lock.
     */
    public static Build build(int ownerX,int ownerZ,int side,List<SnowFieldEnvelope.Entry> entries){
        if(side!=16&&side!=32)throw new IllegalArgumentException("Certificate side must be 16 or 32");
        long started=System.nanoTime();double[] values=new double[side*side];Arrays.fill(values,Double.NaN);
        var empty=new SnowConstantFieldBins(ownerX,ownerZ,side,values,0);
        if(Math.abs((long)ownerX)>WORLD_LIMIT||Math.abs((long)ownerZ)>WORLD_LIMIT)
            return new Build(empty,0,0,0,System.nanoTime()-started);
        for(var entry:entries)if(!physical(entry.key()))
            return new Build(empty,0,0,0,System.nanoTime()-started);
        var sorted=entries.toArray(SnowFieldEnvelope.Entry[]::new);
        Arrays.sort(sorted,Comparator.comparingDouble(
                (SnowFieldEnvelope.Entry e)->e.prepared().maxDepressionMillimeters()).reversed());
        var cores=new IdentityHashMap<SnowContactRelief.Prepared,Core>();
        long visits=0,tests=0;int accepted=0;double step=1.0/side;
        for(int bz=0;bz<side;bz++)for(int bx=0;bx<side;bx++){
            double minX=ownerX+bx*step,minZ=ownerZ+bz*step,maxX=minX+step,maxZ=minZ+step;
            double maximum=Double.NaN;
            for(var entry:sorted){
                double depth=entry.prepared().maxDepressionMillimeters();
                if(!(depth>0)||(!Double.isNaN(maximum)&&depth<maximum))break;
                visits++;
                if(!entry.prepared().depressionIntersectsBounds(minX,minZ,maxX,maxZ))continue;
                if(Double.isNaN(maximum))maximum=depth;
                // A deeper vehicle or shoulder blocks lower-depth certificates. Equal-depth
                // feet may still prove the maximum because every remaining upper bound is <= it.
                if(entry.key().vehicle())continue;
                var core=cores.computeIfAbsent(entry.prepared(),ignored->new Core(entry.key()));
                tests++;
                if(core.covers(minX,minZ,maxX,maxZ)){
                    values[bz*side+bx]=depth;accepted++;break;
                }
            }
        }
        return new Build(new SnowConstantFieldBins(ownerX,ownerZ,side,values,accepted),
                visits,tests,cores.size(),System.nanoTime()-started);
    }
    private static boolean physical(SnowFieldEnvelope.KernelKey key){
        return Double.isFinite(key.x())&&Double.isFinite(key.z())
                &&Math.abs(key.x())<=WORLD_LIMIT&&Math.abs(key.z())<=WORLD_LIMIT
                &&Float.isFinite(key.yaw())&&Float.isFinite(key.width())&&Float.isFinite(key.length())
                &&Float.isFinite(key.depth())&&key.width()>=.04f&&key.width()<=2
                &&key.length()>=.08f&&key.length()<=4&&key.depth()>=0&&key.depth()<=750
                &&SoleProfiles.valid(key.profile());
    }
    /** Same constants and operation order as Prepared's actual convex sole classifier. */
    private static final class Core {
        final double cx,cz,sin,cos,halfWidth,halfLength;
        final double[] ax,az,dx,dz,nx,nz;
        final boolean valid;
        Core(SnowFieldEnvelope.KernelKey key){
            cx=key.x();cz=key.z();double angle=Math.toRadians(key.yaw());
            sin=Math.sin(angle);cos=Math.cos(angle);
            halfWidth=key.width()*.5;halfLength=key.length()*.5;
            var outline=OUTLINES[key.profile()];int count=outline.length;
            ax=new double[count];az=new double[count];dx=new double[count];dz=new double[count];
            nx=new double[count];nz=new double[count];boolean regular=true;
            for(int i=0;i<count;i++){
                var a=outline[i];var b=outline[(i+1)%count];
                ax[i]=a[0]*halfWidth;az[i]=a[1]*halfLength;
                dx[i]=(b[0]-a[0])*halfWidth;dz[i]=(b[1]-a[1])*halfLength;
                double squared=dx[i]*dx[i]+dz[i]*dz[i],length=Math.sqrt(squared);
                nx[i]=-dz[i]/length;nz[i]=dx[i]/length;
                regular&=Double.isFinite(squared)&&squared>0&&Double.isFinite(1/squared)
                        &&Double.isFinite(ax[i])&&Double.isFinite(az[i])
                        &&Double.isFinite(nx[i])&&Double.isFinite(nz[i]);
            }
            valid=regular&&Double.isFinite(sin)&&Double.isFinite(cos);
        }
        boolean covers(double minX,double minZ,double maxX,double maxZ){
            if(!valid)return false;
            // Each interval encloses the rounded result of EVERY representable point in
            // the closed world rectangle. Correlation loss can reject a safe bin, never
            // accept an unsafe one. No epsilon enlarges the core.
            double dx0=Math.nextDown(minX-cx),dx1=Math.nextUp(maxX-cx);
            double dz0=Math.nextDown(minZ-cz),dz1=Math.nextUp(maxZ-cz);
            double x0=Math.nextDown(mulLo(dx0,dx1,cos)+mulLo(dz0,dz1,sin));
            double x1=Math.nextUp(mulHi(dx0,dx1,cos)+mulHi(dz0,dz1,sin));
            // Prepared computes (-dx)*sin, not dx*(-sin).
            double z0=Math.nextDown(mulLo(-dx1,-dx0,sin)+mulLo(dz0,dz1,cos));
            double z1=Math.nextUp(mulHi(-dx1,-dx0,sin)+mulHi(dz0,dz1,cos));
            if(!Double.isFinite(x0)||!Double.isFinite(x1)||!Double.isFinite(z0)||!Double.isFinite(z1)
                    ||x0< -halfWidth||x1>halfWidth||z0< -halfLength||z1>halfLength)return false;
            for(int i=0;i<ax.length;i++){
                double px0=Math.nextDown(x0-ax[i]),px1=Math.nextUp(x1-ax[i]);
                double pz0=Math.nextDown(z0-az[i]),pz1=Math.nextUp(z1-az[i]);
                double crossHi=Math.nextUp(mulHi(pz0,pz1,dx[i])-mulLo(px0,px1,dz[i]));
                double planeHi=Math.nextUp(mulHi(px0,px1,nx[i])+mulHi(pz0,pz1,nz[i]));
                // Cross <= 0 is stricter than Prepared's 1e-13 classifier; plane <= 0
                // proves its expansion+1e-12 rejection cannot fire. The same cross
                // proof also covers the independent stateless polygon implementation.
                if(!Double.isFinite(crossHi)||!Double.isFinite(planeHi)||crossHi>0||planeHi>0)return false;
            }
            return true;
        }
        private static double mulLo(double lo,double hi,double value){
            return Math.nextDown((value<0?hi:lo)*value);
        }
        private static double mulHi(double lo,double hi,double value){
            return Math.nextUp((value<0?lo:hi)*value);
        }
    }
}
