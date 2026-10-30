// Server-thread authority captured in operation WALs; recovery deliberately does not consult current player policy.
package com.dark.winter.testing;

import com.dark.winter.online.server.OnlineGameHost;
import com.dark.winter.online.world.IslandAccess.Permission;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.io.IOException;
import java.util.*;

/** Server-thread authority captured in operation WALs; recovery deliberately does not consult current player policy. */
final class TestingActors {
    private TestingActors(){}
    private record Actor(MinecraftServer server,String id,String session,String permissions){}
    private static final ThreadLocal<Actor> CURRENT=new ThreadLocal<>();
    private static final ThreadLocal<MinecraftServer> RESTORING=new ThreadLocal<>();
    static final class Scope implements AutoCloseable {
        private final Actor previous;private boolean closed;
        Scope(Actor actor){previous=CURRENT.get();if(actor==null)CURRENT.remove();else CURRENT.set(actor);}
        public void close(){if(closed)return;closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    static Scope enter(MinecraftServer server,ServerPlayer player,Set<Permission> permissions)throws IOException {
        OnlineGameHost host=OnlineGameHost.get(server);
        if(host==null)return new Scope(null);
        if(!server.isSameThread()||player==null||server.getPlayerList().getPlayer(player.getUUID())!=player)throw new IOException("Нет действительного игрока операции");
        String id=host.actor(player),scope=canonical(permissions);
        authorize(server,id,host.sessionId().toString(),scope,true);
        return new Scope(new Actor(server,id,host.sessionId().toString(),scope));
    }
    record Captured(MinecraftServer server,String id,String session,String permissions){}
    static Captured capture(){Actor actor=CURRENT.get();return actor==null?null:new Captured(actor.server(),actor.id(),actor.session(),actor.permissions());}
    static Scope enterCaptured(MinecraftServer server,Captured captured)throws IOException {
        if(captured==null){if(OnlineGameHost.get(server)!=null)throw new IOException("У фоновой операции нет подтверждённого автора");return new Scope(null);}
        if(captured.server()!=server)throw new IOException("Сервер фоновой операции изменился");
        authorize(server,captured.id(),captured.session(),captured.permissions(),true);return new Scope(new Actor(server,captured.id(),captured.session(),captured.permissions()));
    }
    static String actor(){Actor actor=CURRENT.get();return actor==null?null:actor.id();}
    static String session(){Actor actor=CURRENT.get();return actor==null?null:actor.session();}
    static String permissions(){Actor actor=CURRENT.get();return actor==null?null:actor.permissions();}
    static void validate(String actor,String session,String permissions) {
        if(actor==null){if(session!=null||permissions!=null)throw new IllegalArgumentException("Partial operation actor");return;}
        if(!actor.matches("dw1_[A-Za-z0-9_-]{43}")||session==null||!UUID.fromString(session).toString().equals(session)||permissions==null)
            throw new IllegalArgumentException("Invalid operation actor");
        if(!canonical(parse(permissions)).equals(permissions))throw new IllegalArgumentException("Noncanonical actor permissions");
    }
    private static Set<Permission> parse(String value) {
        if(value==null||value.length()>1024||value.isBlank())throw new IllegalArgumentException("Actor permission set");
        EnumSet<Permission> result=EnumSet.noneOf(Permission.class);
        for(String entry:value.split(","))if(!result.add(Permission.valueOf(entry)))throw new IllegalArgumentException("Duplicate actor permission");
        return result;
    }
    private static String canonical(Set<Permission> permissions){if(permissions==null||permissions.isEmpty())throw new IllegalArgumentException("Empty actor permissions");return permissions.stream().map(Enum::name).sorted().reduce((a,b)->a+","+b).orElseThrow();}
    static Scope enterOperation(MinecraftServer server,TestingEdits.Operation operation)throws IOException {
        return enterCaptured(server,operation.actor()==null?null:new Captured(server,operation.actor(),operation.actorSession(),operation.actorPermissions()));
    }
    /** Private engine-only before-image restoration, after WAL validation. Never grants command/actor authority. */
    static final class RestoreScope implements AutoCloseable {
        private final MinecraftServer previous;private boolean closed;
        RestoreScope(MinecraftServer server)throws IOException {
            var context=TestingServer.context(server);
            if(!server.isSameThread()||context==null||!context.edits.writing())throw new IOException("Нет активного журнала восстановления");
            previous=RESTORING.get();RESTORING.set(server);
        }
        public void close(){if(closed)return;closed=true;if(previous==null)RESTORING.remove();else RESTORING.set(previous);}
    }
    /** Managed Online shares native connections but is not a vanilla LAN workbench. A Host alone is never authority. */
    static boolean managedScope(MinecraftServer server)throws IOException {
        if(server==null||!server.isSameThread()||OnlineGameHost.get(server)==null)return false;
        if(RESTORING.get()==server){var context=TestingServer.context(server);return context!=null&&context.edits.writing();}
        Actor actor=CURRENT.get();
        if(actor==null||actor.server()!=server)return false;
        authorize(server,actor.id(),actor.session(),actor.permissions(),true);return true;
    }
    static void authorizeFresh(MinecraftServer server,TestingEdits.Operation operation)throws IOException {authorize(server,operation.actor(),operation.actorSession(),operation.actorPermissions(),true);}
    static void authorizeActive(MinecraftServer server,TestingEdits.Operation operation)throws IOException {
        if(operation.actor()!=null)authorize(server,operation.actor(),operation.actorSession(),operation.actorPermissions(),true);
    }
    private static void authorize(MinecraftServer server,String actor,String session,String permissions,boolean live)throws IOException {
        OnlineGameHost host=OnlineGameHost.get(server);
        if(host==null){if(actor!=null)throw new IOException("Сеанс автора операции завершён");return;}
        try{validate(actor,session,permissions);}catch(RuntimeException invalid){throw new IOException("Недействительное авторство операции",invalid);}
        if(actor==null||!host.sessionId().toString().equals(session)||host.epoch()!=host.access().epoch()||!host.access().can(actor,Permission.VIEW))
            throw new IOException("Полномочия или сеанс автора операции изменились");
        ServerPlayer player=server.getPlayerList().getPlayers().stream().filter(candidate->actor.equals(host.actor(candidate))).findFirst().orElse(null);
        if(live&&(player==null||!host.can(player,Permission.VIEW)))throw new IOException("Автор операции больше не находится на острове");
        for(Permission permission:parse(permissions))if(player==null||!host.can(player,permission))throw new IOException("Право или режим автора операции изменились");
    }
}
