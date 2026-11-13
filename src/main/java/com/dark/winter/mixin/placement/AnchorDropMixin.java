package com.dark.winter.mixin.placement;
import com.dark.winter.placement.AnchorRuntime;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(Block.class)
public abstract class AnchorDropMixin {
    @Unique private static final ThreadLocal<Vec3> darkwinter$surfaceDrop=new ThreadLocal<>();
    @WrapMethod(method="popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V")
    private static void darkwinter$drop(Level level,BlockPos pos,ItemStack stack,Operation<Void> original){
        Vec3 before=darkwinter$surfaceDrop.get();darkwinter$surfaceDrop.set(AnchorRuntime.dropOffset(level,pos));
        try{original.call(level,pos,stack);}finally{if(before==null)darkwinter$surfaceDrop.remove();else darkwinter$surfaceDrop.set(before);}
    }
    @WrapMethod(method="popResourceFromFace")
    private static void darkwinter$faceDrop(Level level,BlockPos pos,Direction face,ItemStack stack,Operation<Void> original){
        Vec3 before=darkwinter$surfaceDrop.get();darkwinter$surfaceDrop.set(AnchorRuntime.dropOffset(level,pos));
        try{original.call(level,pos,face,stack);}finally{if(before==null)darkwinter$surfaceDrop.remove();else darkwinter$surfaceDrop.set(before);}
    }
    @ModifyExpressionValue(method="popResource(Lnet/minecraft/world/level/Level;Ljava/util/function/Supplier;Lnet/minecraft/world/item/ItemStack;)V",at=@At(value="INVOKE",target="Ljava/util/function/Supplier;get()Ljava/lang/Object;"))
    private static Object darkwinter$physicalDrop(Object value){
        Vec3 offset=darkwinter$surfaceDrop.get();
        if(value instanceof ItemEntity item&&offset!=null&&!offset.equals(Vec3.ZERO))item.setPos(item.position().add(offset));
        return value;
    }
}
