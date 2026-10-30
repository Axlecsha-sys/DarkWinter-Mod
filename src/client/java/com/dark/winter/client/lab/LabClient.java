// Provides the opt-in development loopback channel for bounded client inspection and input.
package com.dark.winter.client.lab;

import com.dark.winter.client.motion.GaitRuntime;
import com.dark.winter.client.motion.LookWeight;
import com.dark.winter.client.motion.MotionConfig;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Opt-in development-only loopback channel. No network thread touches game state. */
public final class LabClient {
    private static final Gson JSON = new Gson();
    private static final LabLease LEASE = new LabLease();
    private static final LookWeight YAW = new LookWeight(), PITCH = new LookWeight();
    private static final Set<String> CLICKS = new HashSet<>();
    private static final Map<String, String> MAPPINGS = Map.ofEntries(
            Map.entry("key.forward", "W"), Map.entry("key.back", "S"), Map.entry("key.left", "A"), Map.entry("key.right", "D"),
            Map.entry("key.jump", "SPACE"), Map.entry("key.sprint", "SHIFT"), Map.entry("key.sneak", "CTRL"),
            Map.entry("key.attack", "LEFT"), Map.entry("key.use", "RIGHT"), Map.entry("key.inventory", "TAB"),
            Map.entry("key.swapOffhand", "F"), Map.entry("key.togglePerspective", "F5"));
    private static HttpServer http;
    private static ThreadPoolExecutor executor;
    private static Path descriptor;
    private static final String INSTANCE = UUID.randomUUID().toString();
    private static Object world, screenContext;
    private static String lifecycleId = "", lifecycleOperation = "", lifecycleStatus = "idle";
    private static long lifecycleAt;
    private static long lastFrame, frameSerial;
    private static String captureId = "", captureStatus = "idle", captureName = "";
    private static long captureAfterFrame;
    private static JsonObject captureContext;
    private static int captureScale = 1;
    private LabClient() { }

    public static void initialize() {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return;
        Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        Path config = game.resolve("config/darkwinter/lab.properties");
        Properties settings = new Properties();
        try {
            if (!Files.isRegularFile(config)) return;
            try (var input = Files.newInputStream(config)) { settings.load(input); }
            if (!Boolean.parseBoolean(settings.getProperty("enabled", "false"))) return;
            Path folder = game.resolve(".darkwinter-lab-client");
            if (Files.isSymbolicLink(folder)) throw new IOException("Runtime folder must not be a symlink");
            Files.createDirectories(folder);
            if (!folder.toRealPath().startsWith(game.toRealPath())) throw new IOException("Runtime folder leaves game directory");
            descriptor = folder.resolve("runtime.json");
            byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
            String token = HexFormat.of().formatHex(random);
            http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
            executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "DarkWinter-Lab-HTTP"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
            http.setExecutor(executor);
            http.createContext("/v1", exchange -> handle(exchange, token));
            JsonObject connection = new JsonObject();
            connection.addProperty("version", 1); connection.addProperty("port", http.getAddress().getPort());
            connection.addProperty("token", token); connection.addProperty("pid", ProcessHandle.current().pid());
            connection.addProperty("instance", INSTANCE);
            Path temp = folder.resolve("runtime-" + INSTANCE + ".tmp");
            Files.writeString(temp, JSON.toJson(connection));
            Files.move(temp, descriptor, StandardCopyOption.REPLACE_EXISTING);
            http.start();
            LoggerFactory.getLogger("DarkWinter/Lab").info("Development background lab ready (loopback only; session required)");
        } catch (Exception error) {
            if (http != null) http.stop(0);
            if (executor != null) executor.shutdownNow();
            http = null;
            LoggerFactory.getLogger("DarkWinter/Lab").error("Background lab could not start: {}", error.getClass().getSimpleName());
        }
    }

    private static void handle(HttpExchange exchange, String token) throws IOException {
        try {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()
                    || exchange.getRequestHeaders().containsKey("Origin")
                    || auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),
                    ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) { reply(exchange, 403, Map.of("error", "Forbidden")); return; }
            if (!exchange.getRequestMethod().equals("POST") || !exchange.getRequestURI().toString().equals("/v1")) {
                reply(exchange, 405, Map.of("error", "POST /v1 required")); return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(4097);
            if (body.length > 4096) { reply(exchange, 413, Map.of("error", "Request too large")); return; }
            JsonObject request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            // A timed-out queued request must never execute later.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            CompletableFuture<JsonObject> answer = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> {
                if (answer.isCancelled() || System.nanoTime() - deadline >= 0) return;
                try { answer.complete(command(Minecraft.getInstance(), request)); }
                catch (Exception error) { answer.completeExceptionally(error); }
            });
            try { reply(exchange, 200, answer.get(4, TimeUnit.SECONDS)); }
            catch (java.util.concurrent.TimeoutException error) {
                answer.cancel(false); reply(exchange, 504, Map.of("error", "Client did not process request; inspect state before retrying"));
            } catch (Exception error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                reply(exchange, 409, Map.of("error", cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
            }
        } catch (RuntimeException error) { reply(exchange, 400, Map.of("error", "Invalid lab request")); }
        finally { exchange.close(); }
    }

    private static void reply(HttpExchange exchange, int code, Object value) throws IOException {
        byte[] body = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
    }

    private static String string(JsonObject data, String key) {
        JsonElement value = data.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("String required: " + key);
        return value.getAsString();
    }
    private static double number(JsonObject data, String key, double fallback) {
        if (!data.has(key)) return fallback;
        JsonElement value = data.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble()))
            throw new IllegalArgumentException("Finite number required: " + key);
        return value.getAsDouble();
    }
    private static Set<String> keys(JsonObject request) {
        Set<String> result = new HashSet<>();
        if (!request.has("keys")) return result;
        if (!request.get("keys").isJsonArray() || request.getAsJsonArray("keys").size() > 12)
            throw new IllegalArgumentException("Invalid key list");
        for (JsonElement key : request.getAsJsonArray("keys")) {
            if (!key.isJsonPrimitive() || !key.getAsJsonPrimitive().isString()
                    || !LabUi.knownKey(key.getAsString()) || !result.add(key.getAsString()))
                throw new IllegalArgumentException("Unknown or duplicate key");
        }
        return result;
    }

    private static JsonObject command(Minecraft client, JsonObject request) {
        maintain(client);
        String op = string(request, "op");
        if (op.equals("state")) return state(client);
        if (op.equals("ui")) { JsonObject result=state(client); result.add("ui",LabUi.snapshot(client)); return result; }
        if (op.equals("controls")) { JsonObject result=state(client); result.add("controls",LabUi.controls()); return result; }
        if (op.equals("begin")) {
            String selected=request.has("world")?string(request,"world"):"";
            if (!selected.isEmpty() && !worldName(client).equals(selected)) throw new IllegalStateException("Selected world is not open");
            LEASE.begin(string(request, "session"));
            world = client.level; screenContext=client.gui.screen();
            KeyMapping.releaseAll(); CLICKS.clear(); YAW.reset(); PITCH.reset(); LabUi.resetPointer();
            lastFrame = System.nanoTime();
            return state(client);
        }
        LEASE.require(string(request, "session"));
        switch (op) {
            case "ui_action" -> {
                if (LEASE.running()) throw new IllegalStateException("Stop gameplay input before acting in a screen");
                JsonObject ui=LabUi.act(client,request);JsonObject result=state(client);result.add("ui",ui);return result;
            }
            case "lifecycle" -> {
                String operation=string(request,"operation");
                if (!operation.equals("quit") && !operation.equals("return_to_title")) throw new IllegalArgumentException("Unknown lifecycle operation");
                if (lifecycleStatus.equals("queued") || lifecycleStatus.equals("running")) throw new IllegalStateException("Lifecycle operation is already pending");
                LEASE.stop("lifecycle");CLICKS.clear();
                lifecycleId=string(request,"operation_id");lifecycleOperation=operation;lifecycleStatus="queued";
                lifecycleAt=System.nanoTime()+250_000_000L;
            }
            case "heartbeat" -> LEASE.heartbeat(string(request, "session"));
            case "action" -> {
                if (client.gui.screen() != null || client.isPaused() || client.player == null || !client.player.isAlive())
                    throw new IllegalStateException("World must be running with no open screen");
                Set<String> keys = keys(request);
                double duration = number(request, "duration_ms", 100);
                if (duration != Math.rint(duration)) throw new IllegalArgumentException("Integer duration required");
                LEASE.start(string(request, "session"), string(request, "action_id"), keys, (int)duration,
                        number(request, "yaw_degrees", 0), number(request, "pitch_degrees", 0));
                CLICKS.clear(); CLICKS.addAll(keys); YAW.reset(); PITCH.reset();
            }
            case "stop" -> { LEASE.stop("requested"); CLICKS.clear(); YAW.reset(); PITCH.reset(); }
            case "end" -> end(client, "requested");
            case "capture" -> {
                if (client.getWindow().isIconified()) throw new IllegalStateException("Restore the minimized game window for a fresh render");
                if (captureStatus.equals("pending") || captureStatus.equals("saving")) throw new IllegalStateException("Capture already pending");
                String id = string(request, "capture_id");
                if (!id.matches("[a-f0-9]{32}") || id.equals(captureId)) throw new IllegalArgumentException("New capture ID required");
                double scale = number(request, "scale", 2);
                if (scale != 1 && scale != 2) throw new IllegalArgumentException("Capture scale must be 1 or 2");
                captureId = id; captureName = "lab-" + id + ".png"; captureScale = (int)scale;
                captureStatus = "pending"; captureAfterFrame = frameSerial;
            }
            default -> throw new IllegalArgumentException("Unknown lab operation");
        }
        return state(client);
    }

    private static String worldName(Minecraft client) {
        if (client.getSingleplayerServer() == null) return "";
        return client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
    }
    private static JsonObject state(Minecraft client) {
        JsonObject data = new JsonObject();
        data.addProperty("runtime_version", "0.3.0");
        data.addProperty("instance", INSTANCE); data.addProperty("pid", ProcessHandle.current().pid());
        data.addProperty("world", worldName(client)); data.addProperty("loaded", client.level != null && client.player != null);
        data.addProperty("focused", client.isWindowActive()); data.addProperty("minimized", client.getWindow().isIconified());
        data.addProperty("paused", client.isPaused()); data.addProperty("pause_on_lost_focus", client.options.pauseOnLostFocus);
        data.addProperty("auto_pause_suppressed", LEASE.active());
        data.addProperty("overlay", client.gui.overlay()==null?"":client.gui.overlay().getClass().getSimpleName());
        data.addProperty("busy",LabUi.busy(client));
        data.addProperty("phase", LabUi.busy(client)?"loading":client.level!=null?"world":client.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen?"menu":"screen");
        data.addProperty("lifecycle_id",lifecycleId);data.addProperty("lifecycle_status",lifecycleStatus);
        data.addProperty("screen", client.gui.screen() == null ? "" : client.gui.screen().getClass().getSimpleName());
        data.addProperty("session", LEASE.session()); data.addProperty("action_id", LEASE.actionId());
        data.addProperty("action_status", LEASE.result()); data.addProperty("stop_reason", LEASE.reason());
        data.add("held_keys", JSON.toJsonTree(LEASE.keys()));
        data.addProperty("frame", frameSerial); data.addProperty("capture_id", captureId);
        data.addProperty("capture_status", captureStatus); data.addProperty("capture_file", captureName);
        if(captureContext!=null)data.add("capture_context",captureContext);
        if (client.player != null) {
            data.addProperty("x", client.player.getX()); data.addProperty("y", client.player.getY()); data.addProperty("z", client.player.getZ());
            data.addProperty("yaw", client.player.getYRot()); data.addProperty("pitch", client.player.getXRot());
            data.addProperty("health", client.player.getHealth()); data.addProperty("on_ground", client.player.onGround());
        }
        return data;
    }
    private static void maintain(Minecraft client) {
        boolean wasActive = LEASE.active();
        LEASE.expire();
        if (LEASE.active() && (world!=client.level || screenContext!=client.gui.screen())) {
            LEASE.stop("context_changed"); CLICKS.clear();YAW.reset();PITCH.reset();KeyMapping.releaseAll();LabUi.resetPointer();
            world=client.level;screenContext=client.gui.screen();
        }
        if (wasActive && !LEASE.active()) restore(client);
        if (!LEASE.running()) CLICKS.clear();
        if (LEASE.active() && client.player!=null && !client.player.isAlive()) { LEASE.stop("player_unavailable");CLICKS.clear(); }
    }
    private static void restore(Minecraft client) {
        world = null; screenContext=null; LabUi.resetPointer(); CLICKS.clear(); YAW.reset(); PITCH.reset(); KeyMapping.releaseAll();
        if (captureStatus.equals("pending")) captureStatus = "cancelled";
    }
    private static void end(Minecraft client, String reason) { LEASE.end(reason); restore(client); }

    public static void beforeFrame(Minecraft client) {
        if (http == null) return;
        maintain(client);
        if(lifecycleStatus.equals("queued") && System.nanoTime()-lifecycleAt>=0) {
            lifecycleStatus="running";
            if(lifecycleOperation.equals("quit")) { client.stop();return; }
            if(client.level!=null) client.disconnectWithSavingScreen();
            client.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
            lifecycleStatus="completed";
        }
        long now = System.nanoTime();
        double seconds = lastFrame == 0 ? 0 : (now - lastFrame) / 1_000_000_000.0;
        lastFrame = now;
        if (!LEASE.active() || !LEASE.running() || client.player == null || client.gui.screen() != null || client.isPaused()) return;
        double[] delta = LEASE.turnDelta();
        if (seconds <= 0 || seconds > 0.25) { YAW.reset(); PITCH.reset(); return; }
        MotionConfig.poll();
        var settings = MotionConfig.get();
        boolean weighted = settings.enabled() && settings.camera() && settings.lookControl()
                && client.options.getCameraType() == CameraType.THIRD_PERSON_BACK && GaitRuntime.posture(client.player);
        double yaw = YAW.update(delta[0], seconds, weighted, settings.maxYawSpeed(), settings.lookResponseSeconds());
        double pitch = PITCH.update(delta[1], seconds, weighted, settings.maxPitchSpeed(), settings.lookResponseSeconds());
        client.player.turn(yaw / 0.15, pitch / 0.15);
    }
    public static void afterRender(Minecraft client) {
        if (http == null) return;
        frameSerial++;
        if (!captureStatus.equals("pending") || frameSerial <= captureAfterFrame || !LEASE.active() || client.getWindow().isIconified()) return;
        captureStatus = "saving";
        String id = captureId;
        var target = client.gameRenderer.mainRenderTarget();
        int scale = target.width % captureScale == 0 && target.height % captureScale == 0 ? captureScale : 1;
        captureContext=new JsonObject();
        captureContext.addProperty("frame",frameSerial);captureContext.addProperty("captured_at_utc",java.time.Instant.now().toString());
        captureContext.addProperty("world",worldName(client));captureContext.addProperty("render_width",target.width);captureContext.addProperty("render_height",target.height);
        captureContext.addProperty("scale",scale);captureContext.add("ui",LabUi.snapshot(client));
        if(client.player!=null) {
            captureContext.addProperty("x",client.player.getX());captureContext.addProperty("y",client.player.getY());captureContext.addProperty("z",client.player.getZ());
            captureContext.addProperty("yaw",client.player.getYRot());captureContext.addProperty("pitch",client.player.getXRot());
        }
        try {
            Screenshot.grab(client.gameDirectory, captureName, target, scale, message -> client.execute(() -> {
                if (captureId.equals(id)) captureStatus = Files.isRegularFile(client.gameDirectory.toPath().resolve("screenshots").resolve(captureName)) ? "ready" : "failed";
            }));
        } catch (RuntimeException error) { captureStatus = "failed"; }
    }

    private static String key(String name) {
        if (name.startsWith("key.hotbar.")) return name.substring("key.hotbar.".length());
        return MAPPINGS.getOrDefault(name,name);
    }
    public static Boolean down(String name) {
        if (!LEASE.active()) return null;
        if(Minecraft.getInstance().gui.screen()!=null) return false;
        String mapped=key(name);
        return LEASE.keys().contains(name)||LEASE.keys().contains(mapped)||(mapped.equals("TAB")&&LEASE.keys().contains("E"));
    }
    public static Boolean click(String name) {
        if (!LEASE.active()) return null;
        if(Minecraft.getInstance().gui.screen()!=null) return false;
        String mapped=key(name);
        if(mapped.equals("TAB")&&CLICKS.remove("E"))return true;
        return CLICKS.remove(name)||CLICKS.remove(mapped);
    }
    public static boolean ownsInput() { return LEASE.active(); }
    public static void shutdown(Minecraft client) {
        if (http == null) return;
        end(client, "client_closed"); http.stop(0); http = null;
        executor.shutdownNow();
        try { Files.deleteIfExists(descriptor); } catch (IOException ignored) { }
    }
}

