// Owns the verified directory route and transport lifecycle for a named connection.
package com.dark.winter.client.online;

import com.dark.winter.online.identity.IdentityStore;
import com.dark.winter.online.identity.ProfileFiles;
import com.dark.winter.online.iroh.IrohEndpointService;
import com.dark.winter.online.iroh.IrohNodeRoute;
import com.dark.winter.online.iroh.IrohProtocolRouter;
import com.dark.winter.online.iroh.IrohGameTransport;
import com.dark.winter.online.node.DirectoryClient;
import com.dark.winter.online.node.DirectoryNode;
import com.dark.winter.online.rooms.RoomConnectionCode;
import com.dark.winter.online.rooms.ConnectionInfo;
import com.dark.winter.online.rooms.SavedConnections;
import com.dark.winter.online.node.NodeIceConfiguration;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit network-room lifecycle, called only on the Friends storage worker.
 * A connected room establishes the signed service route, not friendship, native game entry or host migration.
 */
final class OnlineRoomNetwork implements AutoCloseable {
    enum Code { OPERATION, OWN_ROOM }
    static final class Failure extends IOException{
        private final String phase;private final Code code;private final IrohNodeRoute.Diagnostics diagnostics;
        private final IrohNodeRoute.TransportStats transportStats;
        private final IrohEndpointService.SocketStats socketStats;
        Failure(String phase,Throwable cause){this(phase,Code.OPERATION,cause,IrohNodeRoute.Diagnostics.empty());}
        Failure(String phase,Code code,Throwable cause){this(phase,code,cause,IrohNodeRoute.Diagnostics.empty());}
        Failure(String phase,Code code,Throwable cause,IrohNodeRoute.Diagnostics diagnostics){
            this(phase,code,cause,diagnostics,IrohNodeRoute.TransportStats.empty());
        }
        Failure(String phase,Code code,Throwable cause,IrohNodeRoute.Diagnostics diagnostics,IrohNodeRoute.TransportStats transportStats){
            this(phase,code,cause,diagnostics,transportStats,IrohEndpointService.SocketStats.empty());
        }
        Failure(String phase,Code code,Throwable cause,IrohNodeRoute.Diagnostics diagnostics,IrohNodeRoute.TransportStats transportStats,IrohEndpointService.SocketStats socketStats){
            super("Room operation failed",cause);this.phase=phase;this.code=code;
            this.diagnostics=diagnostics==null?IrohNodeRoute.Diagnostics.empty():diagnostics;
            this.transportStats=transportStats==null?IrohNodeRoute.TransportStats.empty():transportStats;
            this.socketStats=socketStats==null?IrohEndpointService.SocketStats.empty():socketStats;
        }
        String phase(){return phase;}
        Code code(){return code;}
        IrohNodeRoute.Diagnostics diagnostics(){return diagnostics;}
        IrohNodeRoute.TransportStats transportStats(){return transportStats;}
        IrohEndpointService.SocketStats socketStats(){return socketStats;}
    }
    private static final Duration DEADLINE=Duration.ofSeconds(25);
    private final IrohEndpointService transport;
    private final DirectoryNode node;
    private final IrohNodeRoute route;
    private final IrohProtocolRouter router;
    private final UUID routeId;
    private final String nodeId,ownerId,code;
    private final URI endpoint;
    private SavedConnections.Entry savedConnection;
    private final AtomicBoolean closed=new AtomicBoolean();
    private OnlineRoomNetwork(IrohEndpointService transport,DirectoryNode node,IrohNodeRoute route,IrohProtocolRouter router,
                              UUID routeId,String nodeId,String ownerId,String code,URI endpoint){
        this.transport=transport;this.node=node;this.route=route;this.router=router;this.routeId=routeId;
        this.nodeId=nodeId;this.ownerId=ownerId;this.code=code;this.endpoint=endpoint;
    }
    static OnlineRoomNetwork create(IdentityStore actualIdentity)throws Exception{
        IrohEndpointService transport=null;DirectoryNode node=null;IrohNodeRoute route=null;IrohProtocolRouter router=null;
        try{
            String owner=actualIdentity.publicProof().signer().id();
            node=new DirectoryNode(ProfileFiles.directory(actualIdentity.base().resolve("room-node")),
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),null,Clock.systemUTC());
            transport=owned(IrohEndpointService.open(actualIdentity,List.of(IrohNodeRoute.ALPN,IrohGameTransport.ALPN)));
            transport.online(DEADLINE).get(27,TimeUnit.SECONDS);
            String nodeId=node.profile().id();UUID routeId=UUID.nameUUIDFromBytes(("darkwinter/network-room/1:"+owner+":"+nodeId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            router=new IrohProtocolRouter(transport);route=IrohNodeRoute.host(router,node,routeId);
            String code=RoomConnectionCode.issue(actualIdentity,routeId,nodeId,transport.publicKeyHex(),
                    Duration.ofMinutes(30),Clock.systemUTC());
            URI endpoint=URI.create("http://127.0.0.1:"+node.address().getPort()+"/");
            verifyNode(endpoint,nodeId,actualIdentity);
            return new OnlineRoomNetwork(transport,node,route,router,routeId,nodeId,owner,code,endpoint);
        }catch(Exception failure){if(route!=null)route.close();if(router!=null)router.close();if(transport!=null)transport.close();if(node!=null)node.close();throw failure;}
    }
    static OnlineRoomNetwork join(IdentityStore actualIdentity,String code)throws Exception{
        final RoomConnectionCode.VerifiedSignedRoom room;
        try{room=RoomConnectionCode.inspect(code,Clock.systemUTC());}
        catch(IOException invalid){throw new Failure("bootstrap_rejected",invalid);}
        if(room.ownerId().equals(actualIdentity.publicProof().signer().id()))
            throw new Failure("bootstrap_rejected",Code.OWN_ROOM,null);
        IrohEndpointService transport=null;IrohNodeRoute route=null;IrohProtocolRouter router=null;String phase="endpoint_open";
        try{
            transport=owned(IrohEndpointService.open(actualIdentity,List.of(IrohNodeRoute.ALPN,IrohGameTransport.ALPN)));
            phase="relay_online";transport.online(DEADLINE).get(27,TimeUnit.SECONDS);
            phase="route_open";router=new IrohProtocolRouter(transport);route=IrohNodeRoute.join(transport,room);
            phase="verify_node";verifyNode(route.endpoint(),room.nodeId(),actualIdentity);
            return new OnlineRoomNetwork(transport,null,route,router,room.routeId(),room.nodeId(),room.ownerId(),"",route.endpoint());
        }catch(Exception failure){
            String safe=phase+(route==null?"":"/"+route.diagnostic());
            IrohNodeRoute.Diagnostics diagnostics=route==null?IrohNodeRoute.Diagnostics.empty():route.diagnostics();
            IrohNodeRoute.TransportStats transportStats=route==null?IrohNodeRoute.TransportStats.empty():route.captureTransportStats();
            IrohEndpointService.SocketStats socketStats=route==null?IrohEndpointService.SocketStats.empty():route.socketStats();
            if(route!=null)route.close();if(router!=null)router.close();if(transport!=null)transport.close();
            throw new Failure(safe,Code.OPERATION,failure,diagnostics,transportStats,socketStats);
        }
    }
    /** New persistent connections have a distinct authenticated route; legacy expiring codes are unchanged. */
    static OnlineRoomNetwork createConnection(IdentityStore identity,String name,char[] password)throws Exception{return createConnection(identity,name,password,null);}
    static OnlineRoomNetwork createConnection(IdentityStore identity,String name,char[] password,IrohEndpointService reused)throws Exception{
        UUID id=UUID.randomUUID();IrohEndpointService transport=reused;DirectoryNode node=null;IrohNodeRoute route=null;IrohProtocolRouter router=null;
        try{
            ConnectionInfo.validateName(name);
            node=createConnectionNode(identity,id);
            if(transport==null)transport=owned(IrohEndpointService.open(identity,List.of(IrohNodeRoute.ALPN,IrohGameTransport.ALPN)));
            transport.online(DEADLINE).get(27,TimeUnit.SECONDS);
            ConnectionInfo.Created created=ConnectionInfo.create(identity,name,id,node.profile().id(),transport.publicKeyHex(),password);
            var descriptor=created.descriptor();router=new IrohProtocolRouter(transport);
            route=IrohNodeRoute.host(router,node,id,descriptor.routeKey());
            URI endpoint=URI.create("http://127.0.0.1:"+node.address().getPort()+"/");verifyConnectionNode(endpoint,node.profile().id(),identity);
            OnlineRoomNetwork network=new OnlineRoomNetwork(transport,node,route,router,id,node.profile().id(),identity.profile().id(),"",endpoint);
            network.savedConnection=new SavedConnections.Entry(descriptor,created.shareInfo(),created.passwordRequired(),true);return network;
        }catch(Exception failure){if(route!=null)route.close();if(router!=null)router.close();if(transport!=null)transport.close();if(node!=null)node.close();throw failure;}
        finally{if(password!=null)java.util.Arrays.fill(password,'\0');}
    }
    static OnlineRoomNetwork openConnection(IdentityStore identity,SavedConnections.Entry saved)throws Exception{return openConnection(identity,saved,null);}
    static OnlineRoomNetwork openConnection(IdentityStore identity,SavedConnections.Entry saved,IrohEndpointService reused)throws Exception{
        var descriptor=saved.descriptor();IrohEndpointService transport=reused;DirectoryNode node=null;IrohNodeRoute route=null;IrohProtocolRouter router=null;
        String phase="endpoint_open";
        try{
            if(saved.hosting()&&!descriptor.ownerId().equals(identity.profile().id()))throw new IOException("Connection owner changed");
            if(!saved.hosting()&&descriptor.ownerId().equals(identity.profile().id()))throw new Failure("bootstrap_rejected",Code.OWN_ROOM,null);
            if(saved.hosting()){
                node=existingConnectionNode(identity,descriptor.id(),descriptor.nodeId());
            }
            if(transport==null)transport=owned(IrohEndpointService.open(identity,List.of(IrohNodeRoute.ALPN,IrohGameTransport.ALPN)));
            phase="relay_online";transport.online(DEADLINE).get(27,TimeUnit.SECONDS);
            if(saved.hosting()&&!transport.publicKeyHex().equals(descriptor.transportKey()))throw new IOException("Connection endpoint identity changed");
            phase="route_open";router=new IrohProtocolRouter(transport);
            route=saved.hosting()?IrohNodeRoute.host(router,node,descriptor.id(),descriptor.routeKey())
                :IrohNodeRoute.join(transport,descriptor.id(),descriptor.nodeId(),descriptor.transportKey(),descriptor.routeKey());
            URI endpoint=saved.hosting()?URI.create("http://127.0.0.1:"+node.address().getPort()+"/"):route.endpoint();
            phase="verify_node";verifyConnectionNode(endpoint,descriptor.nodeId(),identity);
            OnlineRoomNetwork network=new OnlineRoomNetwork(transport,node,route,router,descriptor.id(),descriptor.nodeId(),descriptor.ownerId(),"",endpoint);
            network.savedConnection=saved;return network;
        }catch(Exception failure){
            if(route!=null)route.close();if(router!=null)router.close();if(transport!=null)transport.close();if(node!=null)node.close();
            throw new Failure(phase,Code.OPERATION,failure);
        }
    }
    /** New named routes may reuse the actual legacy coordinator; its identity and authority journal stay in their original directory. */
    static DirectoryNode createConnectionNode(IdentityStore identity,UUID id)throws IOException{
        java.nio.file.Path legacy=identity.base().resolve("room-node");
        return nodeAt(existingNodeIdentity(legacy)?legacy:identity.base().resolve("connections").resolve(id.toString()));
    }
    /** Reopen only the exact already existing coordinator certified by this saved descriptor. Never generate a replacement identity. */
    static DirectoryNode existingConnectionNode(IdentityStore identity,UUID id,String expectedNode)throws IOException{
        for(java.nio.file.Path candidate:List.of(identity.base().resolve("connections").resolve(id.toString()),identity.base().resolve("room-node"))){
            if(!existingNodeIdentity(candidate))continue;
            DirectoryNode opened=nodeAt(candidate);boolean matched=false;
            try{matched=expectedNode.equals(opened.profile().id());if(matched)return opened;}
            finally{if(!matched)opened.close();}
        }
        throw new IOException("The original saved connection coordinator is missing or changed");
    }
    private static boolean existingNodeIdentity(java.nio.file.Path directory)throws IOException{
        java.nio.file.Path profile=directory.resolve("node-identity").resolve("profile.json");ProfileFiles.requireOutsideWorld(profile);
        if(!java.nio.file.Files.exists(profile,java.nio.file.LinkOption.NOFOLLOW_LINKS))return false;
        if(!java.nio.file.Files.isRegularFile(profile,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid existing connection coordinator identity");
        return true;
    }
    private static DirectoryNode nodeAt(java.nio.file.Path directory)throws IOException{
        return new DirectoryNode(ProfileFiles.directory(directory),
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),null,Clock.systemUTC(),NodeIceConfiguration.empty(),true);
    }
    SavedConnections.Entry savedConnection(){return savedConnection;}
    IrohEndpointService transport(){return closed.get()?null:transport;}
    static java.util.concurrent.CompletableFuture<IrohEndpointService> openProbeTransport(IdentityStore identity){
        return IrohEndpointService.open(identity,List.of(IrohNodeRoute.ALPN,IrohGameTransport.ALPN));
    }
    /** Signed reachability/roster query that deliberately does not register the probing user as a member. */
    static JsonObject probeConnection(IdentityStore identity,SavedConnections.Entry saved,IrohEndpointService transport)throws Exception{
        var descriptor=saved.descriptor();
        try(IrohNodeRoute probe=IrohNodeRoute.join(transport,descriptor.id(),descriptor.nodeId(),descriptor.transportKey(),descriptor.routeKey());
            DirectoryClient client=new DirectoryClient(probe.endpoint(),identity,Clock.systemUTC(),descriptor.nodeId())){
            JsonObject response=client.request("connection_probe",new JsonObject()).get(20,TimeUnit.SECONDS);
            if(!client.connected()||!descriptor.nodeId().equals(client.pinnedNodeId())||!response.has("available")||!response.get("available").getAsBoolean())
                throw new IOException("Connection probe was not verified");
            return response.deepCopy();
        }
    }

    private static <T>T owned(java.util.concurrent.CompletableFuture<T> future)throws Exception{
        try{return future.get(27,TimeUnit.SECONDS);}catch(Exception stopped){future.cancel(true);throw stopped;}
    }
    /** Pre-adoption check is read-only: registration with the full real block/audience policy happens in the runtime. */
    private static void verifyConnectionNode(URI endpoint,String expectedNode,IdentityStore identity)throws Exception{
        try(DirectoryClient client=new DirectoryClient(endpoint,identity,Clock.systemUTC(),expectedNode)){
            JsonObject response=client.request("connection_probe",new JsonObject()).get(20,TimeUnit.SECONDS);
            if(!client.connected()||!expectedNode.equals(client.pinnedNodeId())||!response.has("available")||!response.get("available").isJsonPrimitive()
                    ||!response.get("available").getAsJsonPrimitive().isBoolean()||!response.get("available").getAsBoolean())
                throw new IOException("Connection Node did not independently confirm its identity");
        }
    }
    private static void verifyNode(URI endpoint,String expectedNode,IdentityStore actualIdentity)throws Exception{
        try(DirectoryClient client=new DirectoryClient(endpoint,actualIdentity,Clock.systemUTC(),expectedNode)){
            JsonObject register=new JsonObject();register.addProperty("status","online");
            register.add("audience",new JsonArray());register.add("blocked",new JsonArray());
            register.add("profileProof",com.dark.winter.online.OnlineJson.GSON.toJsonTree(actualIdentity.publicProof()));
            client.request("register",register).get(20,TimeUnit.SECONDS);
            if(!client.connected()||!expectedNode.equals(client.pinnedNodeId()))
                throw new IOException("Network room Node did not independently confirm its identity");
        }
    }
    IrohProtocolRouter router(){return router;}
    URI endpoint(){return endpoint;}
    String nodeId(){return nodeId;}
    UUID routeId(){return routeId;}
    String ownerId(){return ownerId;}
    JsonObject snapshot(){
        JsonObject result=new JsonObject();result.addProperty("active",!closed.get());result.addProperty("hosting",node!=null&&!closed.get());
        if(!closed.get())result.addProperty("ownerId",ownerId);
        result.addProperty("status",closed.get()?"Сеть закрыта.":node!=null
                ?"Сеть создана. Передайте код второму участнику; затем подтвердите заявку в друзья."
                :"Сеть подключена. Служебный узел подтвердил свою подпись; дружба и вход на остров проверяются отдельно.");
        result.addProperty("routerState",safeRouterState(router.status()));
        result.add("transportDiagnostics",route.diagnostics().toJson());
        result.add("transportPathStats",route.transportStats().toJson());
        result.add("transportSocketStats",route.socketStats().toJson());
        if(node!=null&&!closed.get())result.addProperty("code",code);
        return result;
    }
    static String safeRouterState(String value){
        if(value==null)return "unknown";
        return switch(value){case "running","failed","closed"->value;default->"unknown";};
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        route.close();router.close();transport.close();if(node!=null)try{node.close();}catch(IOException ignored){}
    }
    @Override public String toString(){return "OnlineRoomNetwork[serviceRouteOnly]";}
}
