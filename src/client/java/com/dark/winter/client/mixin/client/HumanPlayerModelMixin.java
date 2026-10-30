// Replace the avatar mesh only; armor, mobs, collision and camera retain their own contracts.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.equipment.ThirdPersonBodyModel;
import net.minecraft.core.ClientAsset;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Replace the avatar mesh only; armor, mobs, collision and camera retain their own contracts. */
@Mixin(AvatarRenderer.class)
public abstract class HumanPlayerModelMixin {
    private static final ClientAsset.ResourceTexture DARKWINTER_BODY = new ClientAsset.ResourceTexture(
            Identifier.fromNamespaceAndPath("darkwinter", "entity/northern_survivor"));

    @ModifyArgs(method="<init>",at=@At(value="INVOKE",target="Lnet/minecraft/client/model/player/PlayerModel;<init>(Lnet/minecraft/client/model/geom/ModelPart;Z)V"),require=1)
    private static void darkwinter$humanProportions(Args args) {
        args.set(0, ThirdPersonBodyModel.createRoot(args.get(1)));
    }

    // Keep the player's existing slim/wide skeleton and cape metadata, but make the
    // character's body appearance a stable asset owned by DarkWinter.
    @Inject(method="extractRenderState",at=@At("TAIL"),require=1)
    private void darkwinter$fixedAppearance(net.minecraft.world.entity.Avatar entity,AvatarRenderState state,
                                            float partialTick,CallbackInfo ci) {
        PlayerSkin skin=state.skin;
        state.skin=new PlayerSkin(DARKWINTER_BODY,skin.cape(),skin.elytra(),skin.model(),skin.secure());
    }
}
