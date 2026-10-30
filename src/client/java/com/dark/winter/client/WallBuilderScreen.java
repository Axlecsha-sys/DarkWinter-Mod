// Focusable construction cards; drawing, keyboard navigation and Lab share widget bounds.
package com.dark.winter.client;

import com.dark.winter.client.lab.LabUiProvider;
import com.dark.winter.component.WallBuildManager;
import com.dark.winter.component.WallShape;
import com.dark.winter.network.UpdateWallShapePayload;
import com.dark.winter.registry.ModBlocks;
import com.dark.winter.registry.ModDataComponents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Focusable construction cards; drawing, keyboard navigation and Lab share widget bounds. */
public final class WallBuilderScreen extends Screen implements LabUiProvider {
    private static final int GAP = 8;
    private static final int PADDING = 12;
    private final List<ShapeButton> shapeButtons = new ArrayList<>();
    private ItemStack held = ItemStack.EMPTY;
    private boolean wall = true;
    private boolean floor;
    private int panelX, panelY, panelWidth, panelHeight;
    private int cardHeight;

    public WallBuilderScreen() {
        super(Component.translatable("wall.builder.title"));
    }

    @Override
    protected void setInitialFocus() {
        if (minecraft.getLastInputType().isKeyboard()) {
            for (ShapeButton button : shapeButtons) {
                if (button.shape == WallBuildManager.getClientShape()) {
                    setInitialFocus(button);
                    return;
                }
            }
        }
        super.setInitialFocus();
    }

    @Override
    protected void init() {
        shapeButtons.clear();
        held = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getMainHandItem().copy();
        wall = ModBlocks.isWallSegment(held);
        floor = ModBlocks.isFloorSegment(held);
        panelWidth = Math.min(550, width - 24);
        panelX = (width - panelWidth) / 2;
        int columns = panelWidth >= 510 ? 5 : 3;
        int rows = wall ? (WallShape.values().length + columns - 1) / columns : 1;
        int availableHeight = Math.max(40, height - 24 - 64 - 36 - (rows - 1) * GAP);
        cardHeight = Math.min(100, availableHeight / rows);
        panelHeight = 64 + cardHeight * rows + (rows - 1) * GAP + 36;
        panelY = (height - panelHeight) / 2;

        if (wall) {
            int cardWidth = (panelWidth - PADDING * 2 - (columns - 1) * GAP) / columns;
            WallShape[] shapes = WallShape.values();
            for (int i = 0; i < shapes.length; i++) {
                int row = i / columns;
                int rowCount = Math.min(columns, shapes.length - row * columns);
                int rowWidth = rowCount * cardWidth + (rowCount - 1) * GAP;
                int x = panelX + (panelWidth - rowWidth) / 2 + (i % columns) * (cardWidth + GAP);
                ShapeButton button = new ShapeButton(x, panelY + 64 + row * (cardHeight + GAP),
                        cardWidth, cardHeight, shapes[i]);
                shapeButtons.add(addRenderableWidget(button));
            }
        } else {
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                    .bounds(width / 2 - 58, panelY + panelHeight - 30, 116, 20).build());
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Keep the build site visible while choosing a module.
        g.fillGradient(0, 0, width, height, 0x80050E18, 0xC5030A12);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        HudStyle.panel(g, panelX, panelY, panelWidth, panelHeight);
        g.fill(panelX + PADDING, panelY + 11, panelX + PADDING + 2, panelY + 36, HudStyle.STABLE);
        HudStyle.fit(g, title, panelX + PADDING + 10, panelY + 12,
                panelWidth - PADDING * 2 - 10, 1.25f, HudStyle.INK);
        String kind = wall ? "wall" : floor ? "floor" : "window";
        Component material = held.isEmpty()
                ? Component.translatable("wall.material.darkwinter.spruce") : held.getHoverName();
        HudStyle.fit(g, Component.translatable("wall.builder." + kind + ".subtitle", material),
                panelX + PADDING + 10, panelY + 30, panelWidth - PADDING * 2 - 10, .78f, HudStyle.MUTED);
        HudStyle.fit(g, Component.translatable(floor ? "wall.builder.floor.profile" : "wall.builder.thickness"), panelX + PADDING,
                panelY + 49, panelWidth - PADDING * 2, .72f, HudStyle.MUTED);

        if (wall) {
            HudStyle.fit(g, Component.translatable("wall.builder.controls", DarkWinterClient.buildMenuKeyLabel()),
                    panelX + PADDING, panelY + panelHeight - 21, panelWidth - PADDING * 2, .67f, HudStyle.MUTED);
        } else {
            int iconWidth = Math.min(84, (panelWidth - PADDING * 2) / 4);
            int iconX = panelX + PADDING + iconWidth / 2;
            int iconY = panelY + 64 + cardHeight / 2;
            drawSinglePreview(g, iconX, iconY, Math.min(40, cardHeight - 12));
            HudStyle.paragraph(g, Component.translatable("wall.builder." + kind + ".hint"),
                    panelX + PADDING + iconWidth + 8, panelY + 69,
                    panelWidth - PADDING * 2 - iconWidth - 8, .78f, HudStyle.INK,
                    Math.max(1, (cardHeight - 20) / 10));
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawSinglePreview(GuiGraphicsExtractor g, int cx, int cy, int size) {
        if (floor) {
            g.fill(cx - size / 2, cy - 3, cx + size / 2, cy + 8, 0xFF526575);
            g.fill(cx - size / 2, cy - 3, cx + size / 2, cy, 0xFFABC5CE);
            for (int x = cx - size / 2 + 7; x < cx + size / 2; x += 8)
                g.fill(x, cy, x + 1, cy + 8, 0xFF233441);
        } else {
            int x = cx - size / 2, y = cy - size / 2;
            g.fill(x, y, x + size, y + size, 0xFF526575);
            g.fill(x + 3, y + 3, x + size - 3, y + size - 3, 0xFF94BCC9);
            g.fill(cx - 1, y, cx + 1, y + size, 0xFF344C5A);
            g.fill(x, cy - 1, x + size, cy + 1, 0xFF344C5A);
            g.fill(x + 5, y + 5, x + 8, y + size / 2 - 3, 0xFFAAD8E5);
        }
    }

    private void choose(WallShape chosen) {
        if (minecraft.player == null || !ClientPlayNetworking.canSend(UpdateWallShapePayload.TYPE)) return;
        ItemStack current = minecraft.player.getMainHandItem();
        if (!ModBlocks.isWallSegment(current)) {
            onClose();
            return;
        }
        WallBuildManager.setClientShape(chosen);
        current.set(ModDataComponents.WALL_SHAPE, chosen);
        ClientPlayNetworking.send(new UpdateWallShapePayload(chosen));
        onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (DarkWinterClient.isBuildMenuKey(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public List<Region> darkwinter$labRegions() {
        List<Region> regions = new ArrayList<>();
        for (ShapeButton button : shapeButtons) {
            WallShape shape = button.shape;
            regions.add(new Region("wall_shape." + shape.name(), button.getMessage().getString(),
                    Component.translatable("wall.builder.module_hint", shape.width(), shape.height(),
                            shape.width() * shape.height()).getString(), button.getX(), button.getY(),
                    button.getWidth(), button.getHeight(), shape == WallBuildManager.getClientShape()));
        }
        return regions;
    }

    @Override
    public Map<String, String> darkwinter$labState() {
        return Map.of("category", wall ? "wall" : floor ? "floor" : "window",
                "shape", WallBuildManager.getClientShape().getSerializedName());
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private final class ShapeButton extends Button {
        private final WallShape shape;

        private ShapeButton(int x, int y, int width, int height, WallShape shape) {
            super(x, y, width, height, Component.translatable("wall.builder.size", shape.width(), shape.height()),
                    button -> choose(shape), DEFAULT_NARRATION);
            this.shape = shape;
            setTooltip(Tooltip.create(Component.translatable("wall.builder.module_hint", shape.width(),
                    shape.height(), shape.width() * shape.height())));
            setTooltipDelay(java.time.Duration.ofMillis(500));
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            boolean selected = shape == WallBuildManager.getClientShape();
            boolean highlighted = isHoveredOrFocused();
            int edge = selected ? HudStyle.STABLE : highlighted ? HudStyle.INK : 0xFF425B69;
            g.fillGradient(x, y, x + w, y + h, selected ? 0xFF213E4E : 0xFF142833, 0xFF0B1822);
            g.outline(x, y, w, h, edge);
            if (selected || highlighted) g.fill(x + 1, y + 1, x + w - 1, y + 3, edge);
            HudStyle.centered(g, getMessage(), x + w / 2, y + 7, .9f, selected ? HudStyle.STABLE : HudStyle.INK);
            int cell = Math.max(3, Math.min(12, Math.min((w - 18) / shape.width(), (h - 38) / shape.height())));
            int gridX = x + (w - shape.width() * cell) / 2;
            int gridY = y + 22 + Math.max(0, (h - 38 - shape.height() * cell) / 2);
            for (int row = 0; row < shape.height(); row++) {
                for (int col = 0; col < shape.width(); col++) {
                    int sx = gridX + col * cell, sy = gridY + row * cell;
                    g.fill(sx, sy, sx + cell, sy + cell, 0xFF304D5B);
                    g.fill(sx + 1, sy + 1, sx + cell - 1, sy + cell - 1, selected ? 0xFFABCAD4 : 0xFF7899A8);
                    if (cell > 6) g.fill(sx + 2, sy + 3, sx + cell - 2, sy + 4, 0xFF526F7D);
                }
            }
            Component status = selected ? Component.translatable("wall.builder.selected")
                    : Component.translatable("wall.builder.segment_count", shape.width() * shape.height());
            HudStyle.centered(g, status, x + w / 2, y + h - 12, .65f,
                    selected ? HudStyle.STABLE : HudStyle.MUTED);
        }
    }
}
