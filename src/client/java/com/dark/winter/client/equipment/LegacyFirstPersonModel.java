// Dedicated empty/map hand meshes; third-person proportion and animation changes cannot reach this model.
package com.dark.winter.client.equipment;

import com.dark.winter.client.mixin.client.PlayerModelAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;

/** Dedicated empty/map hand meshes; third-person proportion and animation changes cannot reach this model. */
public final class LegacyFirstPersonModel {
    record Hand(PlayerModel model, ModelPart arm) { }
    private static final PlayerModel WIDE = create(false), SLIM = create(true);

    private static PlayerModel create(boolean slim) {
        return new PlayerModel(HumanBodyModel.createRoot(slim), slim);
    }

    static Hand prepare(boolean slim, HumanoidArm side, boolean sleeve) {
        PlayerModel model = slim ? SLIM : WIDE;
        // Match AvatarRenderer.renderHand and the previous neutral-elbow injection exactly.
        // Reset our own bones only; never borrow the renderer's shared third-person model.
        for (ModelPart arm : new ModelPart[]{model.leftArm, model.rightArm}) {
            arm.resetPose();
            arm.visible = true;
            ModelPart forearm = HumanJoints.forearm(arm);
            forearm.resetPose();
            if (forearm.hasChild("left_sleeve")) forearm.getChild("left_sleeve").visible = sleeve;
            if (forearm.hasChild("right_sleeve")) forearm.getChild("right_sleeve").visible = sleeve;
        }
        model.leftSleeve.visible = sleeve;
        model.rightSleeve.visible = sleeve;
        model.leftArm.zRot = -.1f;
        model.rightArm.zRot = .1f;
        return new Hand(model, model.getArm(side));
    }

    public static void submit(PlayerModel rendererModel, HumanoidArm side, boolean sleeve, Player player,
                              PoseStack poses, SubmitNodeCollector collector, int light, Identifier skin) {
        Hand hand = prepare(((PlayerModelAccessor)rendererModel).darkwinter$isSlim(), side, sleeve);
        collector.submitModelPart(hand.arm(), poses, RenderTypes.entityTranslucent(skin),
                light, OverlayTexture.NO_OVERLAY, null);
        ClothingLayer.submitHand(hand.model(), hand.arm(), player, poses, collector, light);
    }

    private LegacyFirstPersonModel() { }
}
