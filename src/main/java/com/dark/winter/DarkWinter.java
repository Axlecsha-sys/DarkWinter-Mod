// Registers common content, networking, world systems, and server lifecycle hooks.
package com.dark.winter;

import com.dark.winter.network.UpdateWallShapePayload;
import com.dark.winter.registry.ModBlockEntities;
import com.dark.winter.registry.ModBlocks;
import com.dark.winter.registry.ModDataComponents;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DarkWinter implements ModInitializer {
	public static final String MOD_ID = "darkwinter";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		com.dark.winter.testing.TestingIslandGenerator.init();
		com.dark.winter.testing.TestingServer.init();
		com.dark.winter.online.server.OnlineNetworking.init();
		com.dark.winter.testing.TestingEntities.init();
		ModDataComponents.initialize();
		ModBlocks.initialize();
		com.dark.winter.worldgen.TestSpruceBlocks.initialize();
		com.dark.winter.worldgen.TestSpruceFeature.init();
		com.dark.winter.worldgen.TestBiomeCompatibility.init();
		com.dark.winter.registry.ModFurniture.initialize();
		com.dark.winter.registry.ModItems.initialize();
		com.dark.winter.registry.ModCreativeTabs.initialize();
		ModBlockEntities.initialize();
		UpdateWallShapePayload.registerServer();
		com.dark.winter.time.CalendarServer.init();
		com.dark.winter.weather.WeatherParticles.init();
		com.dark.winter.weather.WeatherServer.init();
		com.dark.winter.weather.SpruceGroundSnow.init();
		com.dark.winter.loading.WorldPreloadServer.init();
	}
}
