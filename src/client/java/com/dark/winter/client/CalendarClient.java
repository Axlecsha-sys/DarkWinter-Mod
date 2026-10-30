// Tracks the synchronized calendar epoch for the current client connection.
package com.dark.winter.client;

import com.dark.winter.time.CalendarEpoch;
import com.dark.winter.time.CalendarPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Epoch belongs to the connection/save, not to the player or a dimension instance. */
public final class CalendarClient {
    private static CalendarEpoch current;
    private CalendarClient() { }
    public static void init() {
        ClientPlayConnectionEvents.INIT.register((handler,client) -> current=null);
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client) -> current=null);
        ClientPlayNetworking.registerGlobalReceiver(CalendarPayload.TYPE,(payload,context) -> current=payload.epoch());
    }
    public static CalendarEpoch modern() { return current!=null && current.modern()?current:null; }
}
