// Adds a native video option for the next launch while keeping the active OIT ABI immutable.
package com.dark.winter.client.fixes.render;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

public final class TransparencyVideoOption {
    private static final Logger LOGGER=LogUtils.getLogger();
    private static final String KEY="options.darkwinter.transparency";
    private static final Codec<EarlyTransparency.Mode> CODEC=Codec.STRING.comapFlatMap(
            value->switch(value) {
                case "BALANCED"->DataResult.success(EarlyTransparency.Mode.BALANCED);
                case "PRECISE"->DataResult.success(EarlyTransparency.Mode.PRECISE);
                default->DataResult.error(()->"Unknown transparency mode");
            },EarlyTransparency.Mode::name);

    private final Minecraft client;
    private final OptionsList list;
    private final boolean capable;
    private final EarlyTransparency.Mode active;
    private final OptionInstance<EarlyTransparency.Mode> option;
    private EarlyTransparency.Mode saved;
    private boolean rollingBack;

    public TransparencyVideoOption(Minecraft client,OptionsList list) {
        if(!client.isRunning())throw new IllegalStateException("Video preferences require initialized Minecraft");
        this.client=client;this.list=list;
        capable=EarlyTransparency.canSelectBalanced();
        active=EarlyTransparency.compact()?EarlyTransparency.Mode.BALANCED:EarlyTransparency.Mode.PRECISE;
        // A saved Balanced preference must never masquerade as an available mode in an unforced launch.
        saved=capable?TransparencyPreferenceStore.load(client.gameDirectory.toPath()):EarlyTransparency.Mode.PRECISE;
        option=new OptionInstance<>(KEY,this::tooltip,(caption,value)->{
            var text=label(value);
            if(value!=active)text=text.copy().append(" *");
            return text;
        },new OptionInstance.Enum<>(List.of(EarlyTransparency.Mode.BALANCED,EarlyTransparency.Mode.PRECISE),CODEC),
                saved,this::saveSelection);
    }

    public OptionInstance<EarlyTransparency.Mode> option() { return option; }

    public void added() {
        AbstractWidget button=list.findOption(option);
        if(button!=null)button.active=capable;
    }

    private Tooltip tooltip(EarlyTransparency.Mode value) {
        var text=Component.translatable(KEY+".tooltip",label(active));
        if(!capable)text.append("\n").append(Component.translatable(KEY+".requires_forced_vulkan"));
        else if(value!=active)text.append("\n").append(Component.translatable("options.restartRequired"));
        return Tooltip.create(text);
    }

    private void saveSelection(EarlyTransparency.Mode value) {
        if(rollingBack)return;
        if(!capable) { rollback();return; }
        try {
            TransparencyPreferenceStore.save(client.gameDirectory.toPath(),value);
            saved=value;
        } catch(IOException|SecurityException failure) {
            rollback();
            LOGGER.warn("Could not save DarkWinter transparency preference",failure);
            SystemToast.add(client.gui.toastManager(),SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable(KEY),Component.translatable(KEY+".save_failed"));
        }
    }

    private void rollback() {
        rollingBack=true;
        try{option.set(saved);}finally{rollingBack=false;}
        // Native CycleButton has already displayed the attempted value before its callback.
        list.resetOption(option);
    }

    private static Component label(EarlyTransparency.Mode mode) {
        return Component.translatable(KEY+(mode==EarlyTransparency.Mode.BALANCED?".balanced":".precise"));
    }
}
