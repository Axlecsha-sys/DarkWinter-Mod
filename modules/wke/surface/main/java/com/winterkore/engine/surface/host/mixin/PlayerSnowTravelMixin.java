package com.winterkore.engine.surface.host.mixin;

import com.winterkore.engine.surface.host.SnowSurfaceRuntime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Composes with the existing wind modifier; Minecraft still performs movement and collision. */
@Mixin(Player.class)
public abstract class PlayerSnowTravelMixin {
    @ModifyVariable(method="travel(Lnet/minecraft/world/phys/Vec3;)V",at=@At("HEAD"),argsOnly=true,ordinal=0,require=1)
    private Vec3 wke$snowResistance(Vec3 input){return SnowSurfaceRuntime.travelInput((Player)(Object)this,input);}
}
