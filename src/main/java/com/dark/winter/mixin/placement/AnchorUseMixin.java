package com.dark.winter.mixin.placement;
import com.dark.winter.placement.SurfacePlacement;
import com.dark.winter.placement.FreeFurniturePlacement;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class AnchorUseMixin {
    @WrapMethod(method="useItemOn")
    private InteractionResult darkwinter$localUse(ItemStack stack,Level level,Player player,InteractionHand hand,BlockHitResult hit,Operation<InteractionResult> original){
        if (hand == InteractionHand.MAIN_HAND && FreeFurniturePlacement.applies(stack)) return InteractionResult.PASS;
        return original.call(stack,level,player,hand,SurfacePlacement.logicalHit(level,hit));
    }
    @WrapMethod(method="useWithoutItem")
    private InteractionResult darkwinter$localEmptyUse(Level level,Player player,BlockHitResult hit,Operation<InteractionResult> original){
        return original.call(level,player,SurfacePlacement.logicalHit(level,hit));
    }
}
