// Exposes the native slim-arm model flag for clothing and body rendering.
package com.dark.winter.client.mixin.client;

import net.minecraft.client.model.player.PlayerModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerModel.class)
public interface PlayerModelAccessor {
    @Accessor("slim") boolean darkwinter$isSlim();
}
