// Registers client screens, controls, rendering hooks, and client-side gameplay integrations.
package com.dark.winter.client;

import com.dark.winter.registry.ModBlocks;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

public class DarkWinterClient implements ClientModInitializer {
    private static final KeyMapping.Category BUILDING_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(ModBlocks.MOD_ID, "building")
    );
    private static final KeyMapping.Category CAMERA_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(ModBlocks.MOD_ID, "camera")
    );

    private static KeyMapping openRadialMenuKey;
    private static KeyMapping switchShoulderKey;
    private static KeyMapping testingEditorKey;
    private boolean worldWasLoaded = false;

    @Override
    public void onInitializeClient() {
        UiPreferences.initialize();
        TestingClient.init();
        com.dark.winter.client.online.OnlineClientRuntime.init();
        com.dark.winter.testing.TestingServer.setClientModules((level,module)->TestingAccess.enabled(module));
        com.dark.winter.client.lab.LabClient.initialize();

        ThinTreeModels.init();
        TestSpruceModels.init();
        FurnitureMeshModels.init();
        SpruceNeedleRenderer.init();
        DarkWinterInventoryHooks.init();
        com.dark.winter.client.equipment.BackpackLayer.init();
        com.dark.winter.client.equipment.ClothingItemModels.init();
        com.dark.winter.client.equipment.CharacterVisuals.init();
        com.dark.winter.client.equipment.ThirdPersonAnimations.init();
        CalendarClient.init();
        com.dark.winter.client.weather.WeatherClient.init();
        com.dark.winter.weather.WindPhysics.setClientClimate(player ->
                com.dark.winter.client.weather.WeatherClient.active()
                        ? com.dark.winter.client.weather.WeatherClient.climate(1) : null);
        com.dark.winter.client.weather.WeatherEffects.init();
        com.dark.winter.client.loading.WorldPreloadClient.init();

        HudElementRegistry.removeElement(VanillaHudElements.HEALTH_BAR);
        HudElementRegistry.removeElement(VanillaHudElements.EXPERIENCE_LEVEL);

        openRadialMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.darkwinter.wall_menu", InputConstants.Type.KEYBOARD, InputConstants.KEY_U,
                BUILDING_CATEGORY
        ));
        switchShoulderKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.darkwinter.switch_shoulder", InputConstants.Type.KEYBOARD, InputConstants.KEY_V,
                CAMERA_CATEGORY
        ));

        testingEditorKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.darkwinter.testing_editor", InputConstants.Type.KEYBOARD, InputConstants.KEY_F8,
                BUILDING_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            TestingWorlds.tick(client);
            com.dark.winter.client.online.OnlineClientRuntime.tick(client);
            TestingClient.tick(client);
            boolean restoredCamera = TestingTransientState.consumeRestoredCamera(client);
            boolean worldLoaded = client.level != null && client.player != null;
            if (!worldLoaded) {
                worldWasLoaded = false;
                SprintStamina.reset();
                return;
            }

            if (com.dark.winter.client.loading.WorldPreloadClient.active()) return;
            SprintStamina.tick(client);

            if (!worldWasLoaded) {
                darkwinter$setupControls(client);

                if (!restoredCamera) {
                    if (!TestingWorlds.isTesting() || TestingAccess.enabled("camera"))
                        client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    else client.options.setCameraType(CameraType.FIRST_PERSON);
                }
                worldWasLoaded = true;
            }

            if (TestingAccess.enabled("camera") && client.options.getCameraType() == CameraType.THIRD_PERSON_FRONT) {
                ShoulderCameraState.beginManualToFirst();

                // Возвращаем третье лицо на время анимации
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }

            while (testingEditorKey.consumeClick()) {
                if (TestingWorlds.isTesting() && client.gui.screen() == null)
                    client.gui.setScreen(new TestingEditorScreen(null));
            }
            while (switchShoulderKey.consumeClick()) {
                ShoulderCameraState.toggleShoulder();
            }
            while (openRadialMenuKey.consumeClick()) {
                if (client.gui.screen() == null &&
                        ModBlocks.isBuildingSegment(client.player.getMainHandItem())) {
                    client.gui.setScreen(new WallBuilderScreen());
                }
            }
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ModBlocks.MOD_ID, "testing_lab"),
                (graphics, tickCounter) -> TestingClient.render(graphics));

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(
                        ModBlocks.MOD_ID,
                        "survival_needs"
                ),
                (graphics, tickCounter) ->
                        SurvivalNeedsHud.render(graphics)
        );

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(
                        ModBlocks.MOD_ID,
                        "date_time"
                ),
                (graphics, tickCounter) ->
                        DateTimeHud.render(graphics)
        );

    }


    public static net.minecraft.network.chat.Component buildMenuKeyLabel() {
        return openRadialMenuKey.getTranslatedKeyMessage();
    }

    public static boolean isTestingEditorKey(net.minecraft.client.input.KeyEvent event) {
        return testingEditorKey != null && testingEditorKey.matches(event);
    }

    public static boolean isBuildMenuKey(net.minecraft.client.input.KeyEvent event) {
        return openRadialMenuKey != null && openRadialMenuKey.matches(event);
    }

    private void darkwinter$setupControls(
            net.minecraft.client.Minecraft client
    ) {
        // Бег — Left Shift
        client.options.keySprint.setKey(
                InputConstants.Type.KEYBOARD.getOrCreate(
                        InputConstants.KEY_LSHIFT
                )
        );

        // Приседание — Left Ctrl
        client.options.keyShift.setKey(
                InputConstants.Type.KEYBOARD.getOrCreate(
                        InputConstants.KEY_LCONTROL
                )
        );

        // Отключаем спринт двойным W
        client.options.sprintWindow().set(0);

        // Обновляем привязки клавиш
        KeyMapping.resetMapping();

        // Сохраняем управление
        client.options.save();
    }


}
