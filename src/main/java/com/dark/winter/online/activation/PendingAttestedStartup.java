// Fail-closed bridge from a one-shot native constructor ticket to its exact attested Host.
package com.dark.winter.online.activation;

import com.dark.winter.online.authority.OnlineHostPermit;
import com.dark.winter.online.identity.IdentityStore;
import com.dark.winter.online.replica.NativeActorAliases;
import com.dark.winter.online.server.OnlineGameHost;
import java.io.IOException;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Fail-closed bridge from a one-shot native constructor ticket to its exact attested Host. */
public final class PendingAttestedStartup {
    // An unstarted constructor that fails has no SERVER_STOPPED callback. Weak keys avoid retaining that dead server.
    private static final Map<MinecraftServer,Reservation> PENDING=Collections.synchronizedMap(new WeakHashMap<>());
    private static final class Reservation {final PromotionWorkspace.Ticket ticket;boolean failed;Reservation(PromotionWorkspace.Ticket ticket){this.ticket=ticket;}}
    /** Installed on the actual native base server by the client-only player-list constructor hook. */
    public interface PlayerListHook {}
    private PendingAttestedStartup(){}
    public static void reserve(MinecraftServer server,PromotionWorkspace.Ticket ticket,UUID originalAccount)throws IOException {
        Objects.requireNonNull(server);Objects.requireNonNull(ticket);
        synchronized(PENDING){
            if(PENDING.containsKey(server)||OnlineGameHost.get(server)!=null)throw new IOException("Native startup already reserved");
            ticket.claim(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(),originalAccount);
            PENDING.put(server,new Reservation(ticket));
        }
        try{freezeIfReady(server);}catch(RuntimeException failed){fail(server);throw new IOException("Native startup freeze failed",failed);}
    }
    public static boolean guarded(MinecraftServer server){return server!=null&&PENDING.containsKey(server);}
    /** Deny/freeze only: never aliases a player and never restores a level file underneath a running server. */
    public static void rejectUnclaimed(MinecraftServer server,PromotionWorkspace.Ticket ticket)throws IOException{
        ticket.suppressLiveRollback(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
        synchronized(PENDING){Reservation reservation=PENDING.get(server);if(reservation==null){reservation=new Reservation(ticket);PENDING.put(server,reservation);}reservation.failed=true;}
        fail(server);
    }
    /** A closed, expired or canceled startup never falls back to offline owner permissions. */
    public static NativeActorAliases requireForOpen(MinecraftServer server,IdentityStore identity,UUID localPlayer,OnlineHostPermit permit)throws IOException {
        synchronized(PENDING){
            Reservation reservation=PENDING.get(server);if(reservation==null)return null;
            if(reservation.failed||permit==null||reservation.ticket.identity()!=identity)throw new IOException("Pending native authority was canceled or replaced");
            try{reservation.ticket.requireStartup(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(),permit,localPlayer,identity.profile().id());return reservation.ticket.aliases();}
            catch(IOException|RuntimeException invalid){reservation.failed=true;freezeIfReady(server);if(invalid instanceof IOException io)throw io;throw new IOException("Pending native startup invalid",invalid);}
        }
    }
    /** Caller must put the actual Host in its registry BEFORE releasing this pending guard. */
    public static void adopt(MinecraftServer server,OnlineGameHost actual,OnlineHostPermit permit)throws IOException {
        synchronized(PENDING){
            Reservation reservation=PENDING.get(server);if(reservation==null)return;
            if(reservation.failed||OnlineGameHost.get(server)!=actual||!actual.attested()||!actual.sessionId().equals(permit.nativeSessionId()))throw new IOException("Native Host did not adopt the reserved startup");
            reservation.ticket.requireStartup(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(),permit,reservation.ticket.nativePlayer(),actual.localIdentityId());
            reservation.ticket.adoptedByHost();PENDING.remove(server);
        }
    }
    public static void fail(MinecraftServer server){
        synchronized(PENDING){Reservation reservation=PENDING.get(server);if(reservation==null)return;reservation.failed=true;}
        if(server.getPlayerList()==null)return; // Constructor reservation remains latched; setPlayerList TAIL freezes before any load/tick/login.
        if(server.isSameThread())freezeIfReady(server);
        else if(!server.isStopped())try{server.executeIfPossible(()->freezeIfReady(server));}catch(java.util.concurrent.RejectedExecutionException ignored){}
    }
    private static void freezeIfReady(MinecraftServer server){if(server.getPlayerList()!=null)server.tickRateManager().setFrozen(true);}
    /** Exact MinecraftServer.setPlayerList TAIL, before IntegratedServer.initServer/loadLevel or its thread begins. */
    public static void playerListReady(MinecraftServer server){
        if(!guarded(server))return;
        try{freezeIfReady(server);}catch(RuntimeException failure){
            synchronized(PENDING){Reservation reservation=PENDING.get(server);if(reservation!=null)reservation.failed=true;}
            throw new IllegalStateException("Reserved promotion could not freeze its initialized player list",failure);
        }
    }
    public static void serverStopped(MinecraftServer server){PENDING.remove(server);}
}
