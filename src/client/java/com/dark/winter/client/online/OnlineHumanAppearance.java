// A per-extraction value; renderer snapshots are reused, so ordinary entities explicitly clear it.
package com.dark.winter.client.online;

import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;

/** A per-extraction value; renderer snapshots are reused, so ordinary entities explicitly clear it. */
public final class OnlineHumanAppearance {
    public static final RenderStateDataKey<Boolean> FEMALE=RenderStateDataKey.create(()->"darkwinter:online_female_human");
    private OnlineHumanAppearance(){}
    public static void extract(Avatar entity,AvatarRenderState state){
        var choice=OnlineAvatarSkins.avatar(entity.getUUID());
        ((FabricRenderState)state).setData(FEMALE,choice!=null&&choice.model().equals("female"));
    }
    public static boolean female(AvatarRenderState state){return Boolean.TRUE.equals(((FabricRenderState)state).getData(FEMALE));}
}
