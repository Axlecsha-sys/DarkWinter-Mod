// Uses authenticated session avatar skins while retaining ordinary entity skins.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.online.OnlineAvatarSkins;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The original skin object is returned unchanged outside a current authenticated avatar mapping. */
@Mixin(AbstractClientPlayer.class)
public abstract class OnlineAvatarSkinMixin {
    @Inject(method="getSkin",at=@At("RETURN"),cancellable=true,require=1)
    private void darkwinter$profileAvatar(CallbackInfoReturnable<PlayerSkin> result){
        PlayerSkin original=result.getReturnValue();
        PlayerSkin selected=OnlineAvatarSkins.resolve(((AbstractClientPlayer)(Object)this).getUUID(),original);
        if(selected!=original)result.setReturnValue(selected);
    }
}
