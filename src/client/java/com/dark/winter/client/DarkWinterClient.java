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
        com.dark.winter.client.placement.AnchorsClient.init();
        com.dark.winter.client.placement.PlacedItemsClient.init();
        com.dark.winter.client.core.CoreCommands.initialize();
        com.dark.winter.client.vector.SvgHud.initialize();
        com.dark.winter.client.text.SmoothUiText.initialize();
        com.dark.winter.client.inventory.InventoryPortraitLighting.initialize();
        com.dark.winter.client.interaction.DeadwoodClient.initialize();
        com.dark.winter.client.voice.ProtagonistVoiceClient.initialize();
        com.dark.winter.client.voice.BreathingClient.initialize();
        com.dark.winter.client.diagnostics.performance.PerformanceClient.initialize();
        com.dark.winter.client.fixes.voxel.VoxelFaceRuntime.initialize();
        com.dark.winter.client.fixes.mesh.SolidVertexDeduplicator.initialize();
        com.dark.winter.client.fixes.mesh.ChunkMeshRuntime.initialize();
        // Compose game atmosphere adaptation before WKE clones the complete terrain pipeline state.
        com.winterkore.engine.render.WkeRendererRuntime.setTerrainPreprocessor(
                com.dark.winter.visuals.vanillafog.VanillaFogPipelines::terrain);
        com.winterkore.engine.render.WkeRendererRuntime.initialize();
        TestingClient.init();
        com.dark.winter.client.online.OnlineClientRuntime.init();
        com.dark.winter.testing.TestingServer.setClientModules((level,module)->TestingAccess.enabled(module));
        com.dark.winter.client.lab.LabClient.initialize();

        ThinTreeModels.init();
        SnowBiomePresentation.init();
        TestSpruceModels.init();
        TestGroundSnowModels.init();
		TestTerrainBlockColors.init();
        TestSnowTerrainModels.init();
        TestSnowSlabModels.init();
        TestRootSoilModels.init();
        com.dark.winter.client.highlight.ModelHighlight.init();
        FurnitureMeshModels.init();
        FurniturePlacementPreviewRenderer.initialize();
        FurniturePlacementControls.initialize(BUILDING_CATEGORY);
        ProvisionItemModels.init();
        com.dark.winter.client.inventory.ProvisionGuiPreview.initialize();
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
        com.winterkore.engine.surface.client.SnowSurfaceClient.init();
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
            com.dark.winter.client.interaction.HousePassageClient.tick(client);
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
        // Key defaults are installed before options.txt loads; world entry must preserve saved bindings.
        if (client.options.sprintWindow().get() == 0) return;
        client.options.sprintWindow().set(0);
        client.options.save();
    }

}
