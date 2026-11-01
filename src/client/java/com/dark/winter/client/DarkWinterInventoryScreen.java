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
    private boolean compact;
    private int panelWidth = PANEL_W;
    private int panelHeight = PANEL_H;
    private final int[] originalX;
    private final int[] originalY;

    public DarkWinterInventoryScreen(Player player) {
        super(player.inventoryMenu, player.getInventory(), Component.translatable("container.inventory"), PANEL_W, PANEL_H);

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

        // Keep native 16-pixel slots and mouse coordinates, even at Auto's 320x240 viewport.
        // The final superclass image dimensions are only used for initial centering/outside clicks.
        compact = width < PANEL_W + 16 || height < PANEL_H + 16;
        panelWidth = Math.min(PANEL_W, width - 16);
        panelHeight = compact ? 224 : PANEL_H;
        leftPos = (width - panelWidth) / 2;
        topPos = (height - panelHeight) / 2;

        boolean creative = this.minecraft.player != null && this.minecraft.player.hasInfiniteMaterials();
        int x = leftPos + 9;
        int y = topPos + 9;
        int count = creative ? 3 : 2;
        int tabWidth = (panelWidth - 18 - 6 * (count - 1)) / count;

        // Uniform functional tabs; no vanilla stone-textured buttons.
        addRenderableWidget(new FlatTabButton(x, y, tabWidth, Component.translatable("container.inventory"),
                () -> page == Page.ITEMS, b -> switchPage(Page.ITEMS)));
        addRenderableWidget(new FlatTabButton(x + tabWidth + 6, y, tabWidth, text("equipment"),
                () -> page == Page.EQUIPMENT, b -> switchPage(Page.EQUIPMENT)));
        if (creative) addRenderableWidget(new FlatTabButton(x + (tabWidth + 6) * 2, y, tabWidth,
                Component.translatable("gameMode.creative"), () -> false, b -> openCreative()));

        positionSlots();
    }

    @Override
    public java.util.Map<String,String> darkwinter$labState() {
        return java.util.Map.of("page",page.name(),"compact",Boolean.toString(compact));
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
                moveSlot(i, compact ? 10 : 50, (compact ? 49 : 67) + (i - 5) * SLOT_STEP);
            }
            moveSlot(45, compact ? 35 : 50, compact ? 103 : 148);

            for (int i = 1; i <= 4; i++) {
                int k = i - 1;
                moveSlot(i, (compact ? 129 : 10) + (k % 2) * SLOT_STEP,
                        (compact ? 54 : 266) + (k / 2) * SLOT_STEP);
            }
            moveSlot(0, compact ? 171 : 50, compact ? 63 : 275);
            for (GearSlot gear : GearSlot.values())
                moveSlot(GearSlot.MENU_START + gear.ordinal(), compact ? 199 : 211,
                        (compact ? 46 : 44) + gear.ordinal() * 18);
        }

        int inventoryY = inventoryY();
        int hotbarY = hotbarY();

        // Main inventory: 3 rows x 9 slots.
        for (int i = 9; i <= 35; i++) {
            int k = i - 9;
            moveSlot(i, itemsX() + (k % 9) * SLOT_STEP, inventoryY + (k / 9) * SLOT_STEP);
        }

        // These are the same native hotbar slots, including the Testing HUD's 1-9 slots.
        for (int i = 36; i <= 44; i++) {
            moveSlot(i, itemsX() + (i - 36) * SLOT_STEP, hotbarY);
        }
    }

    private int itemsX() { return compact ? page == Page.EQUIPMENT ? 10 : (panelWidth - 162) / 2 : ITEMS_X; }
    private int inventoryY() { return page == Page.EQUIPMENT ? compact ? 132 : 210 : compact ? 66 : 96; }
    private int hotbarY() { return page == Page.EQUIPMENT ? compact ? 200 : 279 : compact ? 140 : 190; }
    private static Component text(String key) { return Component.translatable("inventory.darkwinter." + key); }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        return mouseX < left || mouseY < top || mouseX >= left + panelWidth || mouseY >= top + panelHeight;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        super.extractBackground(graphics, mouseX, mouseY, partialTicks);

        HudStyle.panel(graphics, leftPos, topPos, panelWidth, panelHeight);
        graphics.fill(leftPos + 9, topPos + (compact ? 30 : 37), leftPos + panelWidth - 9,
                topPos + (compact ? 31 : 38), 0xFF45657E);

        if (page == Page.EQUIPMENT) {
            // A simple frame around the 3D model; not an art asset.
            int modelX = compact ? 44 : 98, modelY = compact ? 48 : 58;
            int modelW = compact ? 72 : 99, modelH = compact ? 54 : 106;
            graphics.fill(leftPos + modelX, topPos + modelY, leftPos + modelX + modelW,
                    topPos + modelY + modelH, 0x442B4A62);
            graphics.outline(leftPos + modelX, topPos + modelY, modelW, modelH, 0xFF39566E);
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
                    leftPos + (compact ? 47 : 101), topPos + (compact ? 49 : 61),
                    leftPos + (compact ? 113 : 194), topPos + (compact ? 100 : 161),
                    compact ? 20 : 35, 0.0625F,
                    mouseX, mouseY,
                    this.minecraft.player
            );
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (this.minecraft.player != null && compact && page == Page.EQUIPMENT) {
            HudStyle.fit(graphics, Component.translatable("inventory.darkwinter.mass_compact",
                    ItemMass.kilograms(ItemMass.carriedGrams(this.minecraft.player))),
                    199, 210, panelWidth - 207, 1f, HudStyle.MUTED);
        } else if (this.minecraft.player != null)
            HudStyle.fit(graphics, Component.translatable("inventory.darkwinter.known_mass",
                    ItemMass.kilograms(ItemMass.carriedGrams(this.minecraft.player))),
                    itemsX(), page == Page.ITEMS ? compact ? 172 : 217 : 300,
                    panelWidth - itemsX() - 8, 1f, HudStyle.MUTED);
        if (page == Page.EQUIPMENT) {
            for (GearSlot gear : GearSlot.values())
                HudStyle.fit(graphics, Component.translatable("equipment.darkwinter." + gear.id()),
                        compact ? 221 : 233, (compact ? 50 : 48) + gear.ordinal() * 18,
                        panelWidth - (compact ? 221 : 233) - 8, 1f, HudStyle.MUTED);
        }
        // The parent's slot coordinate system is retained exactly.
        if (page == Page.ITEMS) {
            HudStyle.text(graphics, text("items"), itemsX(), inventoryY() - 17, 1f, HudStyle.INK);
            HudStyle.text(graphics, text("hotbar"), itemsX(), hotbarY() - 17, 1f, HudStyle.MUTED);
        } else if (compact) {
            HudStyle.text(graphics, text("armor"), 10, 36, 1f, HudStyle.MUTED);
            HudStyle.fit(graphics, text("offhand"), 58, 106, 65, 1f, HudStyle.MUTED);
            HudStyle.fit(graphics, Component.translatable("container.crafting"), 129, 39, 62, 1f, HudStyle.MUTED);
            HudStyle.text(graphics, text("items"), 10, 120, 1f, HudStyle.INK);
            HudStyle.text(graphics, text("hotbar"), 10, 187, 1f, HudStyle.MUTED);
        } else {
            HudStyle.text(graphics, text("equipment"), 12, 43, 1f, HudStyle.INK);
            HudStyle.text(graphics, text("armor"), 44, 53, 1f, HudStyle.MUTED);
            HudStyle.fit(graphics, text("offhand"), 8, 151, 38, 1f, HudStyle.MUTED);
            HudStyle.fit(graphics, Component.translatable("container.crafting"), 10, 250, 64, 1f, HudStyle.MUTED);
            HudStyle.text(graphics, text("items"), ITEMS_X, 194, 1f, HudStyle.INK);
            HudStyle.text(graphics, text("hotbar"), ITEMS_X, 267, 1f, HudStyle.MUTED);
        }
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        if (page != Page.EQUIPMENT || !menu.getCarried().isEmpty()) return;
        if (compact && minecraft.player != null && mouseX >= leftPos + 199 && mouseX < leftPos + panelWidth - 8
                && mouseY >= topPos + 208 && mouseY < topPos + 222) {
            graphics.setTooltipForNextFrame(net.minecraft.client.gui.components.Tooltip.splitTooltip(minecraft,
                    HudStyle.font(Component.translatable("inventory.darkwinter.known_mass",
                            ItemMass.kilograms(ItemMass.carriedGrams(minecraft.player))))), mouseX, mouseY);
            return;
        }
        for (GearSlot gear : GearSlot.values()) {
            Slot slot = menu.slots.get(GearSlot.MENU_START + gear.ordinal());
            int labelRight = leftPos + panelWidth - 8;
            boolean onLabel = mouseX >= leftPos + slot.x + 20 && mouseX < labelRight;
            boolean emptySlot = !slot.hasItem() && mouseX >= leftPos + slot.x - 1 && mouseX < leftPos + slot.x + 17;
            if ((onLabel || emptySlot) && mouseY >= topPos + slot.y && mouseY < topPos + slot.y + 16) {
                graphics.setTooltipForNextFrame(net.minecraft.client.gui.components.Tooltip.splitTooltip(minecraft,
                        HudStyle.font(Component.translatable("equipment.darkwinter." + gear.id()))), mouseX, mouseY);
                return;
            }
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

        private FlatTabButton(int x, int y, int width, Component label,
                              BooleanSupplier selected, OnPress onPress) {
            super(x, y, width, 21, label, onPress, DEFAULT_NARRATION);
            this.selected = selected;
            if (HudStyle.width(label) > width - 10)
                setTooltip(net.minecraft.client.gui.components.Tooltip.create(HudStyle.font(label)));
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
