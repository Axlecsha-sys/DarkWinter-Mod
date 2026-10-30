// Updates client stamina and the bounded sprint-speed modifier.

package com.dark.winter.client;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.client.Minecraft;

public final class SprintStamina {

    private static final float MAX_STAMINA = 150.0F;
    private static final float SPRINT_COST = 8.0F / 20.0F;
    private static final float WALK_RECOVERY = 12.0F / 20.0F;
    private static final float IDLE_RECOVERY = 18.0F / 20.0F;

    private static final float RESTART_THRESHOLD = MAX_STAMINA;

    // Плавный переход ходьба <-> бег
    private static final float ACCELERATION = 0.08F;
    private static final float DECELERATION = 0.12F;

    private static final double SPRINT_BONUS = 0.30D;

    private static float sprintBlend = 0.0F;

    private static final Identifier SPEED_MODIFIER_ID =
            Identifier.fromNamespaceAndPath("darkwinter", "smooth_sprint");

    // Задержка восстановления после нагрузки
    private static final int SPRINT_RECOVERY_DELAY = 20;
    private static final int JUMP_RECOVERY_DELAY = 30;

    // Сколько тиков осталось до восстановления
    private static int recoveryDelayTicks = 0;

    // Прыжки
    private static final float JUMP_COST = 10.0F;
    private static final float SPRINT_JUMP_COST = 18.0F;
    private static final int JUMP_COOLDOWN = 18;

    private static int lastJumpTick = -1000;

    private static float stamina = MAX_STAMINA;
    private static boolean exhausted = false;

    private SprintStamina() {}
    public record State(float stamina,boolean exhausted,int lastJumpTick,float sprintBlend,int recoveryDelayTicks) {}
    public static State captureState() {return new State(stamina,exhausted,lastJumpTick,sprintBlend,recoveryDelayTicks);}
    public static void restoreState(State state) {
        if(state==null||!Float.isFinite(state.stamina())||!Float.isFinite(state.sprintBlend())
                ||state.stamina()<0||state.stamina()>1000||state.sprintBlend()<0||state.sprintBlend()>1)
            throw new IllegalArgumentException("Invalid stamina snapshot");
        stamina=state.stamina();exhausted=state.exhausted();lastJumpTick=state.lastJumpTick();
        sprintBlend=state.sprintBlend();recoveryDelayTicks=state.recoveryDelayTicks();
    }

    public static float getStamina() {
        return stamina;
    }

    public static void reset() {
        stamina = getMaxStamina();
        exhausted = false;
        lastJumpTick = -1000;
        sprintBlend = 0.0F;
        recoveryDelayTicks = 0;
    }


    public static void tick(Minecraft client) {
        if (!TestingAccess.enabled("stamina")) {
            reset();
            if (client.player != null) {
                var speed=client.player.getAttribute(Attributes.MOVEMENT_SPEED);
                if(speed!=null) speed.removeModifier(SPEED_MODIFIER_ID);
            }
            return;
        }
        if (client.player == null || client.level == null) {
            return;
        }

        // Detached editor input belongs to the camera; preserve the parked body's stamina.
        if (TestingDrone.active()) return;

        // Проверяем, удерживается ли Shift
        boolean sprintKeyHeld = client.options.keySprint.isDown();

        // Проверяем, движется ли персонаж
        boolean moving = client.player.getDeltaMovement()
                .horizontalDistanceSqr() > 0.001D;

        // Если выносливость восстановилась, разрешаем бег
        if (exhausted && stamina >= getMaxStamina()) {
            exhausted = false;
        }

        // Можно ли включить спринт?
        boolean canSprint = sprintKeyHeld
                && !client.options.keyDown.isDown()
                && !exhausted
                && stamina > 0.0F
                && !client.player.isPassenger()
                && !client.player.isCrouching();

        // Включаем спринт, даже если стоим на месте
        client.player.setSprinting(canSprint);

        // Выносливость тратится только при движении

        if (canSprint && moving) {

            // Расходуем силы во время бега
            var lab=testingSettings();
            stamina -= lab==null?SPRINT_COST:lab.staminaSprintDrain/20f;

            // Обновляем задержку восстановления
            recoveryDelayTicks = Math.max(
                    recoveryDelayTicks,
                    SPRINT_RECOVERY_DELAY
            );

            if (stamina <= 0.0F) {
                stamina = 0.0F;
                exhausted = true;
                client.player.setSprinting(false);
            }

        } else {

            // Сначала проходит задержка
            if (recoveryDelayTicks > 0) {
                recoveryDelayTicks--;
            } else {

                // Только потом восстанавливаем силы
                var lab=testingSettings();
                stamina += moving ? (lab==null?WALK_RECOVERY:lab.staminaWalkRegen/20f)
                        : (lab==null?IDLE_RECOVERY:lab.staminaIdleRegen/20f);
            }
        }

        // Ограничиваем значение диапазоном от 0 до MAX_STAMINA
        stamina = Math.max(
                0.0F,
                Math.min(getMaxStamina(), stamina)
        );

        // Плавно изменяем реальную скорость, а не только анимацию.
        updateSmoothSprint(client, canSprint && moving && !exhausted);
    }

    public static boolean tryJump(int currentTick, boolean sprinting) {
        if (!TestingAccess.enabled("stamina")) return true;

        if (exhausted) {
            return false;
        }

        // Запрещаем слишком частые прыжки
        if (currentTick - lastJumpTick < JUMP_COOLDOWN) {
            return false;
        }

        var lab=testingSettings();
        float cost = sprinting ? (lab==null?SPRINT_JUMP_COST:lab.staminaSprintJumpCost)
                : (lab==null?JUMP_COST:lab.staminaJumpCost);

        if (stamina < cost) {
            return false;
        }

        stamina -= cost;

        if (stamina <= 0.0F) {
            stamina = 0.0F;
            exhausted = true;
        }

        lastJumpTick = currentTick;

        // После прыжка восстановление начинается позже
        recoveryDelayTicks = Math.max(
                recoveryDelayTicks,
                JUMP_RECOVERY_DELAY
        );

        return true;
    }

    public static boolean canAutoRun() {
        if (!TestingAccess.enabled("stamina")) return true;
        return !exhausted && stamina > 0.0F;
    }

    public static float getMaxStamina() {
        var lab=testingSettings();return lab==null?MAX_STAMINA:lab.staminaCapacity;
    }


    private static com.dark.winter.testing.TestingSettings testingSettings(){
        return TestingAccess.settings();
    }

    /**
     * Ванильный спринт сразу даёт +30% к скорости движения.
     * Собственным временным модификатором компенсируем скачок, чтобы
     * реальная скорость плавно переходила от ходьбы к бегу и обратно.
     * Работает только у локального игрока (client-side прототип).
     */
    private static void updateSmoothSprint(Minecraft client, boolean running) {
        AttributeInstance speed = client.player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }

        // Не вмешиваемся в полёт, верховую езду и плавание.
        if (client.player.getAbilities().flying
                || client.player.isPassenger()
                || client.player.isInWater()) {
            sprintBlend = 0.0F;
            speed.removeModifier(SPEED_MODIFIER_ID);
            return;
        }

        float change = running ? ACCELERATION : DECELERATION;
        float target = running ? 1.0F : 0.0F;

        if (sprintBlend < target) {
            sprintBlend = Math.min(target, sprintBlend + change);
        } else {
            sprintBlend = Math.max(target, sprintBlend - change);
        }

        // Ваниль: множитель x1.30, когда isSprinting() == true.
        // Желаемая скорость: от x1.00 до x1.30 по мере разгона.
        double vanillaFactor = client.player.isSprinting()
                ? 1.0D + SPRINT_BONUS : 1.0D;
        double desiredFactor = 1.0D + SPRINT_BONUS * sprintBlend;
        double correction = desiredFactor / vanillaFactor - 1.0D;

        if (Math.abs(correction) < 0.0001D) {
            speed.removeModifier(SPEED_MODIFIER_ID);
        } else {
            speed.addOrUpdateTransientModifier(new AttributeModifier(
                    SPEED_MODIFIER_ID,
                    correction,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
            ));
        }
    }

}
