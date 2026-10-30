// Shared navigation around native settings pages; their widgets and persistence stay native.
package com.dark.winter.client;

import com.dark.winter.client.mixin.client.SettingsOptionsAccess;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.network.chat.Component;

/** Shared navigation around native settings pages; their widgets and persistence stay native. */
public final class SettingsNavigation {
    public enum Section {
        VIDEO("options.video", "VideoSettingsScreen"),
        SOUND("options.sounds", "SoundOptionsScreen"),
        CONTROLS("options.controls", "ControlsScreen"),
        INTERFACE("menu.darkwinter.interface", "UiPreferencesScreen"),
        ACCESSIBILITY("options.accessibility", "AccessibilityOptionsScreen"),
        LANGUAGE("options.language", "LanguageSelectScreen");
        public final String key;
        private final String primaryClass;
        Section(String key, String primaryClass) { this.key=key; this.primaryClass=primaryClass; }
    }

    private SettingsNavigation() {}

    public static boolean applies(Screen screen) {
        return screen instanceof OptionsSubScreen || screen instanceof UiPreferencesScreen;
    }

    public static int contentLeft(Screen screen) { return UiLayout.hub(screen.width,screen.height).right()+6; }
    public static int contentWidth(Screen screen) { return Math.max(84,UiLayout.hub(screen.width,screen.height).contentWidth()-12); }
    public static int optionLeft(Screen screen) { return contentLeft(screen)+6; }
    public static int optionWidth(Screen screen) { return Math.max(72,contentWidth(screen)-12); }
    public static boolean compact(Screen screen) { return optionWidth(screen)<300; }
    public static boolean keyCompact(Screen screen) { return optionWidth(screen)<250; }

    public static void keyRowHeights(net.minecraft.client.gui.screens.options.controls.KeyBindsList list,Screen screen) {
        for(Object raw:list.children()) if(raw instanceof ContainerObjectSelectionList.Entry<?> row
                && raw.getClass().getSimpleName().equals("KeyEntry")) row.setHeight(keyCompact(screen)?38:20);
    }

    public static Screen parent(Screen screen) {
        if(screen instanceof OptionsSubScreen) return ((SettingsOptionsAccess)screen).darkwinter$parent();
        if(screen instanceof UiPreferencesScreen preferences) return preferences.parentScreen();
        return null;
    }

    private static Section section(Screen screen) {
        Screen current=screen;
        for(int depth=0;current!=null && depth<16;depth++) {
            for(Section section:Section.values()) if(current.getClass().getSimpleName().equals(section.primaryClass)) return section;
            Screen next=parent(current);
            if(next==current) break;
            current=next;
        }
        return null;
    }

    public static boolean selected(Screen screen,String key) {
        Section active=section(screen);
        return active!=null && active.key.equals(key);
    }

    public static boolean sidebar(AbstractWidget widget) {
        if(widget instanceof NavButton) return true;
        Screen screen=Minecraft.getInstance().gui.screen();
        if(!(screen instanceof OptionsScreen) || widget.getX()!=UiLayout.hub(screen.width,screen.height).left()) return false;
        for(Section section:Section.values()) if(MenuStyle.key(widget,section.key)) return true;
        return false;
    }

    public static boolean sidebarHit(Screen screen,double x,double y) {
        return screen.children().stream().anyMatch(child -> child instanceof NavButton button
                && button.visible && button.active && button.isMouseOver(x,y));
    }

    public static void ensure(Screen screen,Consumer<Button> add) {
        if(!applies(screen)) return;
        var layout=UiLayout.hub(screen.width,screen.height);
        for(Section section:Section.values()) {
            NavButton button=screen.children().stream().filter(child -> child instanceof NavButton nav && nav.section==section)
                    .map(child -> (NavButton)child).findFirst().orElse(null);
            if(button==null) { button=new NavButton(screen,section);add.accept(button); }
            button.setPosition(layout.left(),layout.top()+section.ordinal()*layout.step());
            button.setWidth(layout.navWidth());button.setHeight(Math.max(16,layout.step()-3));
        }
    }

    /** Native option rows retain one/two controls but may stack at small GUI widths. */
    public static void rowHeights(OptionsList list,Screen screen) {
        int index=0;
        for(Object raw:list.children()) {
            if(raw instanceof ContainerObjectSelectionList.Entry<?> row && raw instanceof ContainerEventHandler group) {
                if(raw.getClass().getSimpleName().equals("Entry")) {
                    row.setHeight(compact(screen) && group.children().size()==2?44:25);
                } else if(raw.getClass().getSimpleName().equals("HeaderEntry")) {
                    for(var child:group.children()) if(child instanceof FocusableTextWidget text) {
                        text.setMaxWidth(optionWidth(screen));text.setWidth(optionWidth(screen));text.updateHeight();
                        row.setHeight((index==0?0:18)+Math.max(18,text.getHeight()+2));
                    }
                }
            }
            index++;
        }
    }

    public static void frame(GuiGraphicsExtractor g,Screen screen) {
        var layout=UiLayout.hub(screen.width,screen.height);
        HudStyle.text(g,Component.translatable("options.title"),layout.left(),18,1.42f,HudStyle.INK);
        g.fill(layout.left(),39,screen.width-layout.left(),40,0x90537384);
        HudStyle.text(g,Component.translatable("menu.darkwinter.categories"),layout.left(),46,.73f,HudStyle.MUTED);
        HudStyle.panel(g,layout.right()-4,48,layout.contentWidth()+8,Math.max(60,screen.height-90));
        HudStyle.fit(g,screen.getTitle(),contentLeft(screen),54,contentWidth(screen),.75f,HudStyle.MUTED);
    }

    private static OptionsScreen hub(Screen source) {
        Screen current=source,last=source;
        for(int depth=0;current!=null && depth<16;depth++) {
            if(current instanceof OptionsScreen options) return options;
            last=current;
            Screen next=parent(current);
            if(next==current) break;
            current=next;
        }
        return new OptionsScreen(last,Minecraft.getInstance().options);
    }

    private static void open(Screen source,Section destination) {
        if(source.getClass().getSimpleName().equals(destination.primaryClass)) return;
        var minecraft=Minecraft.getInstance();
        OptionsScreen hub=hub(source);
        Screen current=source;
        for(int depth=0;depth<16 && applies(current);depth++) {
            Screen expectedParent=parent(current);
            if(current instanceof OptionsSubScreen) {
                OptionsList list=((SettingsOptionsAccess)current).darkwinter$optionsList();
                if(list!=null) list.applyUnsavedChanges();
            }
            // Close each native parent, too: Language -> Font -> Sound must
            // apply the pending language selection through Language's Done.
            Button done=current.children().stream().filter(child -> child instanceof Button button && MenuStyle.key(button,"gui.done"))
                    .map(child -> (Button)child).findFirst().orElse(null);
            if(done!=null) done.onPress(null); else current.onClose();
            Screen returned=minecraft.gui.screen();
            if(returned!=expectedParent && returned!=hub && returned!=null) return; // Respect a native confirmation screen.
            if(returned!=null && returned.getClass().getSimpleName().equals(destination.primaryClass)) {
                focus(returned,destination);
                return;
            }
            if(returned==hub || returned==null || !applies(returned)) break;
            current=returned;
            if(depth==15) return;
        }
        Screen next=switch(destination) {
            case VIDEO -> new VideoSettingsScreen(hub,minecraft,minecraft.options);
            case SOUND -> new SoundOptionsScreen(hub,minecraft.options);
            case CONTROLS -> new ControlsScreen(hub,minecraft.options);
            case INTERFACE -> new UiPreferencesScreen(hub);
            case ACCESSIBILITY -> new AccessibilityOptionsScreen(hub,minecraft.options);
            case LANGUAGE -> new LanguageSelectScreen(hub,minecraft.options,minecraft.getLanguageManager());
        };
        minecraft.gui.setScreen(next);
        focus(next,destination);
    }

    private static void focus(Screen screen,Section destination) {
        for(var child:screen.children()) if(child instanceof NavButton nav && nav.section==destination) screen.setFocused(nav);
    }

    private static final class NavButton extends Button {
        private final Section section;
        private NavButton(Screen owner,Section section) {
            super(0,0,100,20,Component.translatable(section.key),button -> open(owner,section),DEFAULT_NARRATION);
            this.section=section;
        }
        @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) {
            MenuStyle.button(g,this,alpha,isHoveredOrFocused()?1:0);
            HudStyle.fit(g,getMessage(),getX()+8,getY()+(getHeight()-10)/2f,getWidth()-16,.86f,HudStyle.INK);
        }
    }
}
