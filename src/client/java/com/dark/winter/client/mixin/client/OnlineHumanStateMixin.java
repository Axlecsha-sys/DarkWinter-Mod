// Captures authenticated appearance choices for native avatar rendering.
package com.dark.winter.client.mixin.client;
import com.dark.winter.client.online.OnlineHumanAppearance;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(AvatarRenderer.class)
public abstract class OnlineHumanStateMixin {
    @Inject(method="extractRenderState",at=@At("TAIL"),require=1)
    private void darkwinter$humanChoice(Avatar entity,AvatarRenderState state,float partialTick,CallbackInfo ci){
        OnlineHumanAppearance.extract(entity,state);
    }
}
