package com.winterkore.engine.surface.geometry;

/** Bounded shallow leg/boot drag between real plants; landing soles keep their own exact outlines. */
public final class FootDragTrace {
    public record Point(double x,double y,double z,float yaw,float width,float length) {}
    public record Channel(double x,double y,double z,float yaw,float width,float length) {}
    private FootDragTrace() {}
    /** Short in-volume sweep between consecutive real boot poses, never between completed strides. */
    public static Channel scuff(Point from,Point to,float snowWeight){
        if(from==null||to==null||!Float.isFinite(snowWeight)||snowWeight<.08f||snowWeight>1
                ||!Float.isFinite(from.width+to.width+from.length+to.length))return null;
        double distance=Math.hypot(to.x-from.x,to.z-from.z);
        if(!Double.isFinite(distance+from.y+to.y)||distance<.04||distance>.75||Math.abs(to.y-from.y)>.7)return null;
        float width=Math.min(from.width,to.width)*.90f;
        float length=(float)Math.min(.65,Math.max(Math.min(from.length,to.length),distance+.12));
        if(width<.04||width>2||length<.08)return null;
        float yaw=(float)Math.toDegrees(Math.atan2(-(to.x-from.x),to.z-from.z));
        return new Channel((from.x+to.x)*.5,(from.y+to.y)*.5,(from.z+to.z)*.5,yaw,width,length);
    }
    public static Channel connect(Point from,Point to,float snowWeight){
        if(from==null||to==null||!Float.isFinite(snowWeight)||snowWeight<.35f||snowWeight>1
                ||!Float.isFinite(from.width+to.width+from.length+to.length))return null;
        double distance=Math.hypot(to.x-from.x,to.z-from.z);
        double turn=(to.yaw-from.yaw)%360;if(turn>180)turn-=360;if(turn< -180)turn+=360;
        if(!Double.isFinite(distance+turn+from.y+to.y)||distance<.15||distance>2.5
                ||Math.abs(to.y-from.y)>.4||Math.abs(turn)>45)return null;
        float width=Math.min(from.width,to.width)*.8f;
        float length=(float)Math.min(4,distance+Math.min(from.length,to.length)*.3);
        if(width<.04||width>2||length<.08)return null;
        float yaw=(float)Math.toDegrees(Math.atan2(-(to.x-from.x),to.z-from.z));
        return new Channel((from.x+to.x)*.5,(from.y+to.y)*.5,(from.z+to.z)*.5,yaw,width,length);
    }
}
