// Fallback for a progress packet arriving after the vanilla loading screen has closed.
package com.dark.winter.client.loading;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Fallback for a progress packet arriving after the vanilla loading screen has closed. */
public final class WorldPreloadScreen extends Screen {
    private final Screen previous;

    public WorldPreloadScreen(Screen previous) {
        super(Component.translatable("loading.darkwinter.chunks"));
        this.previous = previous instanceof WorldPreloadScreen ? null : previous;
    }

    @Override protected void init() {
        int y = NorthernLoadingArt.buttonY(this.height);
        if (WorldPreloadClient.unacknowledgedFailure()) {
            this.addRenderableWidget(new QuietButton(this.width / 2 - 140, y, 136,
                    Component.translatable("loading.darkwinter.continue"), button -> {
                WorldPreloadClient.acknowledgeFailure();
                this.minecraft.gui.setScreen(this.previous);
            }));
            this.addRenderableWidget(menuButton(this.width / 2 + 4, y, 136));
        } else {
            this.addRenderableWidget(menuButton(this.width / 2 - 68, y, 136));
        }
    }

    @Override public void tick() {
        if (!WorldPreloadClient.active() && !WorldPreloadClient.unacknowledgedFailure()) {
            if (this.previous == null) WorldPreloadClient.loadingScreenClosed();
            this.minecraft.gui.setScreen(this.previous);
        }
    }

    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        NorthernLoadingArt.background(graphics, this.width, this.height);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        NorthernLoadingArt.foreground(graphics, this.font, this.width, this.height,
                WorldPreloadClient.progress(), WorldPreloadClient.displayedProgress(0), false);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    static Button menuButton(int x, int y, int width) {
        return new QuietButton(x, y, width, Component.translatable("loading.darkwinter.menu"), button -> WorldPreloadClient.cancel());
    }

    private static final class QuietButton extends Button {
        private QuietButton(int x, int y, int width, Component text, OnPress action) {
            super(x, y, width, 20, text, action, DEFAULT_NARRATION);
        }

        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            boolean highlighted = this.isHoveredOrFocused();
            graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(),
                    highlighted ? 0x80405e70 : 0x30192e3d);
            graphics.fill(this.getX(), this.getY() + this.getHeight() - 1,
                    this.getX() + this.getWidth(), this.getY() + this.getHeight(), highlighted ? 0xff91b9cd : 0xff3d596a);
            com.dark.winter.client.HudStyle.centered(graphics, this.getMessage(),
                    this.getX() + this.getWidth() / 2, this.getY() + 6, 1f, highlighted ? 0xffe2edf2 : 0xff9ab3c2);
        }
    }
}
