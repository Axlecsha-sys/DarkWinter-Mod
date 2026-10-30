// Associates native block-item placement with the current Testing edit scope.
package com.dark.winter.mixin;
import com.dark.winter.testing.TestingNativeEdits;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(BlockItem.class)
public abstract class TestingNativePlacementScopeMixin {
    @WrapMethod(method="place")
    private InteractionResult darkwinter$place(BlockPlaceContext context,Operation<InteractionResult> original){
        if(!(context.getPlayer() instanceof ServerPlayer player))return original.call(context);
        if(TestingNativeEdits.refused(player))return InteractionResult.FAIL;
        var scope=TestingNativeEdits.begin(player,"Нативная установка блоков");InteractionResult result;
        try{result=original.call(context);}catch(RuntimeException|Error failure){scope.abort();throw failure;}finally{scope.close();}
        return scope.accepted()?result:InteractionResult.FAIL;
    }
}
