// Encrypted Iroh route to one actual loopback DirectoryNode.
package com.dark.winter.online.iroh;

import com.dark.winter.DarkWinter;
import com.dark.winter.online.node.DirectoryNode;
import com.google.gson.JsonObject;
import com.dark.winter.online.rooms.RoomConnectionCode;
import computer.iroh.BiStream;
import computer.iroh.Connection;
import computer.iroh.ConnectionStats;
import computer.iroh.PathSnapshot;
import computer.iroh.RecvStream;
import computer.iroh.SendStream;
import kotlin.Unit;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encrypted Iroh route to one actual loopback DirectoryNode.
 * No arbitrary remote destinations, friendship consent, game admission or host authority is granted.
 * The ordinary signed DirectoryClient protocol still verifies the expected Node independently.
 */
public final class IrohNodeRoute implements AutoCloseable {
    public static final String ALPN="darkwinter/node/1";
    private static final int MAX_CHANNELS=48,BUFFER_BYTES=16384;
    private static final Duration OPERATION=Duration.ofSeconds(25);
    public enum Timing { DIAL, OPEN }
    public enum Direction { TCP_READ, QUIC_WRITE, QUIC_READ, TCP_WRITE }
    public enum Milestone { CONNECTION_ACCEPTED, STREAM_ACCEPTED, NODE_TCP_CONNECTED, TCP_READ, QUIC_WRITE, QUIC_READ, TCP_WRITE, CLOSED }
    public enum Stage { NONE, CONNECT_PEER, OPEN_STREAM, ACCEPT_STREAM, NODE_CONNECT, TCP_READ, QUIC_WRITE, QUIC_READ, TCP_WRITE, PIPE }
    public enum Kind { NONE, INVALID_ARGUMENT, TIMEOUT, CANCELLED, CAPACITY, NATIVE, ABI, IO, OTHER }
    public enum PathKind { DIRECT, RELAY, NONE, UNKNOWN }
    public enum SampleState { NONE, READY, UNAVAILABLE }
    /** A detached native sample; address-bearing PathSnapshot and native handles are never retained. */
    public record TransportStats(SampleState sample,PathKind path,long udpTxDatagrams,long udpTxBytes,
                                 long udpRxDatagrams,long udpRxBytes,long lostPackets,long lostBytes) {
        public TransportStats {
            sample=sample==null?SampleState.NONE:sample;path=path==null?PathKind.UNKNOWN:path;
            udpTxDatagrams=Math.max(0,udpTxDatagrams);udpTxBytes=Math.max(0,udpTxBytes);
            udpRxDatagrams=Math.max(0,udpRxDatagrams);udpRxBytes=Math.max(0,udpRxBytes);
            lostPackets=Math.max(0,lostPackets);lostBytes=Math.max(0,lostBytes);
        }
        public static TransportStats empty(){return new TransportStats(SampleState.NONE,PathKind.UNKNOWN,0,0,0,0,0,0);}
        static TransportStats unavailable(){return new TransportStats(SampleState.UNAVAILABLE,PathKind.UNKNOWN,0,0,0,0,0,0);}
        public JsonObject toJson(){
            JsonObject value=new JsonObject();value.addProperty("sample",sample.name().toLowerCase(Locale.ROOT));
            value.addProperty("path",path.name().toLowerCase(Locale.ROOT));value.addProperty("udp_tx_datagrams",udpTxDatagrams);
            value.addProperty("udp_tx_bytes",udpTxBytes);value.addProperty("udp_rx_datagrams",udpRxDatagrams);
            value.addProperty("udp_rx_bytes",udpRxBytes);value.addProperty("lost_packets",lostPackets);value.addProperty("lost_bytes",lostBytes);
            return value;
        }
        public String summary(){
            return "native_sample="+sample.name().toLowerCase(Locale.ROOT)+" native_path="+path.name().toLowerCase(Locale.ROOT)
                    +" udp_tx_datagrams="+udpTxDatagrams+" udp_tx_bytes="+udpTxBytes
                    +" udp_rx_datagrams="+udpRxDatagrams+" udp_rx_bytes="+udpRxBytes
                    +" lost_packets="+lostPackets+" lost_bytes="+lostBytes;
        }
    }
    static PathKind pathKind(int selected,boolean ip,boolean relay){
        if(selected==0)return PathKind.NONE;
        if(selected!=1||ip==relay)return PathKind.UNKNOWN;
        return ip?PathKind.DIRECT:PathKind.RELAY;
    }
    /** Immutable counts only; no peer, address, request, connection code or exception text is retained. */
    public record Diagnostics(long dialMs,long openMs,long connectionsAccepted,long streamsAccepted,long nodeTcpConnected,
                              long tcpReadBytes,long quicWrittenBytes,long quicReadBytes,long tcpWrittenBytes,
                              Stage failureStage,Kind failureKind) {
        public Diagnostics {
            dialMs=Math.max(0,dialMs);openMs=Math.max(0,openMs);connectionsAccepted=Math.max(0,connectionsAccepted);
            streamsAccepted=Math.max(0,streamsAccepted);nodeTcpConnected=Math.max(0,nodeTcpConnected);
            tcpReadBytes=Math.max(0,tcpReadBytes);quicWrittenBytes=Math.max(0,quicWrittenBytes);
            quicReadBytes=Math.max(0,quicReadBytes);tcpWrittenBytes=Math.max(0,tcpWrittenBytes);
            failureStage=failureStage==null?Stage.NONE:failureStage;failureKind=failureKind==null?Kind.NONE:failureKind;
        }
        public static Diagnostics empty(){return new Diagnostics(0,0,0,0,0,0,0,0,0,Stage.NONE,Kind.NONE);}
        public JsonObject toJson(){
            JsonObject value=new JsonObject();value.addProperty("dial_ms",dialMs);value.addProperty("open_ms",openMs);
            value.addProperty("conn_accepted",connectionsAccepted);value.addProperty("stream_accepted",streamsAccepted);
            value.addProperty("node_tcp_connected",nodeTcpConnected);value.addProperty("tcp_read",tcpReadBytes);
            value.addProperty("quic_write",quicWrittenBytes);value.addProperty("quic_read",quicReadBytes);
            value.addProperty("tcp_write",tcpWrittenBytes);value.addProperty("failure_stage",failureStage.name().toLowerCase(Locale.ROOT));
            value.addProperty("failure_kind",failureKind.name().toLowerCase(Locale.ROOT));return value;
        }
        public String summary(){
            return "dial_ms="+dialMs+" open_ms="+openMs+" conn_accepted="+connectionsAccepted
                    +" stream_accepted="+streamsAccepted+" node_tcp_connected="+nodeTcpConnected
                    +" tcp_read="+tcpReadBytes+" quic_write="+quicWrittenBytes+" quic_read="+quicReadBytes
                    +" tcp_write="+tcpWrittenBytes+" failure_stage="+failureStage.name().toLowerCase(Locale.ROOT)
                    +" failure_kind="+failureKind.name().toLowerCase(Locale.ROOT);
        }
    }
    /** Short synchronized accounting only; no native calls or I/O while holding this monitor. */
    static final class Telemetry {
        private final LongSupplier clock;
        private final Map<Object,Long> dial=new IdentityHashMap<>(),open=new IdentityHashMap<>();
        private final EnumSet<Milestone> logged=EnumSet.noneOf(Milestone.class);
        private long lastNanos,dialNanos,openNanos,accepted,streams,nodes,tcpRead,quicWrite,quicRead,tcpWrite;
        private Stage failureStage=Stage.NONE;private Kind failureKind=Kind.NONE;
        Telemetry(LongSupplier clock){this.clock=Objects.requireNonNull(clock);}
        private boolean clockRead;
        private long now(){long sample=clock.getAsLong();if(!clockRead||sample-lastNanos>=0){lastNanos=sample;clockRead=true;}return lastNanos;}
        private static long plus(long a,long b){return b>Long.MAX_VALUE-a?Long.MAX_VALUE:a+b;}
        private static long elapsed(long start,long end){long value=end-start;return value<0?Long.MAX_VALUE:value;}
        synchronized Object begin(Timing timing){
            Map<Object,Long> active=timing==Timing.DIAL?dial:open;if(active.size()>=MAX_CHANNELS)return null;
            Object token=new Object();active.put(token,now());return token;
        }
        synchronized void end(Timing timing,Object token){
            Map<Object,Long> active=timing==Timing.DIAL?dial:open;Long start=active.remove(token);if(start==null)return;
            long duration=elapsed(start,now());if(timing==Timing.DIAL)dialNanos=plus(dialNanos,duration);else openNanos=plus(openNanos,duration);
        }
        synchronized void acceptedConnection(){accepted=plus(accepted,1);}
        synchronized void acceptedStream(){streams=plus(streams,1);}
        synchronized void nodeConnected(){nodes=plus(nodes,1);}
        synchronized void add(Direction direction,long bytes){
            if(bytes<=0)return;
            switch(direction){
                case TCP_READ->tcpRead=plus(tcpRead,bytes);case QUIC_WRITE->quicWrite=plus(quicWrite,bytes);
                case QUIC_READ->quicRead=plus(quicRead,bytes);case TCP_WRITE->tcpWrite=plus(tcpWrite,bytes);
            }
        }
        synchronized boolean permitLog(Milestone milestone){return milestone!=null&&logged.add(milestone);}
        synchronized void fail(Stage stage,Throwable failure){
            if(failureKind!=Kind.NONE)return;
            List<Throwable> chain=new ArrayList<>(12);Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
            for(Throwable current=failure;current!=null&&chain.size()<12&&seen.add(current);){
                chain.add(current);try{current=current.getCause();}catch(Throwable unreadable){break;}
            }
            Kind kind=Kind.OTHER;
            for(Throwable current:chain)if(current instanceof IrohAsync.Failure known){
                kind=switch(known.code()){
                    case INVALID_ARGUMENT->Kind.INVALID_ARGUMENT;case TIMEOUT->Kind.TIMEOUT;case CANCELLED->Kind.CANCELLED;
                    case CAPACITY->Kind.CAPACITY;case NATIVE->Kind.NATIVE;case ABI->Kind.ABI;
                };break;
            }
            if(kind==Kind.OTHER)for(Throwable current:chain){
                if(current instanceof TimeoutException||current instanceof SocketTimeoutException||current instanceof java.net.http.HttpTimeoutException){kind=Kind.TIMEOUT;break;}
                if(current instanceof CancellationException||current instanceof InterruptedException){kind=Kind.CANCELLED;break;}
            }
            if(kind==Kind.OTHER)for(Throwable current:chain){
                if(current instanceof IllegalArgumentException){kind=Kind.INVALID_ARGUMENT;break;}
                if(current instanceof IOException){kind=Kind.IO;break;}
            }
            failureStage=stage==null?Stage.NONE:stage;failureKind=kind;
        }
        synchronized Diagnostics snapshot(){
            long at=now(),d=dialNanos,o=openNanos;
            for(long start:dial.values())d=plus(d,elapsed(start,at));
            for(long start:open.values())o=plus(o,elapsed(start,at));
            return new Diagnostics(d/1_000_000,o/1_000_000,accepted,streams,nodes,tcpRead,quicWrite,quicRead,tcpWrite,failureStage,failureKind);
        }
    }
    private final Telemetry telemetry=new Telemetry(System::nanoTime);
    private volatile TransportStats transportStats=TransportStats.empty();
    private volatile IrohEndpointService.SocketStats socketStats=IrohEndpointService.SocketStats.empty();
    public Diagnostics diagnostics(){return telemetry.snapshot();}
    /** Cache-only: safe for runtime snapshots; no native work occurs here. */
    public TransportStats transportStats(){return transportStats;}
    public IrohEndpointService.SocketStats socketStats(){return socketStats;}
    private TransportStats sampleConnection(Connection connection){
        socketStats=transport.sampleSocketStatsOnWorker();
        if(connection==null)return transportStats;
        try{
            ConnectionStats stats=connection.stats();
            List<PathSnapshot> paths=connection.paths();int selected=0;boolean ip=false,relay=false;
            PathKind kind=PathKind.UNKNOWN;
            if(paths!=null&&paths.size()<=64){
                for(PathSnapshot path:paths)if(path.isSelected()){selected++;ip=path.isIp();relay=path.isRelay();}
                kind=pathKind(selected,ip,relay);
            }
            // Never read path identifiers, remote addresses, or a PathSnapshot's generated toString().
            transportStats=new TransportStats(SampleState.READY,kind,stats.getUdpTxDatagrams(),stats.getUdpTxBytes(),
                    stats.getUdpRxDatagrams(),stats.getUdpRxBytes(),stats.getLostPackets(),stats.getLostBytes());
        }catch(Throwable unavailable){transportStats=TransportStats.unavailable();}
        return transportStats;
    }
    /** Worker-only failure/close sample before connection handles are released. Never call from a UI snapshot. */
    public TransportStats captureTransportStats(){
        boolean sampled=false;
        for(Connection connection:connections){sampled=true;TransportStats value=sampleConnection(connection);if(value.sample()==SampleState.READY)return value;}
        if(!sampled)socketStats=transport.sampleSocketStatsOnWorker();
        return transportStats;
    }
    private void milestone(Milestone milestone,Connection connection){
        if(!telemetry.permitLog(milestone))return;
        if(milestone!=Milestone.CLOSED)sampleConnection(connection);
        if(destination!=null)
            DarkWinter.LOGGER.info("Online Node route milestone={} {} {} {}",milestone.name().toLowerCase(Locale.ROOT),diagnostics().summary(),transportStats.summary(),socketStats.summary());
    }
    private final IrohEndpointService transport;
    private final UUID routeId;
    private final String nodeId,peerKey;
    private final byte[] routeSecret;
    private final InetSocketAddress destination;
    private final ServerSocket listener;
    private AutoCloseable registration;
    private final ExecutorService io=Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("dw-iroh-node-",0).factory());
    private final Set<Connection> connections=ConcurrentHashMap.newKeySet();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final Semaphore capacity=new Semaphore(MAX_CHANNELS);
    private final AtomicBoolean closed=new AtomicBoolean();
    private volatile String diagnostic="idle";
    public String diagnostic(){return diagnostic;}
    private IrohNodeRoute(IrohEndpointService transport,UUID routeId,String nodeId,String peerKey,
                          InetSocketAddress destination,ServerSocket listener,byte[] routeSecret){
        if(routeSecret!=null&&routeSecret.length!=ConnectionRouteHandshake.SECRET_BYTES)throw new IllegalArgumentException("Invalid connection credential");
        this.transport=Objects.requireNonNull(transport);this.routeId=Objects.requireNonNull(routeId);
        this.nodeId=Objects.requireNonNull(nodeId);this.peerKey=peerKey;this.destination=destination;this.listener=listener;
        this.routeSecret=routeSecret==null?null:routeSecret.clone();
    }
    /** The caller owns the Node and endpoint; closing this route only closes its own forwarding channels. */
    public static IrohNodeRoute host(IrohProtocolRouter router,DirectoryNode actualNode,UUID routeId)throws IOException{
        return host(router,actualNode,routeId,null);
    }
    /** Persistent connections authenticate their capability before any local Node socket is opened. */
    public static IrohNodeRoute host(IrohProtocolRouter router,DirectoryNode actualNode,UUID routeId,byte[] routeSecret)throws IOException{
        InetSocketAddress address=Objects.requireNonNull(actualNode).address();
        if(address.getAddress()==null||!address.getAddress().isLoopbackAddress()||address.getPort()<1)
            throw new IOException("A real loopback Node is required");
        IrohNodeRoute route=new IrohNodeRoute(router.service(),routeId,actualNode.profile().id(),"",address,null,routeSecret);
        route.registration=router.register(ALPN,connection->{
            if(route.closed.get()||!route.capacity.tryAcquire()){end(connection);return;}
            route.connections.add(connection);route.telemetry.acceptedConnection();route.milestone(Milestone.CONNECTION_ACCEPTED,connection);
            try{route.io.execute(()->route.serveRemote(connection));}
            catch(RejectedExecutionException stopped){route.connections.remove(connection);route.capacity.release();end(connection);}
        });
        return route;
    }
    /** Only the verified signed bootstrap supplies the exact destination key and expected Node ID. */
    public static IrohNodeRoute join(IrohEndpointService transport,RoomConnectionCode.VerifiedSignedRoom room)throws IOException{
        Objects.requireNonNull(room);
        return join(transport,room.routeId(),room.nodeId(),room.hostTransportKey(),null);
    }
    /** Called only after a persistent, owner-signed descriptor has been verified/decrypted. */
    public static IrohNodeRoute join(IrohEndpointService transport,UUID routeId,String nodeId,String hostTransportKey,byte[] routeSecret)throws IOException{
        if(routeId==null||nodeId==null||!nodeId.matches("dw1_[A-Za-z0-9_-]{43}")
                ||hostTransportKey==null||!hostTransportKey.matches("[0-9a-f]{64}")
                ||routeSecret!=null&&routeSecret.length!=ConnectionRouteHandshake.SECRET_BYTES)throw new IOException("Invalid connection destination");
        ServerSocket listener=new ServerSocket();listener.setReuseAddress(false);
        try{
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),16);
            IrohNodeRoute route=new IrohNodeRoute(transport,routeId,nodeId,hostTransportKey,null,listener,routeSecret);
            route.io.execute(route::acceptLocal);return route;
        }catch(Throwable failure){listener.close();if(failure instanceof IOException io)throw io;throw new IOException("Local route unavailable");}
    }
    public UUID routeId(){return routeId;}
    public String nodeId(){return nodeId;}
    public URI endpoint(){
        if(listener==null)throw new IllegalStateException("The host uses its actual Node address");
        return URI.create("http://127.0.0.1:"+listener.getLocalPort()+"/");
    }
    private void acceptLocal(){
        while(!closed.get()){
            Socket socket=null;
            try{
                socket=listener.accept();
                if(closed.get()||!socket.getInetAddress().isLoopbackAddress()||!capacity.tryAcquire()){socket.close();continue;}
                Socket actual=socket;sockets.add(actual);
                try{io.execute(()->serveLocal(actual));}catch(RejectedExecutionException stopped){sockets.remove(actual);capacity.release();actual.close();}
            }catch(IOException stopped){if(socket!=null)try{socket.close();}catch(IOException ignored){}if(!closed.get())close();return;}
        }
    }
    private void serveRemote(Connection connection){
        Stage stage=Stage.ACCEPT_STREAM;
        try{
            if(!java.util.Arrays.equals(connection.alpn(),ALPN.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                throw new IOException("Unexpected Node protocol");
            try(BiStream stream=IrohAsync.<BiStream>call(OPERATION,connection::acceptBi).get(27,TimeUnit.SECONDS);
                SendStream send=stream.send();RecvStream receive=stream.recv();Socket socket=new Socket()){
                telemetry.acceptedStream();milestone(Milestone.STREAM_ACCEPTED,connection);
                authenticate(send,receive,true);
                sockets.add(socket);stage=Stage.NODE_CONNECT;
                try{socket.connect(destination,5000);telemetry.nodeConnected();milestone(Milestone.NODE_TCP_CONNECTED,connection);stage=Stage.PIPE;pipe(socket,send,receive,connection);}
                finally{sockets.remove(socket);}
            }
        }catch(Exception failure){telemetry.fail(stage,failure);sampleConnection(connection);/* Never echo native messages or wire data. */}
        finally{connections.remove(connection);end(connection);capacity.release();}
    }
    private void serveLocal(Socket socket){
        Connection connection=null;Stage stage=Stage.CONNECT_PEER;
        try{
            if(closed.get())return;
            diagnostic="connecting_peer";
            Object dial=telemetry.begin(Timing.DIAL);
            try{connection=transport.connect(peerKey,ALPN,OPERATION).get(27,TimeUnit.SECONDS);}
            finally{telemetry.end(Timing.DIAL,dial);}
            sampleConnection(connection);
            diagnostic="opening_stream";stage=Stage.OPEN_STREAM;
            connections.add(connection);
            Object open=telemetry.begin(Timing.OPEN);BiStream opened;
            try{opened=IrohAsync.<BiStream>call(OPERATION,connection::openBi).get(27,TimeUnit.SECONDS);}
            finally{telemetry.end(Timing.OPEN,open);}
            try(BiStream stream=opened;SendStream send=stream.send();RecvStream receive=stream.recv()){
                authenticate(send,receive,false);
                diagnostic="piping_node";stage=Stage.PIPE;pipe(socket,send,receive,connection);
            }
        }catch(Exception failure){telemetry.fail(stage,failure);sampleConnection(connection);diagnostic=diagnostic+":"+failure.getClass().getSimpleName();/* Never expose exception messages. */}
        finally{
            sockets.remove(socket);try{socket.close();}catch(IOException ignored){}
            if(connection!=null){connections.remove(connection);end(connection);}capacity.release();
        }
    }
    private void authenticate(SendStream send,RecvStream receive,boolean accepting)throws Exception{
        if(routeSecret==null)return;
        if(closed.get())throw new IOException("Route closed");
        if(accepting){
            byte[] proof=readHandshake(receive,ConnectionRouteHandshake.REQUEST_BYTES);
            boolean accepted;
            try{accepted=ConnectionRouteHandshake.accepts(routeId,routeSecret,proof);}
            finally{Arrays.fill(proof,(byte)0);}
            if(!accepted)throw new IOException("Connection credential rejected");
            byte[] reply=ConnectionRouteHandshake.acknowledgement(routeId);
            IrohAsync.<Unit>call(OPERATION,c->send.writeAll(reply,c)).get(27,TimeUnit.SECONDS);
        }else{
            byte[] proof=ConnectionRouteHandshake.request(routeId,routeSecret);
            try{IrohAsync.<Unit>call(OPERATION,c->send.writeAll(proof,c)).get(27,TimeUnit.SECONDS);}
            finally{Arrays.fill(proof,(byte)0);}
            if(!ConnectionRouteHandshake.confirms(routeId,readHandshake(receive,ConnectionRouteHandshake.ACK_BYTES)))
                throw new IOException("Connection acknowledgement rejected");
        }
    }
    /** One total deadline and exact byte bound; the header is never forwarded into HTTP. */
    private byte[] readHandshake(RecvStream receive,int size)throws Exception{
        byte[] result=new byte[size];int offset=0;long started=System.nanoTime(),budget=OPERATION.toNanos();
        try{
            while(offset<size){
                long left=budget-(System.nanoTime()-started);
                if(left<=0||closed.get())throw new IOException("Connection handshake deadline");
                byte[] part=IrohAsync.read(receive,size-offset,Duration.ofNanos(left)).get(left,TimeUnit.NANOSECONDS);
                if(part.length==0||part.length>size-offset)throw new IOException("Incomplete connection handshake");
                System.arraycopy(part,0,result,offset,part.length);offset+=part.length;Arrays.fill(part,(byte)0);
            }
            return result;
        }catch(Exception failure){Arrays.fill(result,(byte)0);throw failure;}
    }
    private void pipe(Socket socket,SendStream send,RecvStream receive,Connection connection)throws Exception{
        if(closed.get())throw new IOException("Route closed");
        socket.setTcpNoDelay(true);socket.setSoTimeout(30000);
        {
            CompletableFuture<Void> upstream=new CompletableFuture<>();
            io.execute(()->{
                Stage stage=Stage.TCP_READ;
                try{
                    byte[] buffer=new byte[BUFFER_BYTES];int count;
                    while((count=socket.getInputStream().read(buffer))>=0){
                        if(count==0)continue;
                        telemetry.add(Direction.TCP_READ,count);milestone(Milestone.TCP_READ,connection);
                        byte[] copy=java.util.Arrays.copyOf(buffer,count);stage=Stage.QUIC_WRITE;
                        IrohAsync.<Unit>call(OPERATION,c->send.writeAll(copy,c)).get(27,TimeUnit.SECONDS);
                        telemetry.add(Direction.QUIC_WRITE,count);milestone(Milestone.QUIC_WRITE,connection);stage=Stage.TCP_READ;
                    }
                    stage=Stage.QUIC_WRITE;IrohAsync.<Unit>call(OPERATION,send::finish).get(27,TimeUnit.SECONDS);
                    upstream.complete(null);
                }catch(Exception failure){telemetry.fail(stage,failure);upstream.completeExceptionally(new IOException("Node stream closed"));try{socket.close();}catch(IOException ignored){}}
            });
            Stage stage=Stage.QUIC_READ;
            try{
                while(!closed.get()){
                    stage=Stage.QUIC_READ;byte[] bytes=IrohAsync.read(receive,BUFFER_BYTES,OPERATION).get(27,TimeUnit.SECONDS);
                    if(bytes.length==0){socket.shutdownOutput();break;}
                    telemetry.add(Direction.QUIC_READ,bytes.length);milestone(Milestone.QUIC_READ,connection);stage=Stage.TCP_WRITE;
                    socket.getOutputStream().write(bytes);telemetry.add(Direction.TCP_WRITE,bytes.length);milestone(Milestone.TCP_WRITE,connection);
                }
                stage=Stage.PIPE;upstream.get(30,TimeUnit.SECONDS);
            }catch(Exception failure){telemetry.fail(stage,failure);throw failure;}
        }
    }
    private static void end(Connection connection){
        if(connection==null)return;
        try{connection.close(0,new byte[0]);}catch(Exception ignored){}
        try{connection.close();}catch(Exception ignored){}
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        captureTransportStats();
        if(registration!=null)try{registration.close();}catch(Exception ignored){}
        if(listener!=null)try{listener.close();}catch(IOException ignored){}
        for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}
        for(Connection connection:connections)end(connection);
        io.shutdownNow();if(routeSecret!=null)Arrays.fill(routeSecret,(byte)0);milestone(Milestone.CLOSED,null);
    }
    @Override public String toString(){return "IrohNodeRoute[encryptedNodeTransport]";}
}
