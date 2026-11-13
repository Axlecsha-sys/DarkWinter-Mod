package com.dark.winter.interaction;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

public final class DeadwoodSounds {
    public static final Identifier ID=Identifier.fromNamespaceAndPath("darkwinter","collect_deadwood");
    public static final SoundEvent COLLECT=SoundEvent.createVariableRangeEvent(ID);
    private DeadwoodSounds() {}
    public static void initialize(){Registry.register(BuiltInRegistries.SOUND_EVENT,ID,COLLECT);}
}
