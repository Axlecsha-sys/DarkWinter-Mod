// Only SELF movement enters the climb state; packet/piston checks aren't replayed as a climb.
package com.dark.winter.mixin;

import com.dark.winter.movement.TerrainStepAccess;
import com.dark.winter.movement.TerrainStepMotion;
import com.winterkore.engine.surface.host.SnowGroundMotion;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Only SELF movement enters the climb state; packet/piston checks aren't replayed as a climb. */
@Mixin(Entity.class)
public abstract class EntityTerrainStepMixin implements TerrainStepAccess {
    @Unique private TerrainStepMotion darkwinter$step;
    @Unique private boolean darkwinter$selfMove, darkwinter$clipOnly;

    @Override public TerrainStepMotion darkwinter$stepMotion() {
        if (darkwinter$step==null) darkwinter$step=new TerrainStepMotion();
        return darkwinter$step;
    }

    @WrapMethod(method="move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V")
    private void darkwinter$moveContext(MoverType type,Vec3 movement,Operation<Void> original) {
        boolean previous=darkwinter$selfMove;
        darkwinter$selfMove=type==MoverType.SELF;
        if (!darkwinter$selfMove && darkwinter$step!=null) darkwinter$step.cancel();
        SnowGroundMotion.Move snowMove=SnowGroundMotion.begin((Entity)(Object)this,type);
        boolean completed=false;
        try { original.call(type,movement);completed=true; }
        finally {
            try { SnowGroundMotion.finish(snowMove,completed); }
            finally { darkwinter$selfMove=previous; }
        }
    }

    @WrapMethod(method="collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;")
    private Vec3 darkwinter$climbCollision(Vec3 requested,Operation<Vec3> original) {
        if (!((Object)this instanceof Player p)) return original.call(requested);
        if (!darkwinter$selfMove) return SnowGroundMotion.collide(p,requested,true,value->original.call(value));
        TerrainStepMotion step=darkwinter$stepMotion();
        boolean wasClimbing=step.active();
        return step.collide(p,requested,planned -> {
            boolean previous=darkwinter$clipOnly;
            // Cancelling halfway up must not let vanilla instantly finish the
            // remaining ordinary-height step in this same collision call.
            darkwinter$clipOnly=wasClimbing || step.active();
            try { return SnowGroundMotion.collide(p,planned,!wasClimbing&&!step.active(),value->original.call(value)); }
            finally { darkwinter$clipOnly=previous; }
        });
    }

    @ModifyExpressionValue(method="collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;maxUpStep()F"),require=1)
    private float darkwinter$noInstantStepDuringClimb(float ordinary) {
        return darkwinter$clipOnly?0.0f:ordinary;
    }
}
