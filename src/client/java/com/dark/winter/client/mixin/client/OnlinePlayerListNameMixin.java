// Displays authenticated online profile names in the native player list.
package com.dark.winter.client.mixin.client;
import com.dark.winter.client.online.OnlineAvatarSkins;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(PlayerInfo.class)
public abstract class OnlinePlayerListNameMixin {
    @Shadow public abstract GameProfile getProfile();
    @Inject(method="getTabListDisplayName",at=@At("RETURN"),cancellable=true,require=1)
    private void darkwinter$profileListName(CallbackInfoReturnable<Component> result){
        String nickname=OnlineAvatarSkins.nickname(getProfile().id());
        if(!nickname.isBlank())result.setReturnValue(Component.literal(nickname));
    }
}
