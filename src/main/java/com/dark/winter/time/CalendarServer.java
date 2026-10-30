// Maintains per-save calendar epochs and synchronizes them with connected clients.
package com.dark.winter.time;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CalendarServer {
    private static final Logger LOG=LoggerFactory.getLogger("DarkWinter/Calendar");
    private static final Map<MinecraftServer,CalendarEpoch> EPOCHS=new WeakHashMap<>();
    private CalendarServer() { }
    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(CalendarPayload.TYPE,CalendarPayload.CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Path file=server.getWorldPath(LevelResource.ROOT).resolve("data/darkwinter-calendar.properties");
            try {
                // This callback precedes the first tick. Old worlds keep their previous visual calendar.
                boolean fresh=server.overworld().getGameTime()==0;
                CalendarEpoch epoch=CalendarStore.loadOrCreate(file,fresh,
                        server.overworld().getDefaultClockTime(),newWorldMultiplier());
                EPOCHS.put(server,epoch);
                LOG.info("Calendar: mode={}, origin={}, file={}",epoch.modern()?"08.10.1997":"legacy",epoch.originTicks(),file);
            } catch(IOException ex) {
                // Fail visibly rather than silently resetting a world's calendar.
                throw new IllegalStateException("Cannot read/save DarkWinter calendar metadata",ex);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server) -> {
            CalendarEpoch epoch=EPOCHS.get(server);
            if (epoch!=null && ServerPlayNetworking.canSend(handler.player,CalendarPayload.TYPE))
                ServerPlayNetworking.send(handler.player,new CalendarPayload(epoch));
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(EPOCHS::remove);
    }
    private static double newWorldMultiplier() {
        Path config=FabricLoader.getInstance().getConfigDir().resolve("darkwinter/visuals.properties");
        if (!Files.exists(config)) return 4;
        try(Reader r=Files.newBufferedReader(config)) {
            Properties p=new Properties();p.load(r);
            double v=Double.parseDouble(p.getProperty("visualDayLengthMultiplier","4.0"));
            if (Double.isFinite(v) && v>=1 && v<=32) return v;
        } catch(IOException|IllegalArgumentException ex) { LOG.warn("Calendar uses default day length: {}",ex.toString()); }
        return 4;
    }
}
