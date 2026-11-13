// Selects Vulkan for fresh video settings while retaining saved graphics preferences.
package com.dark.winter.client.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Options.class)
abstract class VulkanDefaultOptionsMixin {
    @ModifyExpressionValue(method="<init>",at=@At(value="FIELD",
            target="Lnet/minecraft/client/PreferredGraphicsApi;DEFAULT:Lnet/minecraft/client/PreferredGraphicsApi;"),
            require=2,allow=2)
    private PreferredGraphicsApi darkwinter$vulkanDefault(PreferredGraphicsApi original) {
        return PreferredGraphicsApi.VULKAN;
    }
}
