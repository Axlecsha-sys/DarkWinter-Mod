// Retains ungraded world frames for bounded temporal film exposure.
package com.dark.winter.visuals;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

import java.util.Map;

/** Raw, pre-film world images only. No graded image or HUD can feed back into history. */
final class FilmHistory implements AutoCloseable {
    static final Identifier CURRENT_RAW = id("sky_final");
    static final Identifier PREVIOUS_FRAME = id("previous_frame");
    static final Identifier HELD_FRAME = id("held_frame");
    private static final long MAX_GAP_NANOS = 250_000_000L;

    TextureTarget currentRaw, previousRaw, heldRaw;
    boolean valid, allowCapture, captureHeld;
    long rawCaptures, heldCaptures, resets;
    long lastNanos, lastHeldNanos, nextHeldNanos;
    private long heldPeriodNanos;
    private boolean cameraKnown, previousFirstPerson;
    private Object previousCameraType, previousCameraEntity;
    private Vec3 previousPosition;
    private float previousYaw, previousPitch, previousProjectionX, previousProjectionY;

    void prepare(Minecraft client, LevelRenderState state, RenderTarget main,
                 SceneFrame frame, VisualConfig config) {
        ensureTargets(main);
        frame.filmHistoryValid = false;
        frame.filmDeltaSeconds = 0;
        frame.filmHeldAgeSeconds = 0;
        frame.filmFieldPhase = 0;
        boolean temporalEnabled = com.dark.winter.client.TestingAccess.enabled("visuals") && config.atmosphere && config.filmStrength > 0 && config.debug == 0
                && (config.shutterStrength > 0 || config.stepPrintStrength > 0 || config.interlaceStrength > 0);
        if (!temporalEnabled || client.isPaused() || client.gui.screen() != null) {
            invalidate();
            return;
        }

        long now = System.nanoTime();
        long elapsed = lastNanos == 0 ? 0 : now - lastNanos;
        CameraRenderState camera = state.cameraRenderState;
        if (elapsed < 0 || elapsed > MAX_GAP_NANOS || isCameraCut(client, camera)) {
            invalidate();
            elapsed = 0;
        }
        rememberCamera(client, camera);
        lastNanos = now;
        heldPeriodNanos = Math.max(1L, (long)(1_000_000_000.0 / Math.max(1f, config.stepPrintFps)));
        allowCapture = true;
        captureHeld = !valid || now >= nextHeldNanos;
        frame.filmHistoryValid = valid;
        frame.filmDeltaSeconds = (float)(elapsed * 1e-9);
        frame.filmHeldAgeSeconds = valid ? (float)((now - lastHeldNanos) * 1e-9) : 0;
        // Fifty fields per second, independent of FPS and of the world's tick rate.
        frame.filmFieldPhase = (now / 20_000_000L) & 1L;
    }

    void importTargets(FrameGraphBuilder graph, Map<Identifier, ResourceHandle<RenderTarget>> targets) {
        targets.put(CURRENT_RAW, graph.importExternal("DarkWinter ungraded world", currentRaw));
        targets.put(PREVIOUS_FRAME, graph.importExternal("DarkWinter previous ungraded world", previousRaw));
        targets.put(HELD_FRAME, graph.importExternal("DarkWinter held ungraded world", heldRaw));
    }

    /** Called after the post graph has consumed the old images and completed its render passes. */
    void capture() {
        if (!allowCapture) return;
        previousRaw.copyColorFrom(currentRaw);
        rawCaptures++;
        if (captureHeld) {
            heldRaw.copyColorFrom(currentRaw);
            heldCaptures++;
            lastHeldNanos = lastNanos;
            if (!valid || nextHeldNanos == 0) {
                nextHeldNanos = lastNanos + heldPeriodNanos;
            } else {
                // Keep a fixed cadence instead of accumulating a frame's rounding error each capture.
                long periods = (lastNanos - nextHeldNanos) / heldPeriodNanos + 1;
                nextHeldNanos += periods * heldPeriodNanos;
            }
        }
        valid = true;
    }

    void invalidate() {
        if (valid || allowCapture || cameraKnown || lastNanos != 0) resets++;
        valid = allowCapture = captureHeld = cameraKnown = false;
        lastNanos = lastHeldNanos = nextHeldNanos = 0;
        previousPosition = null;
        previousCameraEntity = previousCameraType = null;
    }

    private void ensureTargets(RenderTarget main) {
        var format = main.getColorTexture().getFormat();
        if (currentRaw != null && currentRaw.width == main.width && currentRaw.height == main.height
                && currentRaw.getColorTexture().getFormat() == format) return;
        close();
        currentRaw = new TextureTarget("DarkWinter ungraded world", main.width, main.height, format, null);
        previousRaw = new TextureTarget("DarkWinter previous world", main.width, main.height, format, null);
        heldRaw = new TextureTarget("DarkWinter held world", main.width, main.height, format, null);
        // Even a disabled history sampler always points to an initialized, valid GPU texture.
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorTexture(previousRaw.getColorTexture(), new Vector4f(0));
        encoder.clearColorTexture(heldRaw.getColorTexture(), new Vector4f(0));
    }

    private boolean isCameraCut(Minecraft client, CameraRenderState camera) {
        if (!cameraKnown) return false;
        if (camera.isFirstPerson != previousFirstPerson || client.options.getCameraType() != previousCameraType
                || client.getCameraEntity() != previousCameraEntity) return true;
        if (camera.pos.distanceToSqr(previousPosition) > 16.0
                || Math.abs(Math.IEEEremainder(camera.yRot - previousYaw, 360.0)) > 55.0
                || Math.abs(camera.xRot - previousPitch) > 55.0) return true;
        // Ordinary FOV easing is retained; an abrupt zoom/projection replacement starts cleanly.
        return changedProjection(camera.projectionMatrix.m00(), previousProjectionX)
                || changedProjection(camera.projectionMatrix.m11(), previousProjectionY);
    }

    private static boolean changedProjection(float current, float previous) {
        return Math.abs(current - previous) > Math.max(.01f, Math.abs(previous) * .08f);
    }

    private void rememberCamera(Minecraft client, CameraRenderState camera) {
        cameraKnown = true;
        previousFirstPerson = camera.isFirstPerson;
        previousCameraType = client.options.getCameraType();
        previousCameraEntity = client.getCameraEntity();
        previousPosition = camera.pos;
        previousYaw = camera.yRot;
        previousPitch = camera.xRot;
        previousProjectionX = camera.projectionMatrix.m00();
        previousProjectionY = camera.projectionMatrix.m11();
    }

    @Override
    public void close() {
        invalidate();
        if (currentRaw != null) { currentRaw.destroyBuffers(); currentRaw = null; }
        if (previousRaw != null) { previousRaw.destroyBuffers(); previousRaw = null; }
        if (heldRaw != null) { heldRaw.destroyBuffers(); heldRaw = null; }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("darkwinter_visuals", path);
    }
}
