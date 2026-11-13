// Capture the actual footprint of each vanilla collision alternative, including nested ordinary step probes.
package com.winterkore.engine.surface.host.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.winterkore.engine.surface.host.SnowCollisionQuery;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Entity.class)
public abstract class SnowCollisionQueryMixin {
    @WrapMethod(method="collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;")
    private static Vec3 wke$entityFootprint(Entity source,Vec3 movement,AABB box,Level level,List<VoxelShape> colliders,Operation<Vec3> original){
        if(!(source instanceof Player))return original.call(source,movement,box,level,colliders);
        return SnowCollisionQuery.collide(source,movement,box,planned->original.call(source,planned,box,level,colliders));
    }
    @WrapMethod(method="collideBoundingBox(Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;")
    private static Vec3 wke$contextFootprint(CollisionContext source,Vec3 movement,AABB box,Level level,List<VoxelShape> colliders,Operation<Vec3> original){
        if(!(source instanceof EntityCollisionContext context)||!(context.getEntity() instanceof Player player))
            return original.call(source,movement,box,level,colliders);
        return SnowCollisionQuery.collide(player,movement,box,planned->original.call(source,planned,box,level,colliders));
    }
}
