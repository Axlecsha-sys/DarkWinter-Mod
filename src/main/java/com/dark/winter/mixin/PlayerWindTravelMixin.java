// Applies bounded wind resistance through the native player travel path.
package com.dark.winter.mixin;

import com.dark.winter.weather.WindPhysics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Descriptor verified against the installed Mojang-mapped Minecraft 26.3 Player source. */
@Mixin(Player.class)
public abstract class PlayerWindTravelMixin {
    @ModifyVariable(method="travel(Lnet/minecraft/world/phys/Vec3;)V", at=@At("HEAD"), argsOnly=true, ordinal=0, require=1)
    private Vec3 darkwinter$windResistance(Vec3 input) {
        return WindPhysics.travelInput((Player)(Object)this, input);
    }
}
