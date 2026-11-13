package com.dark.winter.client.interaction;

import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.PanelDoorEntity;
import com.dark.winter.client.ShoulderCameraState;
import com.dark.winter.client.TestingDrone;
import com.dark.winter.client.UiPreferences;
import com.dark.winter.interaction.HouseInterior;
import com.dark.winter.interaction.HousePassage;
import com.dark.winter.interaction.PanelDoorInteraction;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Drives ordinary walking input through the doorway once the server has opened it. */
public final class HousePassageClient {
    private static @Nullable LocalPlayer player;
    private static @Nullable Level level;
    private static @Nullable HousePassage passage;
    private static boolean indoors;
    private static boolean approaching;
    private static int started, lastProgress, nextSample;
    private static Vec3 sampledAt = Vec3.ZERO;
    private static double bestDistance;
    private static CameraType previousCamera;

    private HousePassageClient() {}

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(HousePassage.TYPE, (packet, context) ->
                context.client().execute(() -> begin(context.client(), packet)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private static void begin(Minecraft client, HousePassage accepted) {
        var local = client.player;
        if (local == null || !local.isAlive() || local.isPassenger() || local.getAbilities().flying
                || !animationEnabled(local) || passage != null || client.gui.screen() != null || TestingDrone.active()) return;
        bind(local);
        passage = accepted;
        started = lastProgress = local.tickCount;
        bestDistance = Double.POSITIVE_INFINITY;
        approaching = true;
        previousCamera = client.options.getCameraType();
        if (previousCamera == CameraType.THIRD_PERSON_BACK) ShoulderCameraState.beginManualToFirst();
        else forceFirstPerson(client);
    }

    public static boolean indoors() { return indoors && currentPlayer(); }
    public static boolean active() { return passage != null && currentPlayer(); }

    private static boolean currentPlayer() {
        var client = Minecraft.getInstance();
        return player == client.player && level == client.level;
    }
    public static boolean animationEnabled(Player player) {
        return !player.isCreative() || UiPreferences.creativeHouseAnimation();
    }
    public static boolean restrictMovement() {
        return active() || indoors() && (!player.isCreative() || UiPreferences.creativeHouseMovement());
    }
    public static boolean lockPerspective() {
        return !TestingDrone.active() && (active() || indoors() && animationEnabled(player));
    }

    public static void tick(Minecraft client) {
        var local = client.player;
        if (local == null || !local.isAlive() || local.isSpectator()) {
            if (active()) finish(client, false);
            reset();
            return;
        }
        bind(local);
        if (client.isPaused() || TestingDrone.active()) return;
        if (passage != null) {
            if (!animationEnabled(local) || client.gui.screen() != null || local.isPassenger() || local.getAbilities().flying || local.isInWater()
                    || local.tickCount - started > 65) finish(client, false);
            else if (local.tickCount - started >= 16 || !ShoulderCameraState.isManualToFirst()) forceFirstPerson(client);
            return;
        }
        if (local.tickCount >= nextSample || local.position().distanceToSqr(sampledAt) > .35 * .35) {
            nextSample = local.tickCount + 6;
            sampledAt = local.position();
            if (local.level().hasChunkAt(local.blockPosition())) indoors = HouseInterior.contains(local.level(), sampledAt);
        }
        if (indoors && animationEnabled(local)) forceFirstPerson(client);
    }

    /** Called at native input extraction so gravity, steps, collisions and footfalls follow normal movement. */
    public static Vec2 walkingInput(Minecraft client) {
        var local = client.player;
        if (local == null || !active() || client.gui.screen() != null) return Vec2.ZERO;
        HousePassage crossing = passage;
        var door = PanelDoorInteraction.target(local.level(), crossing.door());
        if (door == null || !door.open()) {
            // The assembly update normally arrives before the passage packet.
            if (local.tickCount - started >= 6) finish(client, door != null
                    && crossing.clearedThreshold(HousePassage.center(local.level(), door), local.position()));
            return Vec2.ZERO;
        }
        Vec3 center = HousePassage.center(local.level(), door);
        Vec3 forward = crossing.direction().getUnitVec3();
        if (Math.abs(local.getY() - center.y) > .85 || local.position().distanceToSqr(center) > 17) {
            finish(client, false);
            return Vec2.ZERO;
        }
        float yaw = crossing.direction().toYRot();
        local.setYRot(Mth.approachDegrees(local.getYRot(), yaw, 18));
        local.setXRot(Mth.approach(local.getXRot(), 0, 7));
        if (!client.options.getCameraType().isFirstPerson()
                || local.level().getBlockEntity(crossing.door()) instanceof PanelDoorEntity leaf && leaf.progress(0) < .98f)
            return Vec2.ZERO;

        Vec3 approach = crossing.approach(center);
        Vec3 position = local.position();
        Vec3 offset = position.subtract(center);
        double ahead = offset.dot(forward);
        if (approaching && (position.subtract(approach).horizontalDistanceSqr() < .12 * .12
                || offset.subtract(forward.scale(ahead)).horizontalDistanceSqr() < .10 * .10)) {
            approaching = false;
            bestDistance = Double.POSITIVE_INFINITY;
        }
        Vec3 goal = approaching ? approach : crossing.destination(center, door.lower().getValue(FurnitureBlock.FACING));
        Vec3 delta = goal.subtract(position);
        double distance = delta.horizontalDistance();
        if (!approaching && distance < .09) {
            finish(client, true);
            return Vec2.ZERO;
        }
        if (distance < bestDistance - .025) {
            bestDistance = distance;
            lastProgress = local.tickCount;
        } else if (local.tickCount - lastProgress > 13) {
            finish(client, !approaching && crossing.clearedThreshold(center, position));
            return Vec2.ZERO;
        }
        double angle = Math.toRadians(local.getYRot());
        double sin = Math.sin(angle), cos = Math.cos(angle);
        double scale = Math.min(1, distance * 5) / Math.max(distance, 1e-5);
        return new Vec2((float)((delta.x * cos + delta.z * sin) * scale),
                (float)((delta.z * cos - delta.x * sin) * scale));
    }

    private static void finish(Minecraft client, boolean crossed) {
        var crossing = passage;
        passage = null;
        if (crossing == null || client.player == null) return;
        sampledAt = client.player.position();
        nextSample = client.player.tickCount + 6;
        indoors = crossed ? !crossing.leaving() : HouseInterior.contains(client.player.level(), sampledAt);
        ShoulderCameraState.finishManualToFirst();
        if (indoors) forceFirstPerson(client);
        else if (crossed) client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        else client.options.setCameraType(previousCamera);
    }

    private static void forceFirstPerson(Minecraft client) {
        ShoulderCameraState.finishManualToFirst();
        if (!client.options.getCameraType().isFirstPerson()) client.options.setCameraType(CameraType.FIRST_PERSON);
    }

    private static void bind(LocalPlayer local) {
        if (player == local && level == local.level()) return;
        reset();
        player = local;
        level = local.level();
    }

    private static void reset() {
        if (passage != null) ShoulderCameraState.finishManualToFirst();
        player = null;
        level = null;
        passage = null;
        indoors = false;
        nextSample = 0;
    }
}
