package com.winterkore.engine.surface.host;

import com.winterkore.engine.surface.SnowTravelPolicy;
import net.minecraft.world.entity.player.Player;

/** Reads the same immutable client cap/server surface as collision and footprints. No scans or chunk loads. */
public final class SnowLocomotion {
    private SnowLocomotion() {}
    private record Cached(int tick,double x,double y,double z,double depth) {}
    private static final java.util.Map<Player,Cached> CACHE=new java.util.WeakHashMap<>();
    public static double depthMillimeters(Player player) {
        if(player==null)return 0;
        Cached old;synchronized(CACHE){old=CACHE.get(player);}
        if(old!=null&&old.tick==player.tickCount&&old.x==player.getX()&&old.y==player.getY()&&old.z==player.getZ())return old.depth;
        double depth=computeDepth(player);
        synchronized(CACHE){CACHE.put(player,new Cached(player.tickCount,player.getX(),player.getY(),player.getZ(),depth));}
        return depth;
    }
    private static double computeDepth(Player player) {
        if(player==null||!player.isAlive()||player.isSpectator()
                ||player.getAbilities().flying||player.isPassenger()||player.isFallFlying()
                ||player.isInLiquid()||player.onClimbable()||player.noPhysics)return 0;
        double depth=depthAt(player,player.getX(),player.getZ());
        if(depth>0)return depth;
        // A foot may be supported by a narrow patch even while the avatar centre is over its opening.
        var body=player.getBoundingBox();double inset=.02;
        double x0=body.minX+inset,x1=body.maxX-inset,z0=body.minZ+inset,z1=body.maxZ-inset;
        return Math.max(Math.max(depthAt(player,x0,z0),depthAt(player,x1,z0)),
                Math.max(depthAt(player,x0,z1),depthAt(player,x1,z1)));
    }
    private static double depthAt(Player player,double x,double z){
        var level=player.level();var cell=SnowSurfaceRuntime.findGeometry(level,x,player.getY(),z);
        if(cell==null)return 0;
        var corners=SnowSurfaceRuntime.fineCornersMillimeters(level,cell);
        double top=SnowHostGeometry.sample(cell,x,z,corners).height();
        double bottom=SnowHostGeometry.snowBottomHeight(cell,x,z);
        double relief=SnowSurfaceRuntime.depressionMillimeters(level,cell,corners,x,z);
        double physical=Math.max(bottom,Math.min(top-relief/1000,
                top-SnowTravelPolicy.sinkMillimeters((top-bottom)*1000,cell.layers()*125)/1000));
        if(!Double.isFinite(physical)||Math.abs(physical-player.getY())>.25)return 0;
        double depth=(top-bottom)*1000-Math.max(0,relief);
        return Double.isFinite(depth)?Math.max(0,depth):0;
    }
    /** Visual/contact support only. Never writes the entity's physical onGround flag. */
    public static boolean grounded(Player player) {
        if(player==null)return false;
        if(player.onGround())return true;
        if(player.getDeltaMovement().y>.05||depthMillimeters(player)<=0)return false;
        double support=SnowCollisionSupport.supportHeight(player.level(),player.getBoundingBox(),player.getY());
        return Double.isFinite(support)&&Math.abs(support-player.getY())<=.020;
    }
    public static float gaitWeight(Player player) {return SnowTravelPolicy.gaitWeight(depthMillimeters(player));}
}
