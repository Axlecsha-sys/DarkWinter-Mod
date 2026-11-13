// Synchronize the physical attachment offset, not only its renderer, without changing native tile coordinates.
package com.dark.winter.mixin.placement;

import com.dark.winter.placement.hanging.HangingAnchorAccess;
import com.dark.winter.placement.hanging.HangingPlacement;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HangingEntity.class)
public abstract class HangingEntitySurfaceMixin implements HangingAnchorAccess {
    @Unique private static final EntityDataAccessor<Optional<BlockPos>> darkwinter$SUPPORT =
            SynchedEntityData.defineId(HangingEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    @Unique private static final EntityDataAccessor<Direction> darkwinter$NORMAL =
            SynchedEntityData.defineId(HangingEntity.class, EntityDataSerializers.DIRECTION);
    @Unique private static final EntityDataAccessor<Vector3fc> darkwinter$OFFSET =
            SynchedEntityData.defineId(HangingEntity.class, EntityDataSerializers.VECTOR3);
    @Unique private boolean darkwinter$writingAnchor;

    @Shadow protected abstract void recalculateBoundingBox();
    @Shadow protected abstract boolean hasLevelCollision(AABB box);
    @Shadow protected abstract AABB getPopBox();
    @Shadow protected abstract boolean canCoexist(boolean allowIntersectingSameType);

    @Unique private HangingEntity darkwinter$self() { return (HangingEntity)(Object)this; }

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void darkwinter$defineAnchor(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(darkwinter$SUPPORT, Optional.empty());
        builder.define(darkwinter$NORMAL, Direction.SOUTH);
        builder.define(darkwinter$OFFSET, new Vector3f());
    }

    @Override public Optional<BlockPos> darkwinter$surfaceSupport() {
        return darkwinter$self().getEntityData().get(darkwinter$SUPPORT);
    }
    @Override public Direction darkwinter$surfaceNormal() {
        return darkwinter$self().getEntityData().get(darkwinter$NORMAL);
    }
    @Override public Vec3 darkwinter$surfaceOffset() {
        Vector3fc value = darkwinter$self().getEntityData().get(darkwinter$OFFSET);
        return new Vec3(value.x(), value.y(), value.z());
    }

    @Override public void darkwinter$setSurfaceAnchor(@Nullable BlockPos support, Direction normal, Vec3 offset) {
        darkwinter$writingAnchor = true;
        try {
            SynchedEntityData data = darkwinter$self().getEntityData();
            data.set(darkwinter$SUPPORT, Optional.ofNullable(support).map(BlockPos::immutable));
            data.set(darkwinter$NORMAL, normal);
            data.set(darkwinter$OFFSET, new Vector3f((float)offset.x, (float)offset.y, (float)offset.z));
        } finally {
            darkwinter$writingAnchor = false;
        }
    }

    @Inject(method = "onSyncedDataUpdated", at = @At("RETURN"))
    private void darkwinter$receiveAnchor(EntityDataAccessor<?> accessor, CallbackInfo ci) {
        if (!darkwinter$writingAnchor && (accessor.equals(darkwinter$SUPPORT)
                || accessor.equals(darkwinter$NORMAL) || accessor.equals(darkwinter$OFFSET))
                && darkwinter$self().getPos() != null) recalculateBoundingBox();
    }

    @Inject(method = "recalculateBoundingBox", at = @At("RETURN"))
    private void darkwinter$movePhysicalBox(CallbackInfo ci) { HangingPlacement.afterBoundingBox(darkwinter$self()); }

    @Override public boolean darkwinter$surfaceSurvives(boolean allowIntersectingSameType) {
        Boolean supported = HangingPlacement.supported(darkwinter$self(), this);
        return supported == null || supported && !hasLevelCollision(getPopBox()) && canCoexist(allowIntersectingSameType);
    }

    @Inject(method = "survives", at = @At("HEAD"), cancellable = true)
    private void darkwinter$checkActualSupport(CallbackInfoReturnable<Boolean> cir) {
        if (darkwinter$surfaceSupport().isPresent()) cir.setReturnValue(darkwinter$surfaceSurvives(false));
    }
}
