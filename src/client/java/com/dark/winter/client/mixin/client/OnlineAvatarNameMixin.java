// Uses authenticated session names in extracted avatar render state.
package com.dark.winter.client.mixin.client;
import com.dark.winter.client.online.OnlineAvatarSkins;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(AvatarRenderer.class)
public abstract class OnlineAvatarNameMixin {
    @Inject(method="extractRenderState",at=@At("TAIL"),require=1)
    private void darkwinter$profileName(Avatar entity,AvatarRenderState state,float partialTick,CallbackInfo ci){
        String nickname=OnlineAvatarSkins.nickname(entity.getUUID());
        // Retain vanilla name visibility, distance, invisibility and first-person body policy.
        if(!nickname.isBlank()&&state.nameTag!=null)state.nameTag=Component.literal(nickname);
    }
}
