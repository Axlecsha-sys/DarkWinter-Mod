// Shared snow-volume contact geometry; actual tyre tracks retain their original kernel.
package com.winterkore.engine.surface.geometry;

public final class SnowContactRelief {
    private static final double[][][] OUTLINES=new double[SoleProfiles.COUNT][][];
    private static final double[] AREAS=new double[SoleProfiles.COUNT],PERIMETERS=new double[SoleProfiles.COUNT];
    static {
        for(int p=0;p<SoleProfiles.COUNT;p++){
            var points=SoleProfiles.outline(p);OUTLINES[p]=points;double twiceArea=0,perimeter=0;
            for(int i=0;i<points.length;i++){var a=points[i];var b=points[(i+1)%points.length];
                twiceArea+=a[0]*b[1]-b[0]*a[1];perimeter+=Math.hypot(b[0]-a[0],b[1]-a[1]);}
            AREAS[p]=Math.abs(twiceArea)*.5;PERIMETERS[p]=perimeter/(Math.PI*2);
        }
    }
    private SnowContactRelief(){}
    private static double smooth(double t){t=Math.clamp(t,0,1);return t*t*(3-2*t);}
    /** Crumbly snow collapses beyond the sole instead of retaining a vertical cut at its outline. */
    public static double shoulderWidthMeters(double depth){return .025+Math.min(.32,Math.max(0,depth)*.00065);}
    private static double rimWidthMeters(double depth){return .035+Math.min(.225,Math.max(0,depth)*.0003);}
    private static double footDepression(double edge,double depth,double shoulder){
        return edge<=0?depth:edge>=shoulder?0:depth*smooth(1-edge/shoulder);
    }
    private static double footAmplitude(double width,double length,double depth,int profile,double shoulder,double band){
        double h=(width-length)*(width-length)/((width+length)*(width+length));
        double perimeter=Math.PI*(width+length)*.5*(1+3*h/(10+Math.sqrt(4-3*h)))*PERIMETERS[profile];
        double area=AREAS[profile]*width*length*.25+perimeter*shoulder*.5+Math.PI*shoulder*shoulder*.3;
        double bankArea=.5*(perimeter*band+Math.PI*((shoulder+band)*(shoulder+band)-shoulder*shoulder));
        return Math.min(depth*.16,depth*area*.11/bankArea);
    }
    private static double footRim(double edge,double x,double z,double depth,double shoulder,double band,double amplitude){
        double outer=edge-shoulder;if(outer<=0||outer>=band)return 0;
        // Stable local breakup leaves separate soft piles, not a continuous raised gasket.
        double rough=smooth((.52+.34*Math.sin(x*19+z*11)+.24*Math.cos(x*9-z*23)-.25)/.65);
        double t=outer/band,w=Math.sin(Math.PI*t);
        return amplitude*w*w*rough;
    }
    public static double depressionMillimeters(double x,double z,double cx,double cz,double yaw,double width,double length,double depth,boolean vehicle){
        return depressionMillimeters(x,z,cx,cz,yaw,width,length,depth,vehicle,SoleProfiles.CINEMATIC_BOOT);
    }
    public static double depressionMillimeters(double wx,double wz,double cx,double cz,double yaw,double width,double length,double depth,boolean vehicle,int profile){
        if(!valid(wx,wz,cx,cz,yaw,width,length,depth,profile))return 0;
        double a=Math.toRadians(yaw),s=Math.sin(a),c=Math.cos(a),dx=wx-cx,dz=wz-cz;
        if(vehicle){
            double x=(dx*c+dz*s)/(width*.5),z=(-dx*s+dz*c)/(length*.5);
            double qx=Math.abs(x)-.78,qz=Math.abs(z)-.78;
            double edge=Math.hypot(Math.max(qx,0),Math.max(qz,0))+Math.min(Math.max(qx,qz),0)-.20;
            if(edge>=0)return 0;double t=Math.clamp(-edge/.22,0,1);return depth*t*t*(3-2*t);
        }
        double x=dx*c+dz*s,z=-dx*s+dz*c,shoulder=shoulderWidthMeters(depth);
        if(Math.abs(x)>width*.5+shoulder||Math.abs(z)>length*.5+shoulder)return 0;
        return footDepression(worldPolygonEdge(OUTLINES[profile],x,z,width*.5,length*.5),depth,shoulder);
    }
    public static double rimMillimeters(double x,double z,double cx,double cz,double yaw,double width,double length,double depth,boolean vehicle){
        return rimMillimeters(x,z,cx,cz,yaw,width,length,depth,vehicle,SoleProfiles.CINEMATIC_BOOT);
    }
    public static double rimMillimeters(double wx,double wz,double cx,double cz,double yaw,double width,double length,double depth,boolean vehicle,int profile){
        if(!valid(wx,wz,cx,cz,yaw,width,length,depth,profile))return 0;
        double a=Math.toRadians(yaw),s=Math.sin(a),c=Math.cos(a),dx=wx-cx,dz=wz-cz;
        double x=dx*c+dz*s,z=-dx*s+dz*c,band=rimWidthMeters(depth);
        double shoulder=vehicle?0:shoulderWidthMeters(depth);
        if(Math.abs(x)>width*.5+shoulder+band||Math.abs(z)>length*.5+shoulder+band)return 0;
        if(!vehicle)return footRim(worldPolygonEdge(OUTLINES[profile],x,z,width*.5,length*.5),x,z,depth,shoulder,band,
                footAmplitude(width,length,depth,profile,shoulder,band));
        double qx=Math.abs(x)-width*.39,qz=Math.abs(z)-length*.39,radius=Math.min(width,length)*.10;
        double edge=Math.hypot(Math.max(qx,0),Math.max(qz,0))+Math.min(Math.max(qx,qz),0)-radius;
        if(edge<=0||edge>=band)return 0;
        double t=edge/band,weight=Math.sin(Math.PI*t);weight*=weight;
        double area=width*length*.80,perimeter=(width+length)*1.8,bankArea=.5*(perimeter*band+Math.PI*band*band);
        return depth*area*.18/bankArea*weight;
    }
    public static double combinedHeightMillimeters(double x,double z,double cx,double cz,double yaw,double width,double length,double depth,boolean vehicle,int profile){
        double cavity=depressionMillimeters(x,z,cx,cz,yaw,width,length,depth,vehicle,profile);
        double coverage=depth>0?Math.clamp(cavity/depth,0,1):0;
        if(coverage>=1)return -cavity;
        double rim=rimMillimeters(x,z,cx,cz,yaw,width,length,depth,vehicle,profile);
        return cavity==0?rim:-cavity+rim*(1-coverage);
    }
    /**
     * One caller-owned field query. Individual cavity/bank kernels stay unchanged; a fading cavity
     * suppresses neighbouring displaced snow continuously instead of hiding a whole bank at D>0.
     * Accumulate all cavities first, then banks unless fullyCovered(). R is irrelevant at C=1.
     */
    public static final class FieldAccumulator {
        private double depression,rim,coverage;
        public void reset(){depression=rim=coverage=0;}
        public double depressionMillimeters(){return depression;}
        public double rimMillimeters(){return rim;}
        public double coverage(){return coverage;}
        public boolean fullyCovered(){return coverage>=1;}
        public double signedMillimeters(){
            if(fullyCovered())return depression;
            return depression==0?-rim:depression-rim*(1-coverage);
        }
        public void addDepression(Prepared prepared,double x,double z){addDepression(prepared,x,z,null);}
        public void addDepression(Prepared prepared,double x,double z,long[] maxCounters){
            double depth=prepared.maxDepressionMillimeters();if(!(depth>0))return;
            if(fullyCovered()&&depth<=depression)return;
            // A rejected contribution must improve neither absolute D nor normalized coverage C.
            // Round C*depth down so multiplication cannot hide a last-ULP coverage improvement.
            double threshold=fullyCovered()?depression:Math.min(depression,
                    coverage<=0?0:Math.max(0,Math.nextDown(coverage*depth)));
            double value=prepared.depressionMillimetersForMax(x,z,threshold,maxCounters);
            depression=Math.max(depression,value);
            coverage=Math.max(coverage,Math.clamp(value/depth,0,1));
        }
        public void addRim(Prepared prepared,double x,double z){
            if(fullyCovered()||prepared.maxRimMillimeters()<=rim)return;
            rim=Math.max(rim,prepared.rimMillimeters(x,z));
        }
        public void add(Prepared prepared,double x,double z){
            addDepression(prepared,x,z);addRim(prepared,x,z);
        }
    }
    /** Same conservative rotated support bounds as Prepared, without allocating sole edge arrays. */
    public static boolean influenceIntersectsBounds(double x,double z,double yaw,double width,double length,double depth,
            boolean vehicle,int profile,double minX,double minZ,double maxX,double maxZ){
        if(!valid(0,0,x,z,yaw,width,length,depth,profile))return false;
        double a=Math.toRadians(yaw);
        return intersectsBounds(x,z,Math.sin(a),Math.cos(a),width*.5,length*.5,
                vehicle?0:shoulderWidthMeters(depth),rimWidthMeters(depth),minX,minZ,maxX,maxZ);
    }
    /** Exact normal-number ULP without Math.scalb in the hot triangle-bound test. */
    private static double boundsUlp(double magnitude){
        long exponent=(Double.doubleToRawLongBits(magnitude)>>>52)&0x7ffL;
        return exponent>52&&exponent<0x7ffL?Double.longBitsToDouble((exponent-52)<<52):Math.ulp(magnitude);
    }
    private static boolean intersectsBounds(double cx,double cz,double sin,double cos,double halfWidth,double halfLength,
            double shoulder,double band,double minX,double minZ,double maxX,double maxZ){
        if(!Double.isFinite(minX)||!Double.isFinite(minZ)||!Double.isFinite(maxX)||!Double.isFinite(maxZ)
                ||minX>maxX||minZ>maxZ)return false;
        double ex=(maxX-minX)*.5,ez=(maxZ-minZ)*.5,bx=minX+ex,bz=minZ+ez;
        if(!Double.isFinite(ex+ez+bx+bz))return true;
        double dx=bx-cx,dz=bz-cz,ac=Math.abs(cos),as=Math.abs(sin),w=halfWidth+shoulder+band,l=halfLength+shoulder+band;
        double magnitude=Math.max(Math.max(Math.abs(cx),Math.abs(cz)),Math.max(Math.abs(bx),Math.abs(bz))),epsilon=1e-9+8*boundsUlp(magnitude);
        return Math.abs(dx)<=ex+w*ac+l*as+epsilon&&Math.abs(dz)<=ez+w*as+l*ac+epsilon
                &&Math.abs(dx*cos+dz*sin)<=w+ex*ac+ez*as+epsilon&&Math.abs(-dx*sin+dz*cos)<=l+ex*as+ez*ac+epsilon;
    }
    public static Prepared prepare(double x,double z,double yaw,double width,double length,double depth,boolean vehicle,int profile){
        return new Prepared(x,z,yaw,width,length,depth,vehicle,profile);
    }
    // The rounded rectangle only needs a two-dimensional norm outside both axes.
    // A straight side needs no square root. Ordinary physical corners are far
    // from overflow/underflow; the general norm still handles extreme inputs.
    private static double roundedRectangleOffset(double qx,double qz){
        double largest=Math.max(qx,qz);
        if(qx<=0||qz<=0)return largest;
        if(largest>=1e-100&&largest<=1e100)return Math.sqrt(qx*qx+qz*qz);
        return Math.hypot(qx,qz);
    }
    // These directed neighbours are used only on nonnegative finite hot bounds.
    private static double belowPositive(double value){
        return value>0?Double.longBitsToDouble(Double.doubleToRawLongBits(value)-1):0;
    }
    private static double aboveNonnegative(double value){
        return Double.longBitsToDouble(Double.doubleToRawLongBits(value)+1);
    }
    public static final class Prepared {
        private final double cx,cz,sin,cos,halfWidth,halfLength,depth,shoulder,band,amplitude,coreX,coreZ,vehicleRadius,depressionBoundarySlack;
        private static final double[] NO_EDGES=new double[0];
        private final boolean vehicle,valid,edgeCacheValid;private final int profile;
        private final double innerHalfWidth,innerHalfLength,shoulderSquaredUpper,scaledDepthUpper;
        private final double[] edgeAx,edgeAz,edgeDx,edgeDz,edgeInverseSquared,edgeNormalX,edgeNormalZ;
        private Prepared(double x,double z,double yaw,double width,double length,double depth,boolean vehicle,int profile){
            cx=x;cz=z;this.depth=depth;this.vehicle=vehicle;this.profile=profile;
            valid=SnowContactRelief.valid(0,0,x,z,yaw,width,length,depth,profile);
            double a=Math.toRadians(yaw);sin=Math.sin(a);cos=Math.cos(a);
            halfWidth=width*.5;halfLength=length*.5;shoulder=vehicle?0:shoulderWidthMeters(depth);band=rimWidthMeters(depth);
            shoulderSquaredUpper=Math.nextUp(shoulder*shoulder);scaledDepthUpper=Math.nextUp(1.125*depth);
            depressionBoundarySlack=Math.max(1e-11,128*Math.ulp(Math.max(halfWidth,halfLength)+shoulder));
            coreX=width*.39;coreZ=length*.39;vehicleRadius=Math.min(width,length)*.10;
            if(!valid||vehicle){
                edgeAx=edgeAz=edgeDx=edgeDz=edgeInverseSquared=edgeNormalX=edgeNormalZ=NO_EDGES;edgeCacheValid=false;
                innerHalfWidth=innerHalfLength=-1;
            }else{
                var points=OUTLINES[profile];int count=points.length;
                edgeAx=new double[count];edgeAz=new double[count];edgeDx=new double[count];edgeDz=new double[count];
                edgeInverseSquared=new double[count];edgeNormalX=new double[count];edgeNormalZ=new double[count];
                boolean regular=true;
                for(int i=0;i<count;i++){var p=points[i];var next=points[(i+1)%count];
                    double ax=p[0]*halfWidth,az=p[1]*halfLength,dx=(next[0]-p[0])*halfWidth,dz=(next[1]-p[1])*halfLength;
                    double squared=dx*dx+dz*dz,edgeLength=Math.sqrt(squared);
                    edgeAx[i]=ax;edgeAz[i]=az;edgeDx[i]=dx;edgeDz[i]=dz;edgeInverseSquared[i]=1/squared;
                    edgeNormalX[i]=-dz/edgeLength;edgeNormalZ[i]=dx/edgeLength;
                    regular&=Double.isFinite(squared)&&squared>0&&Double.isFinite(edgeInverseSquared[i])&&Double.isFinite(ax)&&Double.isFinite(az);
                }
                // A convex edge is linear, so its maximum over a rectangle occurs at a corner.
                // Verify the central rectangle once; an unsupported/pathological profile has no shortcut.
                double innerX=halfWidth*.75,innerZ=halfLength*.75;boolean inner=regular;
                for(int i=0;i<count&&inner;i++)for(int corner=0;corner<4;corner++){
                    double px=((corner&1)==0?-innerX:innerX)-edgeAx[i];
                    double pz=((corner&2)==0?-innerZ:innerZ)-edgeAz[i];
                    double cross=edgeDx[i]*pz-edgeDz[i]*px;
                    if(!Double.isFinite(cross)||cross>0){inner=false;break;}
                }
                innerHalfWidth=inner?innerX:-1;innerHalfLength=inner?innerZ:-1;
                // Pathological finite dimensions retain the original arithmetic rather than acquiring a new shape.
                edgeCacheValid=regular;
            }
            if(!valid){amplitude=0;return;}
            if(vehicle){double area=width*length*.80,perimeter=(width+length)*1.8;
                amplitude=depth*area*.18/(.5*(perimeter*band+Math.PI*band*band));}
            else amplitude=footAmplitude(width,length,depth,profile,shoulder,band);
        }
        /** Exact upper bounds let a max-combiner skip contacts which cannot improve its current result. */
        public double maxDepressionMillimeters(){return valid?depth:0;}
        public double maxRimMillimeters(){return valid?amplitude:0;}
        /** Exact half-plane classification and nearest facing edge share one ordered traversal. */
        private double edgeDistance(double x,double z,double expansion){
            if(Math.abs(x)<=innerHalfWidth&&Math.abs(z)<=innerHalfLength)return -1;
            boolean inside=true;double minimum=Double.POSITIVE_INFINITY;
            for(int i=0;i<edgeAx.length;i++){
                double px=x-edgeAx[i],pz=z-edgeAz[i],dx=edgeDx[i],dz=edgeDz[i];
                // Keep the same conservative rejection and arithmetic as the separate classifier.
                if(edgeNormalX[i]*px+edgeNormalZ[i]*pz>expansion+1e-12)return Double.POSITIVE_INFINITY;
                double cross=dx*pz-dz*px;
                if(cross>1e-13)inside=false;
                // At a nearest vertex at least one adjacent edge faces the exterior point.
                // Near-plane edges retain the previous conservative distance candidates.
                if(cross< -1e-13)continue;
                double t=Math.clamp((px*dx+pz*dz)*edgeInverseSquared[i],0,1);
                double qx=px-t*dx,qz=pz-t*dz;
                minimum=Math.min(minimum,qx*qx+qz*qz);
            }
            return inside?-1:Math.sqrt(minimum);
        }
        /** A positive existing maximum permits conservative rejection before segment distances. */
        private double edgeDistanceForMax(double x,double z,double expansion,double linearLimit,double squaredLimit,long[] counters){
            if(Math.abs(x)<=innerHalfWidth&&Math.abs(z)<=innerHalfLength)return -1;
            boolean inside=true;double minimum=Double.POSITIVE_INFINITY;
            for(int i=0;i<edgeAx.length;i++){
                double px=x-edgeAx[i],pz=z-edgeAz[i],dx=edgeDx[i],dz=edgeDz[i];
                double plane=edgeNormalX[i]*px+edgeNormalZ[i]*pz;
                if(plane>expansion+1e-12)return Double.POSITIVE_INFINITY;
                double cross=dx*pz-dz*px;
                if(cross>1e-13){
                    inside=false;
                    // The ordinary classifier must already consider this point exterior.
                    // Its absolute cross tolerance can exceed metre slack on tiny outlines.
                    double gap=plane-depressionBoundarySlack;
                    if(gap>0){
                        if(counters!=null)counters[0]++;
                        if(gap>linearLimit||gap*gap>squaredLimit){
                            if(counters!=null)counters[1]++;
                            return Double.POSITIVE_INFINITY;
                        }
                    }
                }
                if(cross< -1e-13)continue;
                double t=Math.clamp((px*dx+pz*dz)*edgeInverseSquared[i],0,1);
                double qx=px-t*dx,qz=pz-t*dz;
                minimum=Math.min(minimum,qx*qx+qz*qz);
            }
            return inside?-1:Math.sqrt(minimum);
        }
        public boolean influenceIntersectsBounds(double minX,double minZ,double maxX,double maxZ){
            return valid&&intersectsBounds(cx,cz,sin,cos,halfWidth,halfLength,shoulder,band,minX,minZ,maxX,maxZ);
        }
        /** Only the cut and its collapsed shoulder contribute to depression and coverage. */
        boolean depressionIntersectsBounds(double minX,double minZ,double maxX,double maxZ){
            return valid&&intersectsBounds(cx,cz,sin,cos,halfWidth,halfLength,shoulder,0,minX,minZ,maxX,maxZ);
        }
        public double depressionMillimeters(double wx,double wz){
            if(!valid||!Double.isFinite(wx)||!Double.isFinite(wz))return 0;
            double dx=wx-cx,dz=wz-cz,x=dx*cos+dz*sin,z=-dx*sin+dz*cos;
            if(!vehicle){
                if(Math.abs(x)>halfWidth+shoulder||Math.abs(z)>halfLength+shoulder)return 0;
                if(!edgeCacheValid)return footDepression(worldPolygonEdge(OUTLINES[profile],x,z,halfWidth,halfLength),depth,shoulder);
                double edge=edgeDistance(x,z,shoulder);
                // A last-ULP shoulder crossing changes a global cavity/rim combiner's sign.
                // Resolve that narrow boundary with the authoritative segment arithmetic.
                if(edge>=0&&Double.isFinite(edge)&&Math.abs(edge-shoulder)<=depressionBoundarySlack)
                    edge=worldPolygonEdge(OUTLINES[profile],x,z,halfWidth,halfLength);
                return edge<0?depth:footDepression(edge,depth,shoulder);
            }
            x/=halfWidth;z/=halfLength;if(Math.abs(x)>1||Math.abs(z)>1)return 0;
            double qx=Math.abs(x)-.78,qz=Math.abs(z)-.78,edge=roundedRectangleOffset(qx,qz)-.20;
            if(edge>=0)return 0;double t=Math.clamp(-edge/.22,0,1);return depth*t*t*(3-2*t);
        }
        /**
         * Contribution to max(currentMax, depression): a proven non-improving contribution may be zero.
         * The ordinary method owns zero/nonzero classification; only a strictly positive finite maximum
         * enables these bounds. Optional caller-owned counters are [plane tests, plane rejections].
         */
        public double depressionMillimetersForMax(double wx,double wz,double currentMax){
            return depressionMillimetersForMax(wx,wz,currentMax,null);
        }
        public double depressionMillimetersForMax(double wx,double wz,double currentMax,long[] counters){
            if(counters!=null&&counters.length<2)throw new IllegalArgumentException("Two MAX-bound counters are required");
            if(!(currentMax>0)||!Double.isFinite(currentMax)||vehicle||!edgeCacheValid
                    ||depth>750||halfWidth>1||halfLength>2)return depressionMillimeters(wx,wz);
            if(!valid||!Double.isFinite(wx)||!Double.isFinite(wz)||currentMax>=depth)return 0;
            double dx=wx-cx,dz=wz-cz,x=dx*cos+dz*sin,z=-dx*sin+dz*cos;
            if(Math.abs(x)>halfWidth+shoulder||Math.abs(z)>halfLength+shoulder)return 0;
            if(Math.abs(x)<=innerHalfWidth&&Math.abs(z)<=innerHalfLength)return depth;
            // For u=edge/shoulder, q(u)=1-3u²+2u³ <= 1-u² and <= (9/8)*(1-u).
            // Round each rejection threshold outwards; geometry slack makes plane gaps lower bounds.
            double ratio=belowPositive(currentMax/depth);
            double squaredLimit=aboveNonnegative(shoulderSquaredUpper*aboveNonnegative(1-ratio));
            double linearRatio=belowPositive(currentMax/scaledDepthUpper);
            double linearLimit=aboveNonnegative(shoulder*aboveNonnegative(1-linearRatio));
            double edge=edgeDistanceForMax(x,z,shoulder,linearLimit,squaredLimit,counters);
            if(edge>=0&&Double.isFinite(edge)&&Math.abs(edge-shoulder)<=depressionBoundarySlack)
                edge=worldPolygonEdge(OUTLINES[profile],x,z,halfWidth,halfLength);
            return edge<0?depth:footDepression(edge,depth,shoulder);
        }
        /**
         * Exact contributions to two independent maxima, stored as [depression, rim].
         * A proven non-improving component may be zero; callers deriving coverage C
         * must bound depressionThreshold by nextDown(C * depth), as in FieldAccumulator.
         * The caller owns the scratch array. Existing scalar queries retain their contracts.
         */
        public void sampleContributions(double wx,double wz,double depressionThreshold,double rimThreshold,double[] values){
            if(values==null||values.length<2)throw new IllegalArgumentException("Two contribution outputs are required");
            values[0]=values[1]=0;
            if(!valid||!Double.isFinite(wx)||!Double.isFinite(wz))return;
            // Keep extreme scaled-edge arithmetic (including NaN results) and independent tyre shapes
            // on their old paths before inferring finite upper bounds for either component.
            if(vehicle||!edgeCacheValid||halfWidth<.02||halfWidth>1||halfLength<.04||halfLength>2||depth>750
                    ||depressionThreshold<0||rimThreshold<0||Double.isNaN(depressionThreshold)||Double.isNaN(rimThreshold)){
                values[0]=depressionMillimetersForMax(wx,wz,depressionThreshold);
                values[1]=rimMillimeters(wx,wz);return;
            }
            boolean needDepression=!(depth<=depressionThreshold),needRim=!(amplitude<=rimThreshold);
            if(!needDepression&&!needRim)return;
            if(!needRim){values[0]=depressionMillimetersForMax(wx,wz,depressionThreshold);return;}
            if(!needDepression){values[1]=rimMillimeters(wx,wz);return;}
            double dx=wx-cx,dz=wz-cz,x=dx*cos+dz*sin,z=-dx*sin+dz*cos;
            if(Math.abs(x)>halfWidth+shoulder+band||Math.abs(z)>halfLength+shoulder+band)return;
            // The bank's wider envelope contains the cavity, so both can share this exact edge scan.
            double edge=edgeDistance(x,z,shoulder+band);
            if(edge>=0&&edge!=Double.POSITIVE_INFINITY)
                values[1]=footRim(edge,x,z,depth,shoulder,band,amplitude);
            // Preserve the cavity's own bounding box and shoulder sign correction. The bank above
            // deliberately uses the original prepared edge, exactly as rimMillimeters does.
            if(Math.abs(x)>halfWidth+shoulder||Math.abs(z)>halfLength+shoulder)return;
            double cavityEdge=edge;
            if(cavityEdge>=0&&Double.isFinite(cavityEdge)&&Math.abs(cavityEdge-shoulder)<=depressionBoundarySlack)
                cavityEdge=worldPolygonEdge(OUTLINES[profile],x,z,halfWidth,halfLength);
            values[0]=cavityEdge<0?depth:footDepression(cavityEdge,depth,shoulder);
        }
        public double rimMillimeters(double wx,double wz){
            if(!valid||!Double.isFinite(wx)||!Double.isFinite(wz))return 0;
            double dx=wx-cx,dz=wz-cz,x=dx*cos+dz*sin,z=-dx*sin+dz*cos;
            if(Math.abs(x)>halfWidth+shoulder+band||Math.abs(z)>halfLength+shoulder+band)return 0;
            if(!vehicle){
                if(!edgeCacheValid)return footRim(worldPolygonEdge(OUTLINES[profile],x,z,halfWidth,halfLength),x,z,depth,shoulder,band,amplitude);
                double edge=edgeDistance(x,z,shoulder+band);
                if(edge<0||edge==Double.POSITIVE_INFINITY)return 0;
                return footRim(edge,x,z,depth,shoulder,band,amplitude);
            }
            double qx=Math.abs(x)-coreX,qz=Math.abs(z)-coreZ,edge=roundedRectangleOffset(qx,qz)-vehicleRadius;
            if(edge<=0||edge>=band)return 0;double t=edge/band,weight=Math.sin(Math.PI*t);weight*=weight;return amplitude*weight;
        }
    }
    /** Conservative packet/halo radius includes the collapsed shoulder and displaced piles. */
    public static double influenceRadiusMeters(double width,double length,double depth){
        if(!Double.isFinite(width)||!Double.isFinite(length)||!Double.isFinite(depth)||width<=0||length<=0||depth<=0)return 0;
        return Math.hypot(width,length)*.5+shoulderWidthMeters(depth)+rimWidthMeters(depth);
    }
    private static boolean valid(double x,double z,double cx,double cz,double yaw,double width,double length,double depth,int profile){
        return Double.isFinite(x)&&Double.isFinite(z)&&Double.isFinite(cx)&&Double.isFinite(cz)&&Double.isFinite(yaw)
                &&Double.isFinite(width)&&Double.isFinite(length)&&Double.isFinite(depth)&&width>0&&length>0&&depth>0&&SoleProfiles.valid(profile);
    }
    private static double worldPolygonEdge(double[][] points,double x,double z,double halfWidth,double halfLength){
        boolean inside=true;double minimum=Double.POSITIVE_INFINITY;
        for(int i=0;i<points.length;i++){var a=points[i];var b=points[(i+1)%points.length];
            double ax=a[0]*halfWidth,az=a[1]*halfLength,dx=(b[0]-a[0])*halfWidth,dz=(b[1]-a[1])*halfLength;
            if(dx*(z-az)-dz*(x-ax)>1e-13)inside=false;
            double t=Math.clamp(((x-ax)*dx+(z-az)*dz)/(dx*dx+dz*dz),0,1),qx=x-ax-t*dx,qz=z-az-t*dz;
            minimum=Math.min(minimum,qx*qx+qz*qz);}
        return (inside?-1:1)*Math.sqrt(minimum);
    }
}
