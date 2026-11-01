// Retains an explicitly saved Vulkan choice after an unclean startup without changing other choices.
package com.dark.winter.client.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(Minecraft.class)
abstract class VulkanUncleanStartupMixin {
    @ModifyExpressionValue(method="<init>", at=@At(value="FIELD",
            target="Lnet/minecraft/client/PreferredGraphicsApi;DEFAULT:Lnet/minecraft/client/PreferredGraphicsApi;",
            ordinal=0), require=1, allow=1)
    private PreferredGraphicsApi darkwinter$retainSavedVulkan(PreferredGraphicsApi original) {
        // This expression is only reached in the unclean, unforced, previously Vulkan branch.
        return PreferredGraphicsApi.VULKAN;
    }

    @ModifyConstant(method="<init>", constant=@Constant(stringValue=
            "Detected unexpected shutdown during last game startup: resetting preferred graphics API to Default"),
            require=1, allow=1)
    private String darkwinter$describeRetainedVulkan(String original) {
        return "Detected unexpected shutdown during last game startup: retaining selected Vulkan backend";
    }
}
