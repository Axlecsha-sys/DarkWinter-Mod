// Actual articulated TP sole placement. All model/gait math is independent of Minecraft.
package com.winterkore.engine.surface.geometry;

public final class HumanFootPose {
    public record Point(double x,double y,double z) {
        Point add(double x,double y,double z){return new Point(this.x+x,this.y+y,this.z+z);}
        Point x(double a){double c=Math.cos(a),s=Math.sin(a);return new Point(x,y*c-z*s,y*s+z*c);}
        Point y(double a){double c=Math.cos(a),s=Math.sin(a);return new Point(x*c+z*s,y,-x*s+z*c);}
        Point z(double a){double c=Math.cos(a),s=Math.sin(a);return new Point(x*c-y*s,x*s+y*c,z);}
    }
    public record Plant(double x,double y,double z,float yaw,float width,float length,int soleProfile,boolean left) {}
    private HumanFootPose(){}
    /** First flat stance of the actual authored curve; not a separately invented step distance. */
    public static double stancePhase(HumanGaitTimeline.Snapshot p){
        double lo=0,hi=Math.PI*.5;
        if(p.movement()<1e-4||p.crouching()>.999f)return hi;
        double a=pitch(p,lo),b=pitch(p,hi);
        if(Math.abs(a)<1e-7&&Math.abs(b)<1e-7)return hi;
        if(a>=0)return lo;if(b<0)return hi;
        for(int i=0;i<28;i++){double mid=(lo+hi)*.5;if(pitch(p,mid)<0)lo=mid;else hi=mid;}return (lo+hi)*.5;
    }
    private static double pitch(HumanGaitTimeline.Snapshot p,double phase){
        var leg=HumanGaitCurves.leg(phase,p.movement(),p.running(),p.crouching(),p.airborne(),p.takeoff(),p.landing(),p.falling(),p.snow(),false);
        return leg.thigh()+leg.knee()+leg.foot();
    }
    private static HumanGaitCurves.Leg leg(HumanGaitTimeline.Snapshot p,boolean left){
        return HumanGaitCurves.leg(p.phase(),p.movement(),p.running(),p.crouching(),p.airborne(),p.takeoff(),p.landing(),p.falling(),p.snow(),left);
    }
    /** Scalar Y row of the exact articulated bone chain; no per-vertex matrix/vector allocation. */
    public static double supportDepth(FootRigHulls.Hull hull,double upper,double lower,double thigh,double knee,double foot,double toe,double roll){
        double angle=thigh+knee+foot,c=Math.cos(angle),s=Math.sin(angle);
        double cy=Math.cos(toe),sy=Math.sin(toe),cr=Math.cos(roll),sr=Math.sin(roll);
        double offsetY=upper*Math.cos(thigh)+lower*Math.cos(thigh+knee);
        double offsetZ=upper*Math.sin(thigh)+lower*Math.sin(thigh+knee),max=-Double.MAX_VALUE;
        double ax=cy*sr,ay=s*sy*sr+c*cr,az=c*sy*sr-s*cr,translation=offsetZ*sy*sr+offsetY*cr;
        for(var point:hull.vertices())max=Math.max(max,point.x()*ax+point.y()*ay+point.z()*az+translation);
        return max;
    }
    private static double rigSupport(HumanGaitTimeline.Snapshot p,HumanGaitCurves.Leg pose,boolean left,FootRigHulls.Hull hull){
        return supportDepth(hull,SoleProfiles.KNEE_Y,SoleProfiles.ANKLE_Y-SoleProfiles.KNEE_Y,pose.thigh(),pose.knee(),pose.foot(),
                (left?-.015:.015)-p.turn()*.025,(left?-.025:.025)*p.crouching()*(1-p.airborne()));
    }
    private static Point footPoint(HumanGaitTimeline.Snapshot p,HumanGaitCurves.Leg leg,boolean left,Point point,double sink,double lateral){
        point=point.x(leg.foot()).add(0,SoleProfiles.ANKLE_Y-SoleProfiles.KNEE_Y,0);
        point=point.x(leg.knee()).add(0,SoleProfiles.KNEE_Y,0).x(leg.thigh());
        double toeYaw=(left?-.015:.015)-p.turn()*.025,roll=(left?-.025:.025)*p.crouching()*(1-p.airborne());
        return point.y(toeYaw).z(roll).add((left?SoleProfiles.LEG_PIVOT_X:-SoleProfiles.LEG_PIVOT_X)
                        +lateral,
                SoleProfiles.HIP_Y+sink,-.78*p.crouching()*(1-p.airborne()));
    }
    private static Point world(Point model,double x,double y,double z,double yaw,double scale){
        double a=Math.toRadians(yaw),c=Math.cos(a),s=Math.sin(a),unit=SoleProfiles.AVATAR_SCALE*scale/16;
        // Stock Avatar: rotateY(180-bodyYaw), scale(-.9375,-.9375,+.9375), translateY(-1.501).
        return new Point(x+(model.x*c+model.z*s)*unit,y+(1.501*16-model.y)*unit,z+(model.x*s-model.z*c)*unit);
    }
    public static Plant sample(HumanGaitTimeline.Snapshot p,boolean left,int profile,double x,double y,double z,double bodyYaw,double scale){
        return sample(p,left,profile,x,y,z,bodyYaw,scale,FootRigHulls.snapshot(),-1);
    }
    public static Plant sample(HumanGaitTimeline.Snapshot p,boolean left,int profile,double x,double y,double z,double bodyYaw,double scale,
            FootRigHulls.Snapshot rig,int rigKey){
        var sole=SoleProfiles.profile(profile);var rightPose=leg(p,false);var leftPose=leg(p,true);var pose=left?leftPose:rightPose;
        var rightHull=rigKey<0?FootRigHulls.fallback(profile,false):rig.hull(rigKey,false,profile);
        var leftHull=rigKey<0?FootRigHulls.fallback(profile,true):rig.hull(rigKey,true,profile);
        double sink=(SoleProfiles.LEG_LENGTH-Math.max(rigSupport(p,rightPose,false,rightHull),rigSupport(p,leftPose,true,leftHull)))*(1-p.airborne());
        double lateral=HumanGaitCurves.pelvisShift(p.phase(),p.movement(),p.crouching(),p.airborne(),p.snow());
        Point local=new Point((left?1:-1)*sole.outboard()*SoleProfiles.LEG_SCALE_X,
                (SoleProfiles.SOLE_Y-11.45)*SoleProfiles.LEG_SCALE_Y,sole.centreZ()*SoleProfiles.LEG_SCALE_Z);
        Point centre=footPoint(p,pose,left,local,sink,lateral),wx=footPoint(p,pose,left,local.add(sole.width()*SoleProfiles.LEG_SCALE_X,0,0),sink,lateral),
                wz=footPoint(p,pose,left,local.add(0,0,-sole.length()*SoleProfiles.LEG_SCALE_Z),sink,lateral);
        var a=world(centre,x,y,z,bodyYaw,scale);var b=world(wx,x,y,z,bodyYaw,scale);var c=world(wz,x,y,z,bodyYaw,scale);
        float yaw=(float)Math.toDegrees(Math.atan2(-(c.x-a.x),c.z-a.z));
        return new Plant(a.x,a.y,a.z,yaw,(float)Math.hypot(b.x-a.x,b.z-a.z),(float)Math.hypot(c.x-a.x,c.z-a.z),profile,left);
    }
}
