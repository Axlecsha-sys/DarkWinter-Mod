// Registers common online packets and authenticated native admission handlers.
package com.dark.winter.online.server;

import com.dark.winter.mixin.online.OnlineCommonListenerAccess;
import com.dark.winter.online.network.OnlinePayload;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.server.MinecraftServer;

/** Explicit common registration. The application initializer calls init exactly once. */
public final class OnlineNetworking {
    private static boolean initialized;
    private OnlineNetworking(){}
    public static synchronized void init() {
        if(initialized)return;initialized=true;
        PayloadTypeRegistry.serverboundConfiguration().register(OnlinePayload.TYPE,OnlinePayload.CODEC);
        PayloadTypeRegistry.clientboundConfiguration().register(OnlinePayload.TYPE,OnlinePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(OnlinePayload.TYPE,OnlinePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OnlinePayload.TYPE,OnlinePayload.CODEC);
        ServerConfigurationNetworking.registerGlobalReceiver(OnlinePayload.TYPE,(packet,context)->{
            OnlineGameHost host=OnlineGameHost.get(context.server());
            if(host!=null)host.configurationPayload(context.packetListener(),packet);
        });
        ServerPlayNetworking.registerGlobalReceiver(OnlinePayload.TYPE,(packet,context)->{
            OnlineGameHost host=OnlineGameHost.get(context.server());
            if(host!=null)host.playPayload(context.player(),packet);
        });
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{
            OnlineGameHost host=OnlineGameHost.get(server);if(host!=null)host.joined(handler.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{
            OnlineGameHost host=OnlineGameHost.get(server);if(host!=null)host.disconnected(handler.player);
        });
        ServerConfigurationConnectionEvents.DISCONNECT.register((handler,server)->{
            OnlineGameHost host=OnlineGameHost.get(server);
            if(host!=null&&((OnlineCommonListenerAccess)handler).darkwinter$connection().getRemoteAddress() instanceof java.net.InetSocketAddress source)
                host.unregisterIngress(source);
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{OnlineGameHost host=OnlineGameHost.get(server);if(host!=null)host.tick();});
        ServerLifecycleEvents.SERVER_STOPPING.register(OnlineNetworking::close);
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{close(server);OnlineGameHost.serverStopped(server);});
    }
    private static void close(MinecraftServer server){OnlineGameHost host=OnlineGameHost.get(server);if(host!=null)host.close();}
}
