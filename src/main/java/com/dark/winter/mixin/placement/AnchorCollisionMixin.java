package com.dark.winter.mixin.placement;
import com.dark.winter.placement.AnchorRuntime;
import java.util.*;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(CollisionGetter.class)
public interface AnchorCollisionMixin {
    @Inject(method="getBlockCollisionsFromContext",at=@At("RETURN"),cancellable=true)
    private void darkwinter$indexedCollision(CollisionContext context,AABB box,CallbackInfoReturnable<Iterable<VoxelShape>> cir){
        var extra=AnchorRuntime.collisionShapes((BlockGetter)this,box,context);if(extra.isEmpty())return;
        Iterable<VoxelShape> nativeShapes=cir.getReturnValue();
        cir.setReturnValue(()->new Iterator<>(){
            private final Iterator<VoxelShape> first=nativeShapes.iterator(),second=extra.iterator();
            public boolean hasNext(){return first.hasNext()||second.hasNext();}
            public VoxelShape next(){return first.hasNext()?first.next():second.next();}
        });
    }
}
