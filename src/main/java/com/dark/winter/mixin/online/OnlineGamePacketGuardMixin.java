// Enforces island permissions on native interaction, inventory, movement, and command packets.
package com.dark.winter.mixin.online;
import com.dark.winter.online.server.OnlineGameHost;
import com.dark.winter.online.world.IslandAccess.Permission;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=ServerGamePacketListenerImpl.class,priority=2000)
public abstract class OnlineGamePacketGuardMixin {
    @Shadow public ServerPlayer player;
    @Inject(method={"handleContainerClick","handleContainerButtonClick","handleContainerSlotStateChanged","handlePlaceRecipe","handleSelectTrade","handleRenameItem","handleSetBeaconPacket","handleEditBook","handleSignUpdate","handleInteract","handlePaddleBoat","handleMoveVehicle","handlePlayerCommand","handleBundleItemSelectedPacket"},at=@At("HEAD"),cancellable=true)
    private void darkwinter$interact(CallbackInfo ci){if(!OnlineGameHost.nativeAllowed(player,Permission.INTERACT))ci.cancel();}
    @Inject(method={"handleAttack","handlePunch"},at=@At("HEAD"),cancellable=true)
    private void darkwinter$entities(CallbackInfo ci){if(!OnlineGameHost.nativeGameplayAllowed(player,Permission.OBJECTS))ci.cancel();}
    @Inject(method={"handleSetCreativeModeSlot","handlePickItemFromBlock","handlePickItemFromEntity"},at=@At("HEAD"),cancellable=true)
    private void darkwinter$creative(CallbackInfo ci){if(!OnlineGameHost.nativeAllowed(player,Permission.BUILD))ci.cancel();}
    @Inject(method={"handleChatCommand","handleSignedChatCommand","handleSetCommandBlock","handleSetCommandMinecart","handleSetStructureBlock","handleSetTestBlock","handleTestInstanceBlockAction","handleSetJigsawBlock","handleJigsawGenerate","handleSetGameRule","handleChangeDifficulty","handleChangeGameMode","handleLockDifficulty"},at=@At("HEAD"),cancellable=true)
    private void darkwinter$nativeAuthority(CallbackInfo ci){if(!OnlineGameHost.nativeOwner(player))ci.cancel();}
    @Inject(method={"handleChatSessionUpdate","handleChat"},at=@At("HEAD"),cancellable=true)
    private void darkwinter$darkwinterIdentityChat(CallbackInfo ci) {
        // Mojang chat keys certify a different account UUID; DWO chat uses its own verified identity payload.
        if(OnlineGameHost.authenticatedRemote(player))ci.cancel();
    }
    @Inject(method="handlePlayerAction",at=@At("HEAD"),cancellable=true)
    private void darkwinter$inventoryDrop(ServerboundPlayerActionPacket packet,CallbackInfo ci) {
        switch(packet.getAction()) {
            case DROP_ITEM,DROP_ALL_ITEMS->{if(!OnlineGameHost.nativeGameplayAllowed(player,Permission.OBJECTS))ci.cancel();}
            case SWAP_ITEM_WITH_OFFHAND->{if(!OnlineGameHost.nativeAllowed(player,Permission.INTERACT))ci.cancel();}
            default->{}
        }
    }
}
