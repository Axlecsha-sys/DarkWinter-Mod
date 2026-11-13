package com.dark.winter.mixin.placement;
import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.placement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(BlockGetter.class)
public interface AnchorRayMixin {
    @Inject(method="clip",at=@At("RETURN"),cancellable=true)
    private void darkwinter$indexedRay(ClipContext context,CallbackInfoReturnable<BlockHitResult> cir){
        cir.setReturnValue(AnchorRuntime.ray((BlockGetter)this,context,cir.getReturnValue()));
    }
    @Inject(method="clipWithInteractionOverride",at=@At("HEAD"),cancellable=true)
    private void darkwinter$authoredRay(Vec3 from,Vec3 to,BlockPos pos,VoxelShape shape,BlockState state,CallbackInfoReturnable<BlockHitResult> cir){
        if(shape.isEmpty()||!(state.getBlock() instanceof FurnitureBlock))return;
        SurfaceGeometry.Hit hit=SurfaceGeometry.hit((BlockGetter)this,pos,from,to);
        cir.setReturnValue(hit==null?null:new BlockHitResult(hit.point(),hit.face(),pos,false));
    }
}
