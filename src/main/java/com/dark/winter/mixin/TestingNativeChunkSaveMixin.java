// Defers native chunk saves when required and tracks their durable write receipts.
package com.dark.winter.mixin;
import com.dark.winter.testing.TestingNativeEdits;
import com.dark.winter.testing.TestingNativeChunkAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ChunkMap.class)
public abstract class TestingNativeChunkSaveMixin implements TestingNativeChunkAccess {
    @Shadow @Final private ServerLevel level;
    @Shadow private boolean save(ChunkAccess chunk){throw new AssertionError("mixin shadow");}
    @Override public boolean darkwinter$save(ChunkAccess chunk){return save(chunk);}
    @Inject(method="save",at=@At("HEAD"),cancellable=true)
    private void darkwinter$defer(ChunkAccess chunk,CallbackInfoReturnable<Boolean> cir){
        if(TestingNativeEdits.deferSave(level.getServer())||!TestingNativeEdits.chunkSaving(level,chunk))cir.setReturnValue(false);
    }
    @WrapOperation(method="save",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ChunkMap;write(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> darkwinter$writeReceipt(ChunkMap map,ChunkPos pos,Supplier<CompoundTag> value,Operation<CompletableFuture<Void>> original){
        CompletableFuture<Void> future=original.call(map,pos,value);
        TestingNativeEdits.chunkQueued(level,pos,future);return future;
    }
}
