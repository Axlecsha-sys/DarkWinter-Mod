package com.dark.winter.interaction;

import com.dark.winter.block.FurnitureBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Native selection geometry, assembly identity and player-to-target visibility in either camera view. */
public record DeadwoodTarget(BlockPos origin,FurnitureBlock block,BlockState state) {
    public static final String ID="forest_branch_pile";
    public static @Nullable DeadwoodTarget at(Level level,BlockPos pos) {
        if(!level.hasChunkAt(pos))return null;
        BlockState state=level.getBlockState(pos);
        if(!(state.getBlock() instanceof FurnitureBlock block)||!block.definition().id().equals(ID))return null;
        if(state.getValue(FurnitureBlock.PART)>=block.definition().parts().size())return null;
        BlockPos origin=block.origin(pos,state);
        if(!level.hasChunkAt(origin))return null;
        BlockState root=level.getBlockState(origin);
        if(!root.is(block)||root.getValue(FurnitureBlock.PART)!=0||root.getValue(FurnitureBlock.FACING)!=state.getValue(FurnitureBlock.FACING))return null;
        return new DeadwoodTarget(origin.immutable(),block,root);
    }
    public static @Nullable DeadwoodTarget hit(Level level,@Nullable HitResult result) {
        return result instanceof BlockHitResult hit&&hit.getType()==HitResult.Type.BLOCK?at(level,hit.getBlockPos()):null;
    }
    /** The shoulder crosshair is offset from the eye ray. Validate its point, not the player's yaw. */
    public boolean visibleFrom(Player player,Vec3 aim) {
        if(!Double.isFinite(aim.x)||!Double.isFinite(aim.y)||!Double.isFinite(aim.z))return false;
        Vec3 eye=player.getEyePosition(1),delta=aim.subtract(eye);
        double distance=delta.length();
        if(distance<1.0E-6||distance>player.blockInteractionRange()+.03)return false;
        // Extend just into the surface to avoid a floating-point miss exactly at the clicked face.
        Vec3 end=aim.add(delta.scale(.03/distance));
        DeadwoodTarget visible=hit(player.level(),player.level().clip(new ClipContext(eye,end,
                ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player)));
        return visible!=null&&visible.origin().equals(origin);
    }
}
