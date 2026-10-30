// Makes bounded laboratory key state visible to native input and movement rules.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.lab.LabClient;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Virtual key state is visible to vanilla and DarkWinter's existing movement rules. */
@Mixin(KeyMapping.class)
public abstract class LabKeyMappingMixin {
    @Shadow public abstract String getName();
    @Inject(method = "isDown()Z", at = @At("HEAD"), cancellable = true, require = 1)
    private void darkwinter$labDown(CallbackInfoReturnable<Boolean> result) {
        Boolean value = LabClient.down(getName());
        if (value != null) result.setReturnValue(value);
    }
    @Inject(method = "consumeClick()Z", at = @At("HEAD"), cancellable = true, require = 1)
    private void darkwinter$labClick(CallbackInfoReturnable<Boolean> result) {
        Boolean value = LabClient.click(getName());
        if (value != null) result.setReturnValue(value);
    }
}
