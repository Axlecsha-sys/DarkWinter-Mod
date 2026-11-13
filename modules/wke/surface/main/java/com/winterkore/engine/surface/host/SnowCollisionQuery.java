// Explicit, nested collision-query footprints. Never infer a future query from mutable entity position.
package com.winterkore.engine.surface.host;

import java.util.function.Function;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/** A query is thread-confined and lives only while the vanilla solver consumes its colliders. */
public final class SnowCollisionQuery {
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    private SnowCollisionQuery(){}
    public static final class Scope implements AutoCloseable {
        private final Entity entity;
        private final AABB footprint;
        private final Scope previous;
        private boolean closed;
        private Scope(Entity entity,AABB footprint){
            this.entity=entity;this.footprint=footprint;previous=CURRENT.get();CURRENT.set(this);
        }
        @Override public void close(){
            if(closed)return;
            if(CURRENT.get()!=this)throw new IllegalStateException("Snow collision scopes closed out of order");
            closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);
        }
    }
    public static Scope enter(Entity entity,AABB footprint){return new Scope(entity,footprint);}
    /** Re-resolve Y against the horizontal displacement actually permitted by walls and other solids. */
    public static Vec3 collide(Entity entity,Vec3 requested,AABB box,Function<Vec3,Vec3> vanilla){
        Vec3 allowed=requested;
        for(int pass=0;pass<4;pass++){
            Vec3 result;
            try(var scope=enter(entity,box.move(allowed.x,0,allowed.z))){result=vanilla.apply(allowed);}
            if(Math.abs(result.x-allowed.x)<=1e-7&&Math.abs(result.z-allowed.z)<=1e-7)return result;
            // Never restore a clipped component: a different floor height must not let a later probe pass a solid.
            allowed=new Vec3(result.x,requested.y,result.z);
        }
        // A pathological alternating corner remains safely blocked for this move; it never sinks into a future floor.
        try(var scope=enter(entity,box)){return vanilla.apply(new Vec3(0,requested.y,0));}
    }
    public static AABB footprint(Entity entity){
        for(Scope scope=CURRENT.get();scope!=null;scope=scope.previous)if(scope.entity==entity)return scope.footprint;
        return entity.getBoundingBox();
    }
}
