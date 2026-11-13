package com.winterkore.engine.surface.geometry;

import java.util.ArrayList;
import java.util.List;

/** Fixed footwear stamps and immutable, distance-spaced deep-snow segments.
 * Animation only supplies the real contact point; it never resizes an existing stamp. */
public final class FixedSnowTrack {
    public static final double DEEP_SNOW_MM=200, SEGMENT_METERS=.18;
    private static final double MAX_BRIDGE_METERS=1.6;
    private static final int MAX_SEGMENTS=8;
    public record Stamp(double x,double y,double z,float yaw,float width,float length,boolean channel) {}
    private Stamp anchor,lastSole;
    private long soleCycle=Long.MIN_VALUE;
    private boolean deep;
    private int profile=-1;
    private double scale;

    public void reset(){anchor=lastSole=null;soleCycle=Long.MIN_VALUE;profile=-1;}
    /** A lifted foot breaks a channel; pose noise cannot issue another sole in the same stride. */
    public void release(){anchor=null;}

    public List<Stamp> sample(double x,double y,double z,float yaw,int soleProfile,double avatarScale,
            long cycle,double physicalSnowMillimeters){
        if(!Double.isFinite(x+y+z+avatarScale+physicalSnowMillimeters)||!Float.isFinite(yaw)
                ||!SoleProfiles.valid(soleProfile)||avatarScale<=0||avatarScale>4||physicalSnowMillimeters<1){
            release();return List.of();
        }
        if(profile!=soleProfile||scale!=avatarScale){reset();profile=soleProfile;scale=avatarScale;}
        var shape=SoleProfiles.profile(soleProfile);
        float width=(float)shape.worldWidth(avatarScale),length=(float)shape.worldLength(avatarScale);
        boolean nextDeep=physicalSnowMillimeters>=DEEP_SNOW_MM;
        if(nextDeep!=deep){anchor=null;soleCycle=Long.MIN_VALUE;deep=nextDeep;}
        var point=new Stamp(x,y,z,yaw,width,length,false);
        if(!deep){
            if(cycle==soleCycle)return List.of();
            soleCycle=cycle;
            if(lastSole!=null&&Math.hypot(x-lastSole.x,z-lastSole.z)<.04)return List.of();
            lastSole=point;anchor=null;return List.of(point);
        }
        if(anchor==null){anchor=point;lastSole=point;soleCycle=cycle;return List.of(point);}
        double dx=x-anchor.x,dz=z-anchor.z,distance=Math.hypot(dx,dz);
        if(distance<SEGMENT_METERS-1e-9)return List.of();
        if(distance>MAX_BRIDGE_METERS||Math.abs(y-anchor.y)>.7){
            anchor=point;lastSole=point;soleCycle=cycle;return List.of(point);
        }
        int count=Math.min(MAX_SEGMENTS,(int)Math.floor((distance+1e-9)/SEGMENT_METERS));
        var result=new ArrayList<Stamp>(count);
        double startX=anchor.x,startY=anchor.y,startZ=anchor.z;
        float direction=(float)Math.toDegrees(Math.atan2(-dx,dz));
        for(int i=1;i<=count;i++){
            double t=Math.min(1,i*SEGMENT_METERS/distance);
            double endX=startX+dx*t,endY=startY+(y-startY)*t,endZ=startZ+dz*t;
            // Every deep segment has the same cross section and length for this footwear.
            // The original segment is never stretched toward a newer endpoint.
            result.add(new Stamp((anchor.x+endX)*.5,(anchor.y+endY)*.5,(anchor.z+endZ)*.5,
                    direction,width,(float)(length+SEGMENT_METERS),true));
            anchor=new Stamp(endX,endY,endZ,direction,width,length,false);
        }
        soleCycle=cycle;return List.copyOf(result);
    }
}
