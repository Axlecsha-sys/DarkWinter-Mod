// Applies the configured automatic-run state to native keyboard movement input.

package com.dark.winter.client.mixin.client;

import com.dark.winter.client.SprintStamina;
import com.dark.winter.client.motion.GaitRuntime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class AutoRunInputMixin extends ClientInput {

    @Unique
    private float darkwinter$autoForward = 0.0F;

    @Inject(method = "tick", at = @At("TAIL"))
    private void darkwinter$autoRun(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        darkwinter$updateAutoRun(client);
        this.moveVector = GaitRuntime.filterMovement(client, this.moveVector);
    }

    @Unique
    private void darkwinter$updateAutoRun(Minecraft client) {

        if (client.player == null
                || client.level == null
                || client.gui.screen() != null) {

            darkwinter$autoForward = 0.0F;
            return;
        }

        Input original = this.keyPresses;

        boolean shiftHeld =
                client.options.keySprint.isDown();

        // Ручное движение назад имеет приоритет
        if (original.backward()
                || client.player.isPassenger()
                || client.player.isCrouching()) {

            darkwinter$autoForward = 0.0F;
            return;
        }

        // Если W нажата, управление движением
        // остаётся у стандартного Minecraft.
        if (original.forward()) {
            darkwinter$autoForward =
                    shiftHeld ? 1.0F : 0.0F;
            return;
        }

        float target = shiftHeld ? 1.0F : 0.0F;

        float step = shiftHeld ? 0.10F : 0.16F;

        // Плавный набор или сброс движения
        if (darkwinter$autoForward < target) {
            darkwinter$autoForward = Math.min(
                    target,
                    darkwinter$autoForward + step
            );
        } else {
            darkwinter$autoForward = Math.max(
                    target,
                    darkwinter$autoForward - step
            );
        }

        if (darkwinter$autoForward <= 0.001F) {
            return;
        }

        // Поддерживаем движение вперёд, пока
        // длится разгон или плавная остановка.
        this.keyPresses = new Input(
                true,
                false,
                original.left(),
                original.right(),
                original.jump(),
                original.shift(),
                original.sprint()
                        && SprintStamina.canAutoRun()
        );

        float x = this.moveVector.x;
        float y = darkwinter$autoForward;

        // Ограничиваем диагональное движение,
        // но не превращаем слабый импульс в полный.
        float length = (float) Math.sqrt(x * x + y * y);

        if (length > 1.0F) {
            x /= length;
            y /= length;
        }

        this.moveVector = new Vec2(x, y);
    }
}
