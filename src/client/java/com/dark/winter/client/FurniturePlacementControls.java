package com.dark.winter.client;

import com.dark.winter.placement.FreeFurniturePlacement;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/** Local furniture controls, with the displayed angle sent ahead of the normal placement packet. */
public final class FurniturePlacementControls {
    private static final float DEGREES_PER_SECOND = 120;
    private static final float TAP_DEGREES = 3;
    private static final int MARGIN = 8;
    private static final int ROW_HEIGHT = 14;
    private static final float TEXT_SCALE = .84f;

    private static KeyMapping rotate;
    private static KeyMapping snap;
    private static LocalPlayer activePlayer;
    private static Item activeItem;
    private static long lastFrame;
    private static boolean rotatedDuringHold;
    private static boolean snapHeld;

    private FurniturePlacementControls() {}

    static void initialize(KeyMapping.Category category) {
        rotate = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.darkwinter.rotate_furniture",
                InputConstants.Type.KEYBOARD, InputConstants.KEY_R, category));
        snap = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.darkwinter.snap_furniture",
                InputConstants.Type.KEYBOARD, InputConstants.KEY_G, category));
        ClientTickEvents.END_CLIENT_TICK.register(FurniturePlacementControls::tick);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("darkwinter", "furniture_placement"),
                (graphics, ticks) -> render(graphics));
    }

    private static boolean active(Minecraft client) {
        return client.player != null && client.level != null && client.player.isAlive() && !client.player.isSpectator()
                && client.gui.screen() == null && client.gui.overlay() == null && !client.isPaused()
                && client.getCameraEntity() == client.player && FreeFurniturePlacement.applies(client.player.getMainHandItem());
    }

    private static void select(LocalPlayer player) {
        Item item = player.getMainHandItem().getItem();
        if (player == activePlayer && item == activeItem) return;
        activePlayer = player;
        activeItem = item;
        lastFrame = 0;
        rotatedDuringHold = false;
    }

    private static boolean pressed(KeyMapping key) {
        boolean pressed = key.consumeClick();
        while (key.consumeClick()) { }
        return pressed;
    }

    private static void tick(Minecraft client) {
        boolean rotatePressed = pressed(rotate);
        boolean snapPressed = pressed(snap);
        boolean snapWasHeld = snapHeld;
        snapHeld = snap.isDown();
        if (!active(client)) {
            lastFrame = 0;
            rotatedDuringHold = false;
            if (client.player == null) {
                activePlayer = null;
                activeItem = null;
            }
            return;
        }
        LocalPlayer player = client.player;
        select(player);
        if (snapPressed && !snapWasHeld) {
            FreeFurniturePlacement.toggleSnap(player);
            sendSnap(player);
        }
        boolean canRotate = FreeFurniturePlacement.canRotate(player.getMainHandItem());
        if (!rotate.isDown() || !canRotate) {
            // Preserve brief presses that begin and end between render frames.
            if (rotatePressed && !rotatedDuringHold && canRotate) {
                FreeFurniturePlacement.adjustRotation(player, TAP_DEGREES);
            }
            rotatedDuringHold = false;
            lastFrame = 0;
        }
    }

    public static void beforePreview(Minecraft client) {
        if (!active(client) || !rotate.isDown() || !FreeFurniturePlacement.canRotate(client.player.getMainHandItem())) {
            lastFrame = 0;
            return;
        }
        LocalPlayer player = client.player;
        select(player);
        long now = System.nanoTime();
        float delta = lastFrame == 0 ? TAP_DEGREES
                : DEGREES_PER_SECOND * Math.min(.10f, (now - lastFrame) / 1_000_000_000f);
        lastFrame = now;
        if (delta <= 0) return;
        FreeFurniturePlacement.adjustRotation(player, delta);
        rotatedDuringHold = true;
    }

    public static void beforePlacement(LocalPlayer player) {
        if (!active(Minecraft.getInstance())) return;
        // The placement uses the angle already shown, even if the click precedes the next frame.
        float angle = FreeFurniturePlacement.currentRotation(player);
        if (FreeFurniturePlacement.canRotate(player.getMainHandItem())
                && ClientPlayNetworking.canSend(FreeFurniturePlacement.SetRotation.TYPE)) {
            ClientPlayNetworking.send(new FreeFurniturePlacement.SetRotation(angle));
        }
        sendSnap(player);
    }

    private static void sendSnap(LocalPlayer player) {
        if (ClientPlayNetworking.canSend(FreeFurniturePlacement.SetSnap.TYPE)) {
            ClientPlayNetworking.send(new FreeFurniturePlacement.SetSnap(FreeFurniturePlacement.snapEnabled(player)));
        }
    }

    private static void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!active(client) || client.gui.hud.isHidden() || !TestingAccess.enabled("hud")
                || DebugOverlayVisibility.expandedDebugOwnsHud()) return;

        Component placeKey = client.options.keyUse.saveString().equals("key.mouse.right")
                ? Component.translatable("hud.darkwinter.furniture.right_mouse")
                : client.options.keyUse.getTranslatedKeyMessage();
        boolean canRotate = FreeFurniturePlacement.canRotate(client.player.getMainHandItem());
        Component rotateKey = rotate.getTranslatedKeyMessage();
        Component snapKey = snap.getTranslatedKeyMessage();
        boolean snapped = FreeFurniturePlacement.snapEnabled(client.player) || client.options.keyShift.isDown();
        Component title = Component.translatable("hud.darkwinter.furniture.title");
        Component placeText = Component.translatable("hud.darkwinter.furniture.place");
        Component rotateText = Component.translatable("hud.darkwinter.furniture.rotate");
        Component snapText = Component.translatable(snapped ? "hud.darkwinter.furniture.snap_on"
                : "hud.darkwinter.furniture.snap_off");
        int snapWidth = Math.max(HudStyle.width(Component.translatable("hud.darkwinter.furniture.snap_on")),
                HudStyle.width(Component.translatable("hud.darkwinter.furniture.snap_off")));
        int textWidth = Math.max(HudStyle.width(placeText), Math.max(canRotate ? HudStyle.width(rotateText) : 0, snapWidth));
        int keyWidth = Math.max(21, 8 + Math.round(TEXT_SCALE
                * Math.max(HudStyle.width(placeKey), Math.max(canRotate ? HudStyle.width(rotateKey) : 0, HudStyle.width(snapKey)))));
        int width = Math.min(graphics.guiWidth() - 2 * MARGIN, Math.max(106, 23 + keyWidth + Math.round(textWidth * TEXT_SCALE)));
        if (width <= 0) return;
        keyWidth = Math.min(keyWidth, width / 2);
        int height = canRotate ? 65 : 51;
        int x = graphics.guiWidth() - width - MARGIN;
        int y = graphics.guiHeight() - height - MARGIN;
        if (x < graphics.guiWidth() / 2 + 94) y -= 23;
        if (y < MARGIN) return;

        HudStyle.instrumentPanel(graphics, x, y, width, height);
        HudStyle.fit(graphics, title, x + 8, y + 5, width - 16, .93f, HudStyle.INK);
        graphics.fill(x + 7, y + 17, x + width - 7, y + 18, HudStyle.LINE);
        row(graphics, placeKey, placeText, x, y + 20, width, keyWidth, HudStyle.INK);
        if (canRotate) row(graphics, rotateKey, rotateText, x, y + 20 + ROW_HEIGHT, width, keyWidth, HudStyle.INK);
        row(graphics, snapKey, snapText, x, y + 20 + (canRotate ? 2 : 1) * ROW_HEIGHT, width, keyWidth,
                snapped ? HudStyle.STABLE : HudStyle.MUTED);
    }

    private static void row(GuiGraphicsExtractor graphics, Component key, Component text,
                            int x, int y, int width, int keyWidth, int color) {
        int keyX = x + 7;
        graphics.fill(keyX, y, keyX + keyWidth, y + 12, 0xC5263C4C);
        graphics.fill(keyX, y + 11, keyX + keyWidth, y + 12, HudStyle.LINE);
        int centeredKey = keyX + Math.max(3, (keyWidth - Math.round(HudStyle.width(key) * TEXT_SCALE)) / 2);
        HudStyle.fit(graphics, key, centeredKey, y + 2, keyX + keyWidth - centeredKey - 2, TEXT_SCALE, HudStyle.INK);
        HudStyle.fit(graphics, text, keyX + keyWidth + 6, y + 2, width - keyWidth - 21, TEXT_SCALE, color);
    }
}
