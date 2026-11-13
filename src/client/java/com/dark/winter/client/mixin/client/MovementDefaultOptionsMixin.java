// Supplies movement defaults before Options loads the player's saved key bindings.
package com.dark.winter.client.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(Options.class)
public abstract class MovementDefaultOptionsMixin {
    @ModifyArgs(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/ToggleKeyMapping;<init>(Ljava/lang/String;ILnet/minecraft/client/KeyMapping$Category;Ljava/util/function/BooleanSupplier;Z)V"),
            require = 2, allow = 2)
    private void darkwinter$movementDefaults(Args args) {
        String name = args.get(0);
        if ("key.sprint".equals(name)) args.set(1, InputConstants.KEY_LSHIFT);
        else if ("key.sneak".equals(name)) args.set(1, InputConstants.KEY_LCONTROL);
    }
}
