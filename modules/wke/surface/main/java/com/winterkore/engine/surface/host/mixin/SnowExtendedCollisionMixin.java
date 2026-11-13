package com.winterkore.engine.surface.host.mixin;

import com.winterkore.engine.surface.host.SnowSurfaceRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native broad-phase must visit a snow owner whose dynamic support extends into the cell above. */
@Mixin(BlockCollisions.class)
public abstract class SnowExtendedCollisionMixin {
    @Shadow @Final private BlockPos.MutableBlockPos pos;
    @Shadow @Final private CollisionGetter collisionGetter;
    @Redirect(method="computeNext",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/level/block/state/BlockState;hasLargeCollisionShape()Z"),require=1)
    private boolean wke$visitExtendedSnow(BlockState state){
        if(state.hasLargeCollisionShape())return true;
        return collisionGetter instanceof Level level&&SnowSurfaceRuntime.knownFineDeltaMillimeters(level,pos)>0;
    }
}
