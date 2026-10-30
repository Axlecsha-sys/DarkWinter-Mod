// Final native item mutation boundary, including creative packets with custom item data.
package com.dark.winter.online.server;
import com.dark.winter.online.world.IslandAccess.Permission;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.GameMasterBlock;
/** Final native item mutation boundary, including creative packets with custom item data. */
public final class OnlineNativeRules {
    private OnlineNativeRules(){}
    public static boolean item(ServerPlayer player,ItemStack stack) {
        if(!OnlineGameHost.restricted(player))return true;
        if(stack.getItem() instanceof BlockItem block) {
            if(block.getBlock() instanceof GameMasterBlock&&!OnlineGameHost.nativeOwner(player))return false;
            return OnlineGameHost.nativeAllowed(player,Permission.BUILD);
        }
        if(OnlineGameHost.nativeGameplayAllowed(player,Permission.OBJECTS))return true;
        String id=BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if(id.equals("milk_bucket")||stack.has(DataComponents.CONSUMABLE)||id.equals("shield")||id.equals("spyglass"))
            return OnlineGameHost.nativeAllowed(player,Permission.INTERACT);
        if(id.contains("bucket"))return OnlineGameHost.nativeAllowed(player,Permission.WATER);
        if(id.equals("bone_meal"))return OnlineGameHost.nativeAllowed(player,Permission.VEGETATION);
        // A nonconsumable native item may create/modify an entity (projectile, boat, egg, frame, tool).
        return OnlineGameHost.nativeAllowed(player,Permission.OBJECTS);
    }
}
