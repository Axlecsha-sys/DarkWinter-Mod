// Applies verified native-player aliases inside a reserved promotion constructor scope.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.online.OnlineNativeAlias;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Sole narrow production alias hook. Unarmed ordinary/initial-owner constructors keep their original profile. */
@Mixin(IntegratedServer.class)
public abstract class OnlinePromotedAliasMixin implements OnlineNativeAlias.ConstructorHook {
    @WrapOperation(method="<init>",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/server/IntegratedServer;setSingleplayerProfile(Lcom/mojang/authlib/GameProfile;)V"),require=1)
    private void darkwinter$verifiedPromotionProfile(IntegratedServer server,GameProfile original,Operation<Void> call){
        GameProfile selected=OnlineNativeAlias.constructorProfile(server,original);
        try{call.call(server,selected);}
        catch(RuntimeException|Error failed){OnlineNativeAlias.constructorSetterFailed(server);throw failed;}
    }
}
