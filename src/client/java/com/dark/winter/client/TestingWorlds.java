// Stores and recognizes explicit per-world Testing-mode markers.
package com.dark.winter.client;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/**
 * Test-mode identity is stored IN THE WORLD, not in global client settings.
 * Only an explicit new-world creation request can write the marker.
 * Existing/legacy saves without this marker are never converted on load.
 */
public final class TestingWorlds {
    public enum Template { NORMAL, FLAT, ISLAND, TEST_MAP }
    private static Template pending;
    private static Path confirmedFolder;
    private static MinecraftServer observedServer;
    private static volatile boolean testing;
    private static volatile Template activeTemplate;
    private static String onlineContext="";
    private TestingWorlds() {}

    public static void arm(Template chosen) { pending = java.util.Objects.requireNonNull(chosen); confirmedFolder = null; }
    public static void cancel() { pending = null; confirmedFolder = null; }
    /** Keep terrain seed consistent with the vanilla seed field at the actual Create action. */
    public static void prepareCreation(net.minecraft.client.gui.screens.worldselection.CreateWorldScreen screen) {
        if (pending == null) return;
        var state = screen.getUiState();
        if (state.getSettings().selectedDimensions().overworld() instanceof com.dark.winter.testing.TestingIslandGenerator island) {
            long seed = state.getSettings().options().seed();
            state.updateDimensions((access,dimensions) -> dimensions.replaceOverworldGenerator(access,island.reseeded(seed)));
        }
    }
    /** Called only by the actual new-world creation method, before the directory exists. */
    public static void confirmCreation(String folder) {
        if (pending == null) return;
        Path base = Minecraft.getInstance().getLevelSource().getBaseDir().toAbsolutePath().normalize();
        Path requested = base.resolve(folder).normalize();
        if (!requested.getParent().equals(base) || Files.exists(requested)) {
            cancel();
            return;
        }
        confirmedFolder = requested;
    }
    public static boolean isTesting() { return com.dark.winter.client.online.OnlineTestingView.active() || testing; }
    public static Template template() { return com.dark.winter.client.online.OnlineTestingView.active()?com.dark.winter.client.online.OnlineTestingView.template():activeTemplate; }
    /** Re-read an existing marker for a deliberate dev fixture; never arms conversion. */
    public static void refresh(Minecraft client) { cancel(); observedServer = null; tick(client); }

    public static void tick(Minecraft client) {
        com.dark.winter.client.online.OnlineTestingView.tick();
        if(com.dark.winter.client.online.OnlineTestingView.active()){
            String key=com.dark.winter.client.online.OnlineTestingView.worldId()+"|"+com.dark.winter.client.online.OnlineTestingView.contextKey();
            if(!onlineContext.equals(key)){onlineContext=key;TestingEditorScreen.clearWorldHistory();TestingPrefabs.clearSelection();}
            cancel();return;
        }
        if(!onlineContext.isEmpty()){onlineContext="";TestingEditorScreen.clearWorldHistory();TestingPrefabs.clearSelection();}
        MinecraftServer server = client.getSingleplayerServer();
        if (client.level == null || server == null) {
            if (client.gui.screen() instanceof SelectWorldScreen || client.gui.screen() instanceof TitleScreen)
                cancel();
            if (server == null) {
                observedServer = null;
                testing = false;
                activeTemplate = null;
            }
            return;
        }
        if (observedServer == server) return;
        observedServer = server;
        TestingEditorScreen.clearWorldHistory();
        TestingPrefabs.clearSelection();
        Path file = marker(server);
        testing = false;
        activeTemplate = null;
        if (Files.exists(file)) {
            try {
                Properties props = new Properties();
                try (var in = Files.newInputStream(file)) { props.load(in); }
                if (("1".equals(props.getProperty("schema")) || "2".equals(props.getProperty("schema"))) && "testing".equals(props.getProperty("mode"))) {
                    activeTemplate = Template.valueOf(props.getProperty("template", "NORMAL"));
                    testing = true;
                    server.execute(() -> com.dark.winter.testing.TestingServer.attach(server));
                }
            } catch (IOException | IllegalArgumentException error) {
                LoggerFactory.getLogger("DarkWinter/Testing").error("Invalid test-world marker: {}", file, error);
            }
            cancel();
            return;
        }
        // A world can only become a test world during the explicit creation flow.
        // A mature save must never be silently relabelled by a leftover UI request.
        Template requested = pending;
        Path requestedFolder = confirmedFolder;
        cancel();
        if (requested == null || requestedFolder == null
                || !server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(requestedFolder)
                || server.overworld().getGameTime() > 2000) return;
        try {
            Files.createDirectories(file.getParent());
            Properties props = new Properties();
            props.setProperty("schema", "2");
            props.setProperty("worldId", java.util.UUID.randomUUID().toString());
            props.setProperty("mode", "testing");
            props.setProperty("template", requested.name());
            Path temp = Files.createTempFile(file.getParent(), "darkwinter-testing-", ".tmp");
            try {
                try (OutputStream out = Files.newOutputStream(temp)) {
                    props.store(out, "DarkWinter test world. Do not remove this marker.");
                }
                try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(temp); }
            testing = true;
            activeTemplate = requested;
            server.execute(() -> {
                com.dark.winter.testing.TestingServer.attach(server);
                if (requested == Template.TEST_MAP) com.dark.winter.testing.TestingSampleMap.populate(server);
            });
            LoggerFactory.getLogger("DarkWinter/Testing").info("Created test-world marker: {}", file);
        } catch (IOException error) {
            LoggerFactory.getLogger("DarkWinter/Testing").error("Cannot persist test-world marker: {}", file, error);
        }
    }
    private static Path marker(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data/darkwinter-testing.properties");
    }
}
