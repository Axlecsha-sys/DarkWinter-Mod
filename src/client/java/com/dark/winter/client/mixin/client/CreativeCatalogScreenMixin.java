package com.dark.winter.client.mixin.client;

import com.dark.winter.client.inventory.CreativeCatalogButton;
import com.dark.winter.client.lab.LabUiProvider;
import com.dark.winter.registry.ModCreativeTabs;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen;
import net.fabricmc.fabric.impl.client.creativetab.FabricCreativeGuiComponents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Two catalog pages over Minecraft's unchanged creative slots and inventory synchronization. */
// Apply after Fabric so our cancellable HEAD handlers precede its page-key handlers.
@Mixin(value = CreativeModeInventoryScreen.class, priority = 900)
public abstract class CreativeCatalogScreenMixin
        extends AbstractContainerScreen<CreativeModeInventoryScreen.ItemPickerMenu> implements LabUiProvider {
    @Shadow private static CreativeModeTab selectedTab;
    @Shadow private float scrollOffs;
    @Shadow protected abstract void selectTab(CreativeModeTab tab);
    @Shadow protected abstract int getTabX(CreativeModeTab tab);
    @Shadow protected abstract int getTabY(CreativeModeTab tab);
    @Unique private CreativeCatalogButton darkwinter$catalogButton;
    @Unique private boolean darkwinter$catalogMouseDown;

    protected CreativeCatalogScreenMixin(CreativeModeInventoryScreen.ItemPickerMenu menu,
            Inventory inventory, Component title) {
        super(menu, inventory, title, 195, 136);
    }

    @Unique
    private FabricCreativeModeInventoryScreen darkwinter$pages() {
        return (FabricCreativeModeInventoryScreen) (Object) this;
    }

    @Unique
    private boolean darkwinter$isOwnCatalog() {
        return darkwinter$pages().getCurrentPage() == darkwinter$pages().getPage(ModCreativeTabs.firstTab());
    }

    @Unique
    private boolean darkwinter$allows(CreativeModeTab tab) {
        if (tab.getType() == CreativeModeTab.Type.HOTBAR) return false;
        if (tab.getType() == CreativeModeTab.Type.SEARCH || tab.getType() == CreativeModeTab.Type.INVENTORY)
            return true;
        if (darkwinter$isOwnCatalog()) return ModCreativeTabs.isDarkWinterTab(tab);
        return "minecraft".equals(BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab).getNamespace());
    }

    @Unique
    private void darkwinter$switchCatalog() {
        CreativeModeTab destination = darkwinter$isOwnCatalog()
                ? CreativeModeTabs.getDefaultTab() : ModCreativeTabs.firstTab();
        darkwinter$pages().switchToPage(darkwinter$pages().getPage(destination));
        // Search/inventory are shared Fabric tabs: always select a real destination category
        // so neither the previous search results nor its query survive in the other catalog.
        selectTab(destination);
        darkwinter$catalogButton.refreshLabel();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void darkwinter$catalogNavigation(CallbackInfo ci) {
        if (minecraft.player == null || !minecraft.player.hasInfiniteMaterials()) return;
        for (var child : List.copyOf(children())) {
            if (child instanceof FabricCreativeGuiComponents.CreativeModeTabButton) removeWidget(child);
        }
        if (!darkwinter$allows(selectedTab))
            selectTab(darkwinter$isOwnCatalog() ? ModCreativeTabs.firstTab() : CreativeModeTabs.getDefaultTab());
        darkwinter$catalogMouseDown = false;
        darkwinter$catalogButton = addRenderableWidget(new CreativeCatalogButton(
                leftPos + imageWidth - 53, topPos + imageHeight,
                this::darkwinter$isOwnCatalog, this::darkwinter$switchCatalog));
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void darkwinter$catalogClick(MouseButtonEvent event, boolean doubleClick,
            CallbackInfoReturnable<Boolean> cir) {
        if (event.button() == 1 && darkwinter$catalogButton != null
                && darkwinter$catalogButton.isMouseOver(event.x(), event.y())) {
            setFocused(darkwinter$catalogButton);
            darkwinter$catalogMouseDown = true;
            darkwinter$switchCatalog();
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void darkwinter$catalogRelease(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() == 1 && darkwinter$catalogMouseDown) {
            darkwinter$catalogMouseDown = false;
            setDragging(false);
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void darkwinter$catalogKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.key() == InputConstants.KEY_PAGEUP || event.key() == InputConstants.KEY_PAGEDOWN) {
            boolean next = event.key() == InputConstants.KEY_PAGEDOWN;
            if (next != darkwinter$isOwnCatalog()) darkwinter$switchCatalog();
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "selectTab", at = @At("HEAD"), cancellable = true)
    private void darkwinter$onlyCatalogTabs(CreativeModeTab tab, CallbackInfo ci) {
        if (!darkwinter$allows(tab)) ci.cancel();
    }

    @Inject(method = "extractTabButton", at = @At("HEAD"), cancellable = true)
    private void darkwinter$visibleTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            CreativeModeTab tab, CallbackInfo ci) {
        if (!darkwinter$allows(tab)) ci.cancel();
    }

    @Inject(method = "checkTabClicked", at = @At("HEAD"), cancellable = true)
    private void darkwinter$tabHit(CreativeModeTab tab, double mouseX, double mouseY,
            CallbackInfoReturnable<Boolean> cir) {
        if (!darkwinter$allows(tab)) cir.setReturnValue(false);
    }

    @Inject(method = "checkTabHovering", at = @At("HEAD"), cancellable = true)
    private void darkwinter$tabTooltip(GuiGraphicsExtractor graphics, CreativeModeTab tab,
            int mouseX, int mouseY, CallbackInfoReturnable<Boolean> cir) {
        if (!darkwinter$allows(tab)) cir.setReturnValue(false);
    }

    @Unique
    private CreativeModeTab.Row darkwinter$row(CreativeModeTab tab) {
        int index = ModCreativeTabs.tabs().indexOf(tab);
        if (index >= 0) return index < 5 ? CreativeModeTab.Row.TOP : CreativeModeTab.Row.BOTTOM;
        // Keep operator blocks available on Minecraft, in the freed saved-hotbars position.
        if (tab == BuiltInRegistries.CREATIVE_MODE_TAB.getValue(CreativeModeTabs.OP_BLOCKS))
            return CreativeModeTab.Row.TOP;
        return tab.row();
    }

    @Inject(method = "getTabX", at = @At("HEAD"), cancellable = true)
    private void darkwinter$categoryX(CreativeModeTab tab, CallbackInfoReturnable<Integer> cir) {
        int index = ModCreativeTabs.tabs().indexOf(tab);
        if (index >= 0) cir.setReturnValue((index % 5) * 27);
    }

    @Inject(method = "getTabY", at = @At("HEAD"), cancellable = true)
    private void darkwinter$categoryY(CreativeModeTab tab, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(darkwinter$row(tab) == CreativeModeTab.Row.TOP ? -32 : imageHeight);
    }

    @Redirect(method = "extractTabButton", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/CreativeModeTab;row()Lnet/minecraft/world/item/CreativeModeTab$Row;"))
    private CreativeModeTab.Row darkwinter$drawRow(CreativeModeTab tab) {
        return darkwinter$row(tab);
    }

    @Redirect(method = "extractTabButton", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/CreativeModeTab;column()I"))
    private int darkwinter$drawColumn(CreativeModeTab tab) {
        int index = ModCreativeTabs.tabs().indexOf(tab);
        return index >= 0 ? index % 5 : tab.column();
    }

    @Unique
    private void darkwinter$filterItems() {
        if (selectedTab.getType() == CreativeModeTab.Type.INVENTORY) return;
        String namespace = darkwinter$isOwnCatalog() ? "darkwinter" : "minecraft";
        if (menu.items.removeIf(stack -> !namespace.equals(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace()))) menu.scrollTo(scrollOffs);
    }

    @Inject(method = "refreshSearchResults", at = @At("TAIL"))
    private void darkwinter$searchScope(CallbackInfo ci) {
        darkwinter$filterItems();
    }

    @Inject(method = "selectTab", at = @At("TAIL"))
    private void darkwinter$categoryScope(CreativeModeTab tab, CallbackInfo ci) {
        darkwinter$filterItems();
    }

    @Override
    public List<Region> darkwinter$labRegions() {
        List<Region> regions = new ArrayList<>();
        for (CreativeModeTab tab : darkwinter$pages().getTabsOnPage(darkwinter$pages().getCurrentPage())) {
            if (!darkwinter$allows(tab) || !tab.shouldDisplay()) continue;
            String id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab).toString();
            regions.add(new Region("creative-tab-" + id, tab.getDisplayName().getString(),
                    "Открывает категорию текущего каталога; используется штатное меню креатива.",
                    leftPos + getTabX(tab), topPos + getTabY(tab)
                            + (darkwinter$row(tab) == CreativeModeTab.Row.TOP ? 4 : 0), 26, 28, tab == selectedTab));
        }
        if (darkwinter$catalogButton != null)
            regions.add(new Region("creative-catalog-switch", darkwinter$catalogButton.getMessage().getString(),
                    "Переключает каталоги Minecraft и DarkWinter; инвентарь и предмет на курсоре сохраняются.",
                    darkwinter$catalogButton.getX(), darkwinter$catalogButton.getY(),
                    darkwinter$catalogButton.getWidth(), darkwinter$catalogButton.getHeight(), false));
        return regions;
    }

    @Override
    public Map<String, String> darkwinter$labState() {
        Map<String, String> state = new LinkedHashMap<>();
        state.put("catalog", darkwinter$isOwnCatalog() ? "darkwinter" : "minecraft");
        state.put("category", BuiltInRegistries.CREATIVE_MODE_TAB.getKey(selectedTab).toString());
        state.put("catalogEntries", Integer.toString(menu.items.size()));
        state.put("carriedItem", BuiltInRegistries.ITEM.getKey(menu.getCarried().getItem()).toString());
        state.put("carriedCount", Integer.toString(menu.getCarried().getCount()));
        state.put("fabricPage", Integer.toString(darkwinter$pages().getCurrentPage()));
        state.put("minecraftPage", Integer.toString(darkwinter$pages().getPage(CreativeModeTabs.getDefaultTab())));
        state.put("darkwinterPages", ModCreativeTabs.tabs().stream()
                .map(tab -> Integer.toString(darkwinter$pages().getPage(tab))).distinct().toList().toString());
        state.put("foreignCatalogEntries", Long.toString(selectedTab.getType() == CreativeModeTab.Type.INVENTORY
                ? 0 : menu.items.stream().filter(stack -> !(darkwinter$isOwnCatalog() ? "darkwinter" : "minecraft")
                .equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace())).count()));
        state.put("categoryCounts", ModCreativeTabs.tabs().stream()
                .map(tab -> BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab).getPath() + "=" + tab.getDisplayItems().size())
                .toList().toString());
        return state;
    }
}
