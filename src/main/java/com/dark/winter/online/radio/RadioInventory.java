// Keeps the retired radio-inventory prototype inert for compatibility.
package com.dark.winter.online.radio;

import com.dark.winter.online.voice.VoiceRoutePolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Inert compatibility boundary for the retired item prototype; never reads or changes inventory/NBT. */
@Deprecated(forRemoval=true)
public final class RadioInventory {
    private RadioInventory(){}
    public static ItemStack selected(ServerPlayer player){return ItemStack.EMPTY;}
    public static VoiceRoutePolicy.Radio state(ItemStack stack){return VoiceRoutePolicy.Radio.absent();}
    public static void configure(ItemStack stack,Boolean enabled,Integer channel,Double volume){
        throw new UnsupportedOperationException("Physical radio controls are not part of Testing Online");
    }
    public static boolean drain(ServerPlayer player,int ticks,boolean transmitting,boolean receiving){return false;}
    public static boolean canReplaceBattery(ServerPlayer player){return false;}
    public static boolean replaceBattery(ServerPlayer player){return false;}
}
