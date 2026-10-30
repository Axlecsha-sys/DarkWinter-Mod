// Arranges equipment and survival information around the native player inventory menu.
package com.dark.winter.client;

import com.dark.winter.client.mixin.client.DarkWinterSlotAccessor;
import com.dark.winter.equipment.GearSlot;
import com.dark.winter.equipment.ItemMass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;

import java.util.function.BooleanSupplier;

/**
 * Functional (non-final-art) inventory layout for Minecraft 26.3.
 * Uses player's existing InventoryMenu and never creates an extra item storage.
 */
public final class DarkWinterInventoryScreen extends AbstractContainerScreen<InventoryMenu> implements com.dark.winter.client.lab.LabUiProvider {

    private static final int PANEL_W = 330;
    private static final int PANEL_H = 312;
    private static final int HIDDEN_COORD = -10000;
    private static final int SLOT_STEP = 18;
    private static final int ITEMS_X = 84;

    private enum Page { ITEMS, EQUIPMENT }

    private Page page = Page.ITEMS;
    private final int[] originalX;
    private final int[] originalY;

    public DarkWinterInventoryScreen(Player player) {
        super(player.inventoryMenu, player.getInventory(), Component.literal("Инвентарь"), PANEL_W, PANEL_H);

        originalX = new int[menu.slots.size()];
        originalY = new int[menu.slots.size()];
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            originalX[i] = slot.x;
            originalY[i] = slot.y;
        }
    }

    @Override
    protected void init() {
        super.init();

        boolean creative = this.minecraft.player != null && this.minecraft.player.hasInfiniteMaterials();
        int x = leftPos + 9;
        int y = topPos + 9;

        // Uniform functional tabs; no vanilla stone-textured buttons.
        if (creative) {
            addRenderableWidget(new FlatTabButton(x, y, 100, "Инвентарь", () -> page == Page.ITEMS,
                    b -> switchPage(Page.ITEMS)));
            addRenderableWidget(new FlatTabButton(x + 106, y, 100, "Снаряжение", () -> page == Page.EQUIPMENT,
                    b -> switchPage(Page.EQUIPMENT)));
            addRenderableWidget(new FlatTabButton(x + 212, y, 100, "Творчество", () -> false,
                    b -> openCreative()));
        } else {
            addRenderableWidget(new FlatTabButton(x, y, 153, "Инвентарь", () -> page == Page.ITEMS,
                    b -> switchPage(Page.ITEMS)));
            addRenderableWidget(new FlatTabButton(x + 159, y, 153, "Снаряжение", () -> page == Page.EQUIPMENT,
                    b -> switchPage(Page.EQUIPMENT)));
        }

        positionSlots();
    }

    @Override
    public java.util.Map<String,String> darkwinter$labState() {
        return java.util.Map.of("page",page.name());
    }

    private void switchPage(Page next) {
        page = next;
        positionSlots();
    }

    private void openCreative() {
        if (this.minecraft.player == null || !this.minecraft.player.hasInfiniteMaterials()) {
            return;
        }
        DarkWinterInventoryHooks.allowCreativeScreen();
        this.minecraft.gui.setScreen(new CreativeModeInventoryScreen(
                this.minecraft.player,
                this.minecraft.player.connection.enabledFeatures(),
                this.minecraft.options.operatorItemsTab().get()
        ));
    }

    private void moveSlot(int index, int x, int y) {
        if (index < 0 || index >= menu.slots.size()) return;
        DarkWinterSlotAccessor accessor = (DarkWinterSlotAccessor) menu.slots.get(index);
        accessor.darkwinter$setX(x);
        accessor.darkwinter$setY(y);
    }

    private void positionSlots() {
        // Slots still belong to the vanilla InventoryMenu. Move only their UI coordinates.
        for (int i = 0; i < menu.slots.size(); i++) {
            moveSlot(i, HIDDEN_COORD, HIDDEN_COORD);
        }

        if (page == Page.EQUIPMENT) {
            // Slot IDs from the standard 26.3 InventoryMenu.
            // 5..8 armor, 45 offhand, 1..4 2x2 crafting, 0 result.
            for (int i = 5; i <= 8; i++) {
                moveSlot(i, 50, 67 + (i - 5) * SLOT_STEP);
            }
            moveSlot(45, 50, 148);

            for (int i = 1; i <= 4; i++) {
                int k = i - 1;
                moveSlot(i, 10 + (k % 2) * SLOT_STEP, 266 + (k / 2) * SLOT_STEP);
            }
            moveSlot(0, 50, 275);
            for (GearSlot gear : GearSlot.values())
                moveSlot(GearSlot.MENU_START + gear.ordinal(), 211, 44 + gear.ordinal() * 18);
        }

        int inventoryY = page == Page.EQUIPMENT ? 210 : 96;
        int hotbarY = page == Page.EQUIPMENT ? 279 : 190;

        // Main inventory: 3 rows x 9 slots.
        for (int i = 9; i <= 35; i++) {
            int k = i - 9;
            moveSlot(i, ITEMS_X + (k % 9) * SLOT_STEP, inventoryY + (k / 9) * SLOT_STEP);
        }

        // Vanilla hotbar items are kept, but the game HUD hotbar remains hidden.
        for (int i = 36; i <= 44; i++) {
            moveSlot(i, ITEMS_X + (i - 36) * SLOT_STEP, hotbarY);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        super.extractBackground(graphics, mouseX, mouseY, partialTicks);

        HudStyle.panel(graphics, leftPos, topPos, PANEL_W, PANEL_H);
        graphics.fill(leftPos + 9, topPos + 37, leftPos + PANEL_W - 9, topPos + 38, 0xFF45657E);

        if (page == Page.EQUIPMENT) {
            // A simple frame around the 3D model; not an art asset.
            graphics.fill(leftPos + 98, topPos + 58, leftPos + 197, topPos + 164, 0x442B4A62);
            graphics.outline(leftPos + 98, topPos + 58, 99, 106, 0xFF39566E);
        }

        // Slot outlines, each exactly at the actual slot position.
        for (Slot slot : menu.slots) {
            if (slot.x < 0 || slot.y < 0) continue;
            int x = leftPos + slot.x - 1;
            int y = topPos + slot.y - 1;
            graphics.fillGradient(x, y, x + 18, y + 18, 0xBC0C1722, 0xE3030A12);
            graphics.outline(x, y, 18, 18, 0xB3557183);
        }

        // Minecraft 26.3 already has an entity-in-inventory renderer: reuse it.
        // Kept only on Equipment page, as requested.
        if (page == Page.EQUIPMENT && this.minecraft.player != null) {
            InventoryScreen.extractEntityInInventoryFollowsMouse(
                    graphics,
                    leftPos + 101, topPos + 61,
                    leftPos + 194, topPos + 161,
                    35, 0.0625F,
                    mouseX, mouseY,
                    this.minecraft.player
            );
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (this.minecraft.player != null)
            HudStyle.text(graphics, Component.translatable("inventory.darkwinter.known_mass",
                    ItemMass.kilograms(ItemMass.carriedGrams(this.minecraft.player))),
                    ITEMS_X, page == Page.ITEMS ? 217 : 302, 1f, HudStyle.MUTED);
        if (page == Page.EQUIPMENT) {
            for (GearSlot gear : GearSlot.values())
                HudStyle.text(graphics, Component.translatable("equipment.darkwinter." + gear.id()),
                        233, 48 + gear.ordinal() * 18, 1f, HudStyle.MUTED);
        }
        // The parent's slot coordinate system is retained exactly.
        if (page == Page.ITEMS) {
            HudStyle.text(graphics, "Личные вещи", ITEMS_X, 75, 1f, HudStyle.INK);
            HudStyle.text(graphics, "Быстрый доступ", ITEMS_X, 173, 1f, HudStyle.MUTED);
        } else {
            HudStyle.text(graphics, "Снаряжение", 12, 43, 1f, HudStyle.INK);
            HudStyle.text(graphics, "Броня", 44, 53, 1f, HudStyle.MUTED);
            HudStyle.text(graphics, "Рука", 13, 151, 1f, HudStyle.MUTED);
            HudStyle.text(graphics, "Создание", 10, 250, 1f, HudStyle.MUTED);
            HudStyle.text(graphics, "Личные вещи", ITEMS_X, 194, 1f, HudStyle.INK);
            HudStyle.text(graphics, "Быстрый доступ", ITEMS_X, 267, 1f, HudStyle.MUTED);
        }
    }

    @Override
    public void removed() {
        // The exact same slot objects are used by other screens; restore them.
        for (int i = 0; i < menu.slots.size(); i++) {
            moveSlot(i, originalX[i], originalY[i]);
        }
        super.removed();
    }

    /** Flat clickable tabs with keyboard focus/narration support, without final visual styling. */
    private static final class FlatTabButton extends Button {
        private final BooleanSupplier selected;

        private FlatTabButton(int x, int y, int width, String label,
                              BooleanSupplier selected, OnPress onPress) {
            super(x, y, width, 21, Component.literal(label), onPress, DEFAULT_NARRATION);
            this.selected = selected;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
            boolean selectedTab = selected.getAsBoolean();
            MenuStyle.button(graphics, this, 1f, selectedTab ? 1f : isHoveredOrFocused() ? .75f : 0f);
            HudStyle.centered(graphics, MenuText.fit(HudStyle.font(getMessage()), getWidth() - 10),
                    getX() + getWidth() / 2, getY() + 6, 1f, selectedTab ? HudStyle.INK : HudStyle.MUTED);
        }
    }
}
