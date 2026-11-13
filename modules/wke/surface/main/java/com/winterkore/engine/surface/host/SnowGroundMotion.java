// Grounded ascent and weather-growth recovery over real supported snow. No position writes or collision retries.
package com.winterkore.engine.surface.host;

import java.util.function.Function;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Only a clear, shallow uphill snow-support change can replace the gravity request. */
public final class SnowGroundMotion {
    private static final double EPS=1e-7,MATCH=2e-4,MAX_RISE=.125,MAX_RECOVERY_RISE=1.5;
    private static final ThreadLocal<Move> ACTIVE=new ThreadLocal<>();
    private SnowGroundMotion(){}
    public static final class Move {
        private final Player player;
        private final Move previous;
        private final boolean ordinary;
        private boolean supported;
        private Move(Player player,MoverType type){
            this.player=player;previous=ACTIVE.get();ordinary=type==MoverType.SELF||type==MoverType.PLAYER;
            ACTIVE.set(this);
        }
    }
    public static Move begin(Entity entity,MoverType type){return entity instanceof Player p?new Move(p,type):null;}
    public static void finish(Move move,boolean completed){
        if(move==null)return;
        try{
            if(completed&&move.supported&&move.ordinary&&physical(move.player)){
                Player p=move.player;AABB body=p.getBoundingBox();
                double actual=SnowCollisionSupport.supportHeight(p.level(),body,body.minY);
                if(Double.isFinite(actual)&&Math.abs(body.minY-actual)<=MATCH&&p.getDeltaMovement().y<=.05){
                    p.setOnGround(true);Vec3 velocity=p.getDeltaMovement();p.setDeltaMovement(velocity.x,0,velocity.z);
                    p.resetFallDistance();
                }
            }
        }finally{
            if(ACTIVE.get()!=move)throw new IllegalStateException("Snow move scopes closed out of order");
            if(move.previous==null)ACTIVE.remove();else ACTIVE.set(move.previous);
        }
    }
    public static Vec3 collide(Player p,Vec3 requested,boolean allowFollow,Function<Vec3,Vec3> vanilla){
        Move move=ACTIVE.get();
        if(move==null||move.player!=p||!move.ordinary)return vanilla.apply(requested);
        move.supported=false;
        if(!allowFollow||!physical(p)||!p.onGround()||requested.y>.05||p.getDeltaMovement().y>.05
                ||!Double.isFinite(requested.x+requested.y+requested.z))return vanilla.apply(requested);
        AABB body=p.getBoundingBox();if(body.getXsize()>2||body.getZsize()>2)return vanilla.apply(requested);
        double current=SnowCollisionSupport.supportHeight(p.level(),body,body.minY);
        double penetration=current-body.minY;
        // Catch-up weather can publish an already-grown cap around standing feet. Recover before the horizontal-motion gate.
        if(Double.isFinite(penetration)&&penetration>MATCH&&penetration<=MAX_RECOVERY_RISE
                &&snowSupport(p,body,body.minY)){
            AABB destination=body.move(requested.x,0,requested.z);
            double ahead=SnowCollisionSupport.supportHeight(p.level(),destination,body.minY);
            double rise=Math.max(current,Double.isFinite(ahead)?ahead:current)-body.minY;
            AABB raised=body.move(0,rise+EPS,0).expandTowards(requested.x,0,requested.z);
            AABB sweep=body.expandTowards(0,rise+EPS,0).expandTowards(requested.x,0,requested.z).deflate(EPS);
            if(rise<=MAX_RECOVERY_RISE&&rise-penetration<=MAX_RISE+EPS&&hardSweepClear(p,sweep)&&p.level().noCollision(p,raised)){
                Vec3 result=vanilla.apply(new Vec3(requested.x,rise,requested.z));
                AABB end=body.move(result.x,result.y,result.z);
                double actual=SnowCollisionSupport.supportHeight(p.level(),end,end.minY);
                move.supported=Double.isFinite(actual)&&Math.abs(end.minY-actual)<=MATCH;
                return result;
            }
            // Held input may point into a tree or wall. Its rejected horizontal sweep must not
            // prevent recovery from snow already surrounding stationary feet beside that obstacle.
            AABB vertical=body.expandTowards(0,penetration+EPS,0).deflate(EPS);
            if(hardSweepClear(p,vertical)&&p.level().noCollision(p,body.move(0,penetration+EPS,0))){
                Vec3 result=vanilla.apply(new Vec3(0,penetration,0));
                AABB end=body.move(result.x,result.y,result.z);
                double actual=SnowCollisionSupport.supportHeight(p.level(),end,end.minY);
                move.supported=Double.isFinite(actual)&&Math.abs(end.minY-actual)<=MATCH;
                return result;
            }
        }
        if(Math.hypot(requested.x,requested.z)<1e-5)return vanilla.apply(requested);
        AABB target=body.move(requested.x,0,requested.z);
        double future=SnowCollisionSupport.supportHeight(p.level(),target,body.minY);
        if(!Double.isFinite(current)||!Double.isFinite(future)||Math.abs(current-body.minY)>MAX_RISE+EPS)
            return vanilla.apply(requested);
        double rise=future-body.minY;
        if(!snowSupport(p,target,body.minY))return vanilla.apply(requested);
        if(rise<=EPS||rise>MAX_RISE+EPS||future<current-EPS)
            return settleOrdinaryStep(p,body,requested,vanilla.apply(requested),move);
        // Start above a currently intersecting snow skin; this is a clearance query only.
        // Walls/ceilings cause an unchanged vanilla request, preserving its ordinary wall slide.
        double initialGap=Math.max(0,current-body.minY);
        AABB clearStart=body.move(0,initialGap+EPS,0);
        if(!p.level().noCollision(p,clearStart.expandTowards(0,Math.max(0,rise-initialGap),0))
                ||!p.level().noCollision(p,body.move(0,rise+EPS,0).expandTowards(requested.x,0,requested.z)))
            return vanilla.apply(requested);
        Vec3 result=vanilla.apply(new Vec3(requested.x,rise,requested.z));
        AABB end=body.move(result.x,result.y,result.z);
        double actual=SnowCollisionSupport.supportHeight(p.level(),end,end.minY);
        move.supported=Double.isFinite(actual)&&Math.abs(end.minY-actual)<=MATCH;
        return result;
    }
    /** Vanilla may step onto the leading edge and leave a few millimetres inside the same rising skin.
     * Raise only that already accepted endpoint, never its X/Z or the step's approved horizontal path. */
    private static Vec3 settleOrdinaryStep(Player p,AABB body,Vec3 requested,Vec3 result,Move move){
        if(result.y<=EPS||result.y>p.maxUpStep()+EPS)return result;
        AABB end=body.move(result.x,result.y,result.z);
        double actual=SnowCollisionSupport.supportHeight(p.level(),end,end.minY),extra=actual-end.minY;
        if(!Double.isFinite(actual)||extra<=EPS||extra>MAX_RISE+EPS)return result;
        double total=result.y+extra;
        AABB raised=body.move(0,total+EPS,0).expandTowards(result.x,0,result.z);
        if(!p.level().noCollision(p,end.move(0,EPS,0).expandTowards(0,extra,0))
                ||!p.level().noCollision(p,raised))return result;
        move.supported=true;
        return new Vec3(result.x,total,result.z);
    }
    private static boolean snowSupport(Player p,AABB box,double y){
        for(int x=(int)Math.floor(box.minX);x<=(int)Math.floor(Math.nextDown(box.maxX));x++)
            for(int z=(int)Math.floor(box.minZ);z<=(int)Math.floor(Math.nextDown(box.maxZ));z++){
                int base=(int)Math.floor(y+.1);
                for(int dy=2;dy>=-3;dy--){
                    var cell=SnowSurfaceRuntime.geometry(p.level(),new net.minecraft.core.BlockPos(x,base+dy,z));
                    if(cell==null)continue;
                    if(cell.layers()>0)return true;
                    if(cell.generic()&&SnowSurfaceRuntime.fineDeltaMillimeters(p.level(),cell.pos())>.01)
                        for(var patch:cell.patches())if(box.maxX>x+patch.minX()&&box.minX<x+patch.maxX()
                                &&box.maxZ>z+patch.minZ()&&box.minZ<z+patch.maxZ())return true;
                }
            }
        return false;
    }
    /** The initial cap is already intersecting the body. Ignore only that soft native snow volume;
     * the entire ascent still respects ordinary soil, slabs, furniture and shifted attachment bodies. */
    private static boolean hardSweepClear(Player player,AABB sweep){
        int x0=(int)Math.floor(sweep.minX),x1=(int)Math.floor(Math.nextDown(sweep.maxX));
        int y0=(int)Math.floor(sweep.minY),y1=(int)Math.floor(Math.nextDown(sweep.maxY));
        int z0=(int)Math.floor(sweep.minZ),z1=(int)Math.floor(Math.nextDown(sweep.maxZ));
        if((long)(x1-x0+1)*(y1-y0+1)*(z1-z0+1)>256)return false;
        var checked=new java.util.HashSet<net.minecraft.core.BlockPos>();
        for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++){
            var physical=new net.minecraft.core.BlockPos(x,y,z);if(!player.level().hasChunkAt(physical))return false;
            var shifted=com.dark.winter.placement.AnchorRuntime.logicalOwners(player.level(),physical);
            for(int index=-1;index<shifted.size();index++){
                var owner=index<0?physical:shifted.get(index);if(!checked.add(owner))continue;
                if(!player.level().hasChunkAt(owner))return false;
                var state=player.level().getBlockState(owner);
                if(SnowHostGeometry.nativeLayers(state)>0)continue;
                for(var box:state.getCollisionShape(player.level(),owner,net.minecraft.world.phys.shapes.CollisionContext.empty()).toAabbs())
                    if(box.move(owner.getX(),owner.getY(),owner.getZ()).intersects(sweep))return false;
            }
        }
        return true;
    }
    public static boolean clear(Player player,AABB query,AABB footprint){return player.level().noCollision(player,query);}
    private static boolean physical(Player p){
        return p.isAlive()&&!p.noPhysics&&!p.isSpectator()&&!p.getAbilities().flying&&!p.isPassenger()
                &&!p.isFallFlying()&&!p.isInLiquid()&&!p.onClimbable()&&!p.isSleeping()&&!p.isVisuallySwimming();
    }
}
