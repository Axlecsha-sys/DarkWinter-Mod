// One constructor argument for one verified newly materialized promotion copy.
package com.dark.winter.client.online;

import com.dark.winter.online.activation.*;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * One constructor argument for one verified newly materialized promotion copy.
 * Never changes Minecraft.User/account/session/license state or profile signing keys.
 * Callers prepare the ticket on a worker and hold Scope until its Host adopts the pending startup.
 */
public final class OnlineNativeAlias {
    /** Implemented only by the actual production constructor mixin, never an application supplied verified flag. */
    public interface ConstructorHook {}
    private static final AtomicReference<Scope> ARMED=new AtomicReference<>();
    private OnlineNativeAlias(){}
    public static final class Scope implements AutoCloseable {
        private final Minecraft client;private final PromotionWorkspace.Ticket ticket;private final Object originalUser;
        private WeakReference<IntegratedServer> consumed=new WeakReference<>(null);private boolean closed;
        private Scope(Minecraft client,PromotionWorkspace.Ticket ticket){this.client=client;this.ticket=ticket;originalUser=client.getUser();}
        public Path world(){return ticket.world();}
        public boolean applied(){return ticket.consumed();}
        public boolean adopted(){return ticket.adopted();}
        @Override public synchronized void close()throws IOException{
            if(closed)return;closed=true;ARMED.compareAndSet(this,null);
            IntegratedServer active=client.getSingleplayerServer();
            if(!ticket.consumed()&&active!=null&&active.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(ticket.world()))
                PendingAttestedStartup.rejectUnclaimed(active,ticket);
            IntegratedServer server=consumed.get();if(server!=null&&!ticket.adopted())PendingAttestedStartup.fail(server);
            ticket.close();
        }
    }
    /** Client thread only, no active native world. Ticket.prepareOwnerPointer() is a separate worker operation. */
    public static Scope arm(Minecraft client,PromotionWorkspace.Ticket ticket)throws IOException{
        Objects.requireNonNull(client);Objects.requireNonNull(ticket);
        if(!ConstructorHook.class.isAssignableFrom(IntegratedServer.class)
                ||!PendingAttestedStartup.PlayerListHook.class.isAssignableFrom(net.minecraft.server.MinecraftServer.class))
            throw new IOException("Production promotion constructor/player-list hooks are not installed");
        if(!client.isSameThread()||client.getSingleplayerServer()!=null||client.level!=null||OnlineClientRuntime.friends()==null||!OnlineClientRuntime.friends().isReady())
            throw new IOException("Promotion alias requires an idle actual client and its shared profile");
        if(ARMED.get()!=null)throw new IOException("Another native promotion constructor is already armed");
        ticket.arm(client.getLevelSource().getBaseDir().toAbsolutePath().normalize(),OnlineClientRuntime.friends().identity(),client.getUser().getProfileId());
        Scope scope=new Scope(client,ticket);if(!ARMED.compareAndSet(null,scope)){scope.close();throw new IOException("Another promotion was armed concurrently");}return scope;
    }
    /** Invoked only at IntegratedServer constructor's setSingleplayerProfile call. */
    public static GameProfile constructorProfile(IntegratedServer server,GameProfile original){
        Path actual=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Scope scope=ARMED.get();if(scope==null){if(PromotionWorkspace.requiresScopedOpen(actual))throw new IllegalStateException("Promoted copy requires a fresh scoped native constructor");return original;}
        if(!actual.equals(scope.ticket.world())){
            try{scope.close();}catch(IOException ignored){/* Foreign/ordinary world is never modified or given the alias. */}
            if(PromotionWorkspace.requiresScopedOpen(actual))throw new IllegalStateException("Promoted copy has no matching fresh constructor scope");
            return original;
        }
        try{
            synchronized(scope){
                if(scope.closed||scope.client.getUser()!=scope.originalUser||!scope.client.getUser().getProfileId().equals(original.id())
                        ||OnlineClientRuntime.friends()==null||OnlineClientRuntime.friends().identity()!=scope.ticket.identity())
                    throw new IOException("Actual client account/profile changed before promoted constructor");
                GameProfile selected=scope.ticket.nativePlayer().equals(original.id())?original:new GameProfile(scope.ticket.nativePlayer(),original.name(),original.properties());
                PendingAttestedStartup.reserve(server,scope.ticket,original.id());
                scope.consumed=new WeakReference<>(server);ARMED.compareAndSet(scope,null);return selected;
            }
        }catch(IOException|RuntimeException denied){
            try{scope.close();}catch(IOException cleanup){denied.addSuppressed(cleanup);}
            throw new IllegalStateException("Refused unverified, expired or canceled promoted-world constructor",denied);
        }
    }
    public static void constructorSetterFailed(IntegratedServer server){PendingAttestedStartup.fail(server);}
}
