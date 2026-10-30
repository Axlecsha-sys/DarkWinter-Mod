// Wraps native block placement and breaking in Testing edit scopes.
package com.dark.winter.mixin;
import com.dark.winter.testing.TestingNativeEdits;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.*;
@Mixin(ServerPlayerGameMode.class)
public abstract class TestingNativeGameModeMixin {
    @Shadow @Final protected ServerPlayer player;
    @WrapMethod(method="useItemOn")
    private InteractionResult darkwinter$use(ServerPlayer actor,Level level,ItemStack stack,InteractionHand hand,BlockHitResult hit,Operation<InteractionResult> original){
        if(TestingNativeEdits.refused(actor))return InteractionResult.FAIL;
        var scope=TestingNativeEdits.begin(actor,"Нативная установка / взаимодействие");InteractionResult result;
        try{result=original.call(actor,level,stack,hand,hit);}catch(RuntimeException|Error failure){scope.abort();throw failure;}finally{scope.close();}
        return scope.accepted()?result:InteractionResult.FAIL;
    }
    @WrapMethod(method="destroyBlock")
    private boolean darkwinter$break(BlockPos pos,Operation<Boolean> original){
        if(TestingNativeEdits.refused(player))return false;
        var scope=TestingNativeEdits.begin(player,"Нативное разрушение");boolean result;
        try{scope.seed(pos);result=original.call(pos);}catch(RuntimeException|Error failure){scope.abort();throw failure;}finally{scope.close();}
        return scope.accepted()&&result;
    }
    @WrapMethod(method="handleBlockBreakAction")
    private void darkwinter$breakPacket(BlockPos pos,ServerboundPlayerActionPacket.Action action,Direction direction,int maxY,int sequence,Operation<Void> original){
        if(TestingNativeEdits.refused(player))return;
        var scope=TestingNativeEdits.begin(player,"Нативное разрушение блоков");
        try{scope.seed(pos);original.call(pos,action,direction,maxY,sequence);}catch(RuntimeException|Error failure){scope.abort();throw failure;}finally{scope.close();}
    }
}
