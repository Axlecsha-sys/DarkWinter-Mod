package com.dark.winter.mixin.placement;
import com.dark.winter.placement.AnchorRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(Containers.class)
public abstract class AnchorContainerDropMixin {
    @WrapMethod(method="dropItemStack")
    private static void darkwinter$contents(Level level,double x,double y,double z,ItemStack item,Operation<Void> original){
        Vec3 offset=AnchorRuntime.dropOffset(level,BlockPos.containing(x,y,z));
        original.call(level,x+offset.x,y+offset.y,z+offset.z,item);
    }
}
