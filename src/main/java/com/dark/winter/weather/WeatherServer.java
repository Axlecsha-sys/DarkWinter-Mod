// Weather belongs to the save, not a player, dimension instance or wall-clock timer.
package com.dark.winter.weather;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.dark.winter.time.CalendarEpoch;
import com.dark.winter.time.CalendarStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Weather belongs to the save, not a player, dimension instance or wall-clock timer. */
public final class WeatherServer {
    private static final Map<MinecraftServer, WeatherTimeline> STATES = new WeakHashMap<>();
    private static final Map<MinecraftServer, ClimateTimeline> CLIMATES = new WeakHashMap<>();
    private static final Map<MinecraftServer, ClimateGeography> GEOGRAPHIES = new WeakHashMap<>();
    private static final Map<MinecraftServer, CalendarEpoch> CALENDARS = new WeakHashMap<>();
    private static final int DEFAULT_TRANSITION_SECONDS = 30;
    private WeatherServer() { }

    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(WeatherPayload.TYPE, WeatherPayload.CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Path path = path(server);
            try {
                WeatherTimeline state = Files.exists(path) ? WeatherStore.load(path, tick(server))
                        : WeatherTimeline.initial(tick(server), nextChange(server));
                STATES.put(server, state);
                ClimateGeography geo = ClimateGeography.load(FabricLoader.getInstance().getConfigDir().resolve("darkwinter/visuals.properties"));
                GEOGRAPHIES.put(server, geo);
                Path calendarPath = server.getWorldPath(LevelResource.ROOT).resolve("data/darkwinter-calendar.properties");
                CALENDARS.put(server, Files.exists(calendarPath) ? CalendarStore.load(calendarPath) : CalendarEpoch.legacy());
                Path climatePath = climatePath(server);
                ClimateTimeline climate;
                if (Files.exists(climatePath)) {
                    ClimateTimeline saved = ClimateStore.load(climatePath, tick(server));
                    climate = new ClimateTimeline(saved.from(), saved.target(), saved.startedAt(), saved.durationTicks(),
                            saved.automatic(), saved.nextChangeAt(), geo.northRotation());
                } else {
                    WindDirection direction = chooseDirection(server);
                    ClimateValues value = climateTarget(server, state.target(), direction,
                            ClimateModel.speed(state.target(), server.overworld().getRandom().nextDouble()));
                    climate = new ClimateTimeline(value, value, tick(server), 0, true, nextWindChange(server), geo.northRotation());
                }
                CLIMATES.put(server, climate);
                neutralizeVanillaWeather(server);
                // prepareWeather() may have restored the previous save's rain before this event.
                server.overworld().setRainLevel(0);
                server.overworld().setThunderLevel(0);
                if (!Files.exists(path)) WeatherStore.save(path, state, tick(server));
                if (!Files.exists(climatePath)) ClimateStore.save(climatePath, climate, tick(server));
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot load DarkWinter weather; metadata was not reset", ex);
            }
        });
        ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> save(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(WeatherServer::save);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            STATES.remove(server); CLIMATES.remove(server); GEOGRAPHIES.remove(server); CALENDARS.remove(server);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(server, handler.player));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) ->
                send(destination.getServer(), player));
        ServerTickEvents.START_SERVER_TICK.register(WeatherServer::neutralizeVanillaWeather);
        ServerTickEvents.END_SERVER_TICK.register(WeatherServer::tickWeather);
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, selection) -> {
            LiteralArgumentBuilder<CommandSourceStack> setWeather = Commands.literal("weather");
            for (WeatherPreset preset : WeatherPreset.values()) {
                setWeather.then(Commands.literal(preset.id())
                        .executes(c -> set(c.getSource(), preset, DEFAULT_TRANSITION_SECONDS))
                        .then(Commands.argument("transitionSeconds", IntegerArgumentType.integer(2, 300))
                                .executes(c -> set(c.getSource(), preset, IntegerArgumentType.getInteger(c, "transitionSeconds")))));
            }
            LiteralArgumentBuilder<CommandSourceStack> setWind = Commands.literal("wind");
            for (WindDirection direction : WindDirection.values()) {
                setWind.then(Commands.literal(direction.id())
                        .executes(c -> setWind(c.getSource(), direction, -1, DEFAULT_TRANSITION_SECONDS))
                        .then(Commands.argument("metersPerSecond", FloatArgumentType.floatArg(0, 30))
                                .executes(c -> setWind(c.getSource(), direction,
                                        FloatArgumentType.getFloat(c, "metersPerSecond"), DEFAULT_TRANSITION_SECONDS))
                                .then(Commands.argument("transitionSeconds", IntegerArgumentType.integer(2, 300))
                                        .executes(c -> setWind(c.getSource(), direction,
                                                FloatArgumentType.getFloat(c, "metersPerSecond"), IntegerArgumentType.getInteger(c, "transitionSeconds"))))));
            }
            // Same permission predicate as Minecraft 26.3's own WeatherCommand.
            dispatcher.register(Commands.literal("darkwinter")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("set").then(setWeather).then(setWind))
                    .then(Commands.literal("wind")
                            .then(Commands.literal("auto")
                                    .then(Commands.literal("on").executes(c -> windAutomatic(c.getSource(), true)))
                                    .then(Commands.literal("off").executes(c -> windAutomatic(c.getSource(), false)))))
                    .then(Commands.literal("weather")
                            .executes(c -> status(c.getSource()))
                            .then(Commands.literal("status").executes(c -> status(c.getSource())))
                            .then(Commands.literal("auto")
                                    .then(Commands.literal("on").executes(c -> automatic(c.getSource(), true)))
                                    .then(Commands.literal("off").executes(c -> automatic(c.getSource(), false))))));
        });
    }

    private static long tick(MinecraftServer server) { return server.overworld().getGameTime(); }
    private static long nextChange(MinecraftServer server) {
        // 8–20 minutes of active game time, with weather changes paused when the game is paused.
        return tick(server) + 20L * (480 + server.overworld().getRandom().nextInt(721));
    }
    private static Path path(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data/darkwinter-weather.properties");
    }
    private static Path climatePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data/darkwinter-climate.properties");
    }
    private static long nextWindChange(MinecraftServer server) {
        return tick(server) + 20L * (240 + server.overworld().getRandom().nextInt(361));
    }

    /** Server movement physics uses exactly the same air as all client effects. */
    public static ClimateSample climate(MinecraftServer server) {
        if(com.dark.winter.testing.TestingServer.isTesting(server)) return com.dark.winter.testing.TestingWeather.climate(server);
        ClimateTimeline state = CLIMATES.get(server);
        return state == null ? null : state.sample(tick(server));
    }
    public static ClimateGeography geography(MinecraftServer server) { return GEOGRAPHIES.get(server); }

    private static void neutralizeVanillaWeather(MinecraftServer server) {
        if (!STATES.containsKey(server)) return;
        // 26.3 stores vanilla weather on MinecraftServer. Keep its rain/thunder neutral so
        // custom clear skies do not inherit rain fog, lightmap darkening or lightning.
        // Nether and End have no vanilla weather; no gamerule or dimension settings change.
        // Forest-floor snow is generated once with new terrain; loaded chunks are never retrofitted.
        var vanilla = server.getWeatherData();
        if (vanilla.isRaining() || vanilla.isThundering() || vanilla.getClearWeatherTime() <= 100)
            server.setWeatherParameters(6000, 0, false, false);
    }

    private static void tickWeather(MinecraftServer server) {
        if(com.dark.winter.testing.TestingServer.isTesting(server)) return;
        WeatherTimeline state = STATES.get(server);
        if (state == null) return;
        long now = tick(server);
        if (state.automatic() && now >= state.nextChangeAt()) {
            WeatherPreset next = nextPreset(server, state.target());
            int duration = 20 * (45 + server.overworld().getRandom().nextInt(46));
            STATES.put(server, state.retarget(next, now, duration, true, nextChange(server)));
            retargetClimateForWeather(server, next, duration);
            broadcast(server);
        } else if (now % 100 == 0) {
            // Also covers respawn and clients whose level was not ready at JOIN.
            broadcast(server);
        }
        tickClimate(server);
    }

    private static WeatherPreset nextPreset(MinecraftServer server, WeatherPreset current) {
        WeatherPreset[] choices = switch (current) {
            case CLEAR -> new WeatherPreset[]{WeatherPreset.PARTLY_CLOUDY, WeatherPreset.WINDY};
            case PARTLY_CLOUDY -> new WeatherPreset[]{WeatherPreset.CLEAR, WeatherPreset.OVERCAST, WeatherPreset.LIGHT_SNOW};
            case WINDY -> new WeatherPreset[]{WeatherPreset.PARTLY_CLOUDY, WeatherPreset.GROUND_SNOW, WeatherPreset.LIGHT_SNOW};
            case OVERCAST -> new WeatherPreset[]{WeatherPreset.PARTLY_CLOUDY, WeatherPreset.LIGHT_SNOW, WeatherPreset.SNOW};
            case LIGHT_SNOW -> new WeatherPreset[]{WeatherPreset.PARTLY_CLOUDY, WeatherPreset.OVERCAST, WeatherPreset.SNOW};
            case SNOW -> new WeatherPreset[]{WeatherPreset.LIGHT_SNOW, WeatherPreset.OVERCAST, WeatherPreset.BLIZZARD};
            case BLIZZARD -> new WeatherPreset[]{WeatherPreset.SNOW, WeatherPreset.SEVERE_BLIZZARD, WeatherPreset.GROUND_SNOW};
            case SEVERE_BLIZZARD -> new WeatherPreset[]{WeatherPreset.BLIZZARD, WeatherPreset.BLIZZARD, WeatherPreset.GROUND_SNOW};
            case GROUND_SNOW -> new WeatherPreset[]{WeatherPreset.WINDY, WeatherPreset.BLIZZARD, WeatherPreset.PARTLY_CLOUDY};
        };
        return choices[server.overworld().getRandom().nextInt(choices.length)];
    }

    private static int set(CommandSourceStack source, WeatherPreset preset, int seconds) {
        MinecraftServer server = source.getServer();
        WeatherTimeline state = STATES.get(server);
        if (state == null) return unavailable(source);
        STATES.put(server, state.retarget(preset, tick(server), seconds * 20, false, nextChange(server)));
        retargetClimateForWeather(server, preset, seconds * 20);
        save(server);
        broadcast(server);
        source.sendSuccess(() -> Component.translatable("commands.darkwinter.weather.set",
                Component.translatable(preset.translationKey()), seconds), true);
        return 1;
    }

    private static int automatic(CommandSourceStack source, boolean enabled) {
        MinecraftServer server = source.getServer();
        WeatherTimeline state = STATES.get(server);
        if (state == null) return unavailable(source);
        STATES.put(server, state.automatic(enabled, nextChange(server)));
        save(server);
        broadcast(server);
        source.sendSuccess(() -> Component.translatable(enabled ? "commands.darkwinter.weather.auto.on" : "commands.darkwinter.weather.auto.off"), true);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        WeatherTimeline state = STATES.get(source.getServer());
        if (state == null) return unavailable(source);
        long remaining = Math.max(0, state.startedAt() + state.durationTicks() - tick(source.getServer()));
        source.sendSuccess(() -> Component.translatable("commands.darkwinter.weather.status",
                Component.translatable(state.target().translationKey()),
                Component.translatable(state.automatic() ? "weather.darkwinter.automatic" : "weather.darkwinter.manual"),
                (remaining + 19) / 20), false);
        return 1;
    }

    private static int unavailable(CommandSourceStack source) {
        source.sendFailure(Component.translatable("commands.darkwinter.weather.unavailable"));
        return 0;
    }
    private static WindDirection chooseDirection(MinecraftServer server) {
        ClimateGeography geo = GEOGRAPHIES.get(server);
        return ClimateModel.chooseDirection(server.overworld().getRandom().nextDouble(), geo.latitude(), geo.longitude());
    }
    private static ClimateValues climateTarget(MinecraftServer server, WeatherPreset weather, WindDirection direction, float speed) {
        ClimateGeography geo = GEOGRAPHIES.get(server);
        CalendarEpoch epoch = CALENDARS.get(server);
        double clock = server.overworld().getDefaultClockTime();
        double dayOfYear, hour;
        if (epoch != null && epoch.modern()) {
            CalendarEpoch.Stamp stamp = epoch.at(clock);
            dayOfYear = stamp.date().getDayOfYear(); hour = stamp.hour() + stamp.minute() / 60.0;
        } else {
            double scaled = clock / geo.visualDayLengthMultiplier();
            double day = Math.floor(scaled / 24000);
            hour = ((scaled - day * 24000) / 1000 + 6) % 24;
            dayOfYear = ((geo.calendarStartDay() - 1 + day * geo.calendarDaysPerMinecraftDay()) % 365 + 365) % 365 + 1;
        }
        return new ClimateValues(speed, direction.bearingDegrees(), ClimateModel.airTemperature(geo.latitude(),
                geo.longitude(), dayOfYear, hour, weather, direction));
    }
    private static void retargetClimateForWeather(MinecraftServer server, WeatherPreset weather, int duration) {
        ClimateTimeline climate = CLIMATES.get(server);
        if (climate == null) return;
        WindDirection direction = WindDirection.nearest(climate.target().sourceBearingDegrees());
        float speed = ClimateModel.speed(weather, server.overworld().getRandom().nextDouble());
        CLIMATES.put(server, climate.retarget(climateTarget(server, weather, direction, speed),
                tick(server), duration, climate.automatic(), nextWindChange(server)));
    }
    private static void tickClimate(MinecraftServer server) {
        ClimateTimeline climate = CLIMATES.get(server);
        if (climate == null) return;
        long now = tick(server);
        WeatherPreset weather = STATES.get(server).target();
        if (climate.automatic() && now >= climate.nextChangeAt()) {
            ClimateValues next = climateTarget(server, weather, chooseDirection(server),
                    ClimateModel.speed(weather, server.overworld().getRandom().nextDouble()));
            CLIMATES.put(server, climate.retarget(next, now, 20 * (45 + server.overworld().getRandom().nextInt(46)),
                    true, nextWindChange(server)));
            broadcast(server);
        } else if (now % 200 == 0 && !climate.transitioning(now)) {
            // Ambient temperature follows the same calendar as the sky, including /time, but approaches it smoothly.
            ClimateValues next = climateTarget(server, weather, WindDirection.nearest(climate.target().sourceBearingDegrees()),
                    climate.target().windMetersPerSecond());
            if (Math.abs(next.airTemperatureC() - climate.target().airTemperatureC()) > .02f) {
                CLIMATES.put(server, climate.retarget(next, now, 200, climate.automatic(), climate.nextChangeAt()));
                broadcast(server);
            }
        }
    }
    private static int setWind(CommandSourceStack source, WindDirection direction, float speed, int seconds) {
        MinecraftServer server = source.getServer();
        ClimateTimeline climate = CLIMATES.get(server);
        if (climate == null) return unavailable(source);
        float requested = speed < 0 ? climate.target().windMetersPerSecond() : speed;
        CLIMATES.put(server, climate.retarget(climateTarget(server, STATES.get(server).target(), direction, requested),
                tick(server), seconds * 20, false, nextWindChange(server)));
        save(server); broadcast(server);
        source.sendSuccess(() -> Component.translatable("commands.darkwinter.wind.set", Component.translatable(direction.translationKey()),
                String.format(java.util.Locale.ROOT, "%.1f", requested), seconds), true);
        return 1;
    }
    private static int windAutomatic(CommandSourceStack source, boolean enabled) {
        MinecraftServer server = source.getServer();
        ClimateTimeline climate = CLIMATES.get(server);
        if (climate == null) return unavailable(source);
        CLIMATES.put(server, climate.automatic(enabled, nextWindChange(server)));
        save(server); broadcast(server);
        source.sendSuccess(() -> Component.translatable(enabled ? "commands.darkwinter.wind.auto.on" : "commands.darkwinter.wind.auto.off"), true);
        return 1;
    }
    private static void save(MinecraftServer server) {
        WeatherTimeline state = STATES.get(server);
        if (state == null) return;
        try {
            WeatherStore.save(path(server), state, tick(server));
            ClimateTimeline climate = CLIMATES.get(server);
            if (climate != null) ClimateStore.save(climatePath(server), climate, tick(server));
        }
        catch (IOException ex) { throw new IllegalStateException("Cannot save DarkWinter weather", ex); }
    }
    private static void broadcast(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) send(server, player);
    }
    private static void send(MinecraftServer server, ServerPlayer player) {
        WeatherTimeline state = STATES.get(server);
        if (state != null && ServerPlayNetworking.canSend(player, WeatherPayload.TYPE))
            ServerPlayNetworking.send(player, new WeatherPayload(state, CLIMATES.get(server), GEOGRAPHIES.get(server)));
    }
}
