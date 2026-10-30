// Explicit opt-in framing over one already-authenticated reliable transport link.
package com.dark.winter.online.transport;

import java.io.*;
import java.nio.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Explicit opt-in framing over one already-authenticated reliable transport link. Owns its sole reader/writer.
 * Does not authenticate a peer, open sockets, use Friends mailboxes or parse Minecraft/replica payloads.
 * Per-lane credits prevent a stalled standby consumer from exhausting the native game lane.
 * Physical SCTP ordering/retransmission still has head-of-line blocking; this is not a latency guarantee.
 * Future continuations may run on a multiplex worker; use an explicit application executor.
 */
public final class WebRtcMultiplex implements AutoCloseable {
    private static final int MAGIC=0x44574D58,VERSION=1,HEADER=12,MAX_FRAME=8192,MAX_WRITES=256;
    private static final int HELLO=1,DATA=2,WINDOW=3,FIN=4,FIN_ACK=5,RESET=6;
    public record Options(int frameBytes,int nativeWindow,int replicaWindow,int nativeQueued,int replicaQueued,Duration timeout) {
        public Options {
            if(frameBytes<1024||frameBytes>MAX_FRAME||nativeWindow<frameBytes||nativeWindow>1024*1024
                    ||replicaWindow<frameBytes||replicaWindow>1024*1024||nativeQueued<frameBytes||nativeQueued>4*1024*1024
                    ||replicaQueued<frameBytes||replicaQueued>4*1024*1024||timeout==null
                    ||timeout.compareTo(Duration.ofSeconds(1))<0||timeout.compareTo(Duration.ofSeconds(60))>0)
                throw new IllegalArgumentException("Multiplex limits");
        }
        public static Options defaults(){return new Options(8192,128*1024,128*1024,256*1024,256*1024,Duration.ofSeconds(30));}
    }
    public record LaneStats(long sentBytes,long receivedBytes,long queuedWriteBytes,long queuedReceiveBytes,
                            long peakWriteBytes,long peakReceiveBytes,int waitingReaders,boolean ended,
                            boolean outputEofAcknowledged,boolean inputEofSeen,boolean inputEofConsumed,boolean inputEofAcknowledged,
                            int sendCredit,int receiveAllowance,int pendingCredits,int queuedWrites) {}
    public record Stats(boolean opened,boolean closing,String code,LaneStats nativeLane,LaneStats replicaLane) {}
    private static final class Write {
        final byte[] bytes;final CompletableFuture<Void> result=new CompletableFuture<>();int offset;
        Write(byte[] bytes){this.bytes=bytes.clone();}
    }
    private record Frame(int lane,int type,byte[] data,Write write) {}
    private final ReliableTransportLink link;
    private final Options options;
    private final Object monitor=new Object();
    private final Lane nativeLane,replicaLane;
    private final CompletableFuture<Void> opened=new CompletableFuture<>(),closed=new CompletableFuture<>();
    private final AtomicBoolean closing=new AtomicBoolean();
    private final AtomicInteger workers=new AtomicInteger(2);
    private final long started=System.nanoTime();
    private final Thread reader,writer;
    private boolean helloSent,helloReceived;
    private int frameBytes,nativeTurns=4,controlTurns,controlNext=1;
    private volatile String code="";
    private WebRtcMultiplex(ReliableTransportLink link,Options options) {
        this.link=Objects.requireNonNull(link);this.options=Objects.requireNonNull(options);frameBytes=options.frameBytes();
        nativeLane=new Lane(1,options.nativeWindow(),options.nativeQueued());replicaLane=new Lane(2,options.replicaWindow(),options.replicaQueued());
        reader=Thread.ofVirtual().name("DarkWinter-mux-read").unstarted(this::readLoop);
        writer=Thread.ofVirtual().name("DarkWinter-mux-write").unstarted(this::writeLoop);
        link.closed().whenComplete((unused,failure)->abort("LINK_CLOSED"));
        boolean readerStarted=false,writerStarted=false;
        try {reader.start();readerStarted=true;writer.start();writerStarted=true;}
        catch(RuntimeException|Error failure) {
            abort("WORKER_START_FAILED");if(!readerStarted)workerEnded();if(!writerStarted)workerEnded();throw failure;
        }
    }
    public static WebRtcMultiplex open(WebRtcLink authenticatedLink){return open(authenticatedLink,Options.defaults());}
    public static WebRtcMultiplex open(WebRtcLink authenticatedLink,Options options){return new WebRtcMultiplex(authenticatedLink,options);}
    /** Alternative transport, after the caller independently authenticates its exact session and peer. */
    public static WebRtcMultiplex open(ReliableTransportLink authenticatedLink){return open(authenticatedLink,Options.defaults());}
    public static WebRtcMultiplex open(ReliableTransportLink authenticatedLink,Options options){return new WebRtcMultiplex(authenticatedLink,options);}

    public WebRtcByteStream nativeStream(){return nativeLane;}
    public WebRtcByteStream replicaStream(){return replicaLane;}
    public CompletableFuture<Void> opened(){return opened.copy();}
    public CompletableFuture<Void> closed(){return closed.copy();}
    public Stats stats(){synchronized(monitor){return new Stats(opened.isDone()&&!opened.isCompletedExceptionally(),closing.get(),code,nativeLane.stats(),replicaLane.stats());}}
    private final class Lane implements WebRtcByteStream {
        final int id,window,maximum;
        final ArrayDeque<Write> writes=new ArrayDeque<>();
        final byte[] inbound;
        int receiveHead,receiveTail;
        final ArrayDeque<CompletableFuture<byte[]>> readers=new ArrayDeque<>();
        final CompletableFuture<Void> outputFinished=new CompletableFuture<>(),endedFuture=new CompletableFuture<>();
        long queuedWrite,queuedReceive,peakWrite,peakReceive,sent,receivedBytes,finStarted,lastProgress=System.nanoTime();
        int peerWindow,credit,allowance,pendingCredit;
        boolean outputClosing,localFinSent,localFinAck,remoteFin,remoteConsumed,finAckSent,resetPending,ended,gracefulRequested;
        Lane(int id,int window,int maximum){this.id=id;this.window=window;this.maximum=maximum;allowance=window;inbound=new byte[window];}
        @Override public CompletableFuture<Void> opened(){return WebRtcMultiplex.this.opened();}
        @Override public CompletableFuture<Void> closed(){return endedFuture.copy();}
        @Override public CompletableFuture<Void> send(byte[] bytes){
            Objects.requireNonNull(bytes);
            synchronized(monitor) {
                if(ended||closing.get()||outputClosing)return failed("LANE_CLOSED");
                if(bytes.length==0)return CompletableFuture.completedFuture(null);
                if(bytes.length>maximum||queuedWrite>maximum-bytes.length||writes.size()>=MAX_WRITES)return failed("LANE_CAPACITY");
                Write write=new Write(bytes);if(writes.isEmpty())lastProgress=System.nanoTime();writes.add(write);queuedWrite+=bytes.length;peakWrite=Math.max(peakWrite,queuedWrite);
                monitor.notifyAll();return write.result.copy();
            }
        }
        @Override public CompletableFuture<byte[]> read(){
            synchronized(monitor) {
                if(ended||closing.get())return failed("LANE_CLOSED");
                if(queuedReceive>0){byte[] bytes=take();consume(bytes.length);return CompletableFuture.completedFuture(bytes);}
                if(remoteFin){remoteConsumed=true;monitor.notifyAll();return CompletableFuture.completedFuture(null);}
                if(readers.size()>=4)return failed("READER_CAPACITY");
                CompletableFuture<byte[]> result=new CompletableFuture<>();readers.add(result);
                result.whenComplete((bytes,error)->{if(result.isCancelled())synchronized(monitor){readers.remove(result);monitor.notifyAll();}});
                return result;
            }
        }
        // A fixed byte ring avoids an unbounded number of tiny receive-frame objects.
        void append(byte[] bytes) {
            int first=Math.min(bytes.length,window-receiveTail);
            System.arraycopy(bytes,0,inbound,receiveTail,first);
            System.arraycopy(bytes,first,inbound,0,bytes.length-first);
            receiveTail=(receiveTail+bytes.length)%window;
        }
        byte[] take() {
            int size=Math.min(frameBytes,(int)queuedReceive),first=Math.min(size,window-receiveHead);
            byte[] bytes=new byte[size];System.arraycopy(inbound,receiveHead,bytes,0,first);
            System.arraycopy(inbound,0,bytes,first,size-first);receiveHead=(receiveHead+size)%window;return bytes;
        }
        void consume(int bytes){queuedReceive-=bytes;pendingCredit+=bytes;monitor.notifyAll();}
        @Override public CompletableFuture<Void> finishOutput(){
            synchronized(monitor) {
                if(ended||closing.get())return failed("LANE_CLOSED");
                if(!outputClosing){outputClosing=true;finStarted=System.nanoTime();monitor.notifyAll();}
                return outputFinished.copy();
            }
        }
        @Override public CompletableFuture<Void> closeGracefully(){
            synchronized(monitor) {
                if(ended)return endedFuture.copy();
                if(!localFinAck||!remoteConsumed)return failed("EOF_NOT_ACKNOWLEDGED");
                gracefulRequested=true;finishGracefully();return endedFuture.copy();
            }
        }
        @Override public void close(){synchronized(monitor){end(true,"LANE_RESET");}}
        void finishGracefully(){if(gracefulRequested&&localFinAck&&remoteConsumed&&finAckSent)end(false,"GRACEFUL_EOF");}
        void end(boolean reset,String reason) {
            if(ended)return;ended=true;resetPending=reset;
            IOException failure=error(reason);
            for(Write write:writes)write.result.completeExceptionally(failure);writes.clear();queuedWrite=0;
            for(CompletableFuture<byte[]> pending:readers)pending.completeExceptionally(failure);readers.clear();
            Arrays.fill(inbound,(byte)0);receiveHead=receiveTail=0;queuedReceive=0;pendingCredit=0;outputFinished.completeExceptionally(failure);
            endedFuture.complete(null);monitor.notifyAll();
        }
        LaneStats stats(){return new LaneStats(sent,receivedBytes,queuedWrite,queuedReceive,peakWrite,peakReceive,readers.size(),ended,localFinAck,remoteFin,remoteConsumed,finAckSent,credit,allowance,pendingCredit,writes.size());}
    }
    private Lane lane(int id)throws IOException {if(id==1)return nativeLane;if(id==2)return replicaLane;throw error("UNKNOWN_LANE");}
    private static IOException error(String code){return new IOException("WebRTC multiplex: "+code);}
    private static <T>CompletableFuture<T> failed(String code){return CompletableFuture.failedFuture(error(code));}
    private void readLoop(){
        try {
            link.opened().get(options.timeout().toMillis(),TimeUnit.MILLISECONDS);
            Raw input=new Raw();
            while(!closing.get()) {
                byte[] header=input.exact(HEADER);ByteBuffer bytes=ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
                int magic=bytes.getInt(),version=Byte.toUnsignedInt(bytes.get()),id=Byte.toUnsignedInt(bytes.get()),type=Byte.toUnsignedInt(bytes.get()),flags=Byte.toUnsignedInt(bytes.get()),size=bytes.getInt();
                if(magic!=MAGIC||version!=VERSION||flags!=0||size<0||size>MAX_FRAME)throw error("INVALID_FRAME");
                byte[] data=input.exact(size);receive(id,type,data);
            }
        }catch(Throwable failure){if(!closing.get())abort("READ_OR_PROTOCOL_FAILED");}
        finally{workerEnded();}
    }
    private final class Raw {
        byte[] bytes=new byte[0];int at;
        byte[] exact(int length)throws Exception {
            byte[] result=new byte[length];int copied=0;
            while(copied<length) {
                if(closing.get())throw error("CLOSED");
                if(at==bytes.length) {
                    CompletableFuture<byte[]> future=link.read();
                    if(!opened.isDone()){
                        long remaining=options.timeout().toNanos()-(System.nanoTime()-started);
                        if(remaining<=0)throw error("HELLO_TIMEOUT");
                        bytes=future.get(remaining,TimeUnit.NANOSECONDS);
                    }else bytes=future.get();
                    at=0;if(bytes==null)throw error("UNEXPECTED_PHYSICAL_EOF");
                }
                int count=Math.min(length-copied,bytes.length-at);
                if(count==0)throw error("EMPTY_PHYSICAL_SEGMENT");
                System.arraycopy(bytes,at,result,copied,count);copied+=count;at+=count;
            }
            return result;
        }
    }
    private void receive(int id,int type,byte[] data)throws IOException {
        synchronized(monitor) {
            if(type==HELLO) {
                if(id!=0||helloReceived||data.length!=12)throw error("INVALID_HELLO");
                ByteBuffer hello=ByteBuffer.wrap(data);int size=hello.getInt(),nativeWindow=hello.getInt(),replicaWindow=hello.getInt();
                if(size<1024||size>MAX_FRAME||nativeWindow<size||nativeWindow>1024*1024||replicaWindow<size||replicaWindow>1024*1024)throw error("HELLO_LIMITS");
                nativeLane.peerWindow=nativeLane.credit=nativeWindow;replicaLane.peerWindow=replicaLane.credit=replicaWindow;
                frameBytes=Math.min(frameBytes,size);helloReceived=true;ready();monitor.notifyAll();return;
            }
            if(!helloReceived)throw error("HELLO_REQUIRED");
            Lane lane=lane(id);
            if(type==RESET) {
                if(data.length!=0)throw error("INVALID_RESET");lane.end(false,"REMOTE_RESET");return;
            }
            // RESET can cross a frame already in flight in the opposite direction. Discard only
            // the peer's remaining advertised credit; never replenish an ended lane.
            if(lane.ended) {
                if(type==DATA) {
                    if(data.length<1||data.length>frameBytes||data.length>lane.allowance)throw error("CLOSED_LANE_CREDIT");
                    lane.allowance-=data.length;
                }else if(type==WINDOW) {
                    if(data.length!=4)throw error("INVALID_WINDOW");
                    int amount=ByteBuffer.wrap(data).getInt();
                    if(amount<1||amount>lane.peerWindow-lane.credit)throw error("INVALID_CREDIT");
                    lane.credit+=amount;
                }else if((type!=FIN&&type!=FIN_ACK)||data.length!=0)throw error("CLOSED_LANE_FRAME");
                return;
            }
            if(type==DATA) {
                if(data.length<1||data.length>frameBytes||data.length>lane.allowance||lane.remoteFin)throw error("RECEIVE_CREDIT_OR_EOF");
                lane.allowance-=data.length;lane.receivedBytes+=data.length;lane.queuedReceive+=data.length;lane.peakReceive=Math.max(lane.peakReceive,lane.queuedReceive);
                boolean delivered=false;
                while(!lane.readers.isEmpty()) {
                    CompletableFuture<byte[]> reader=lane.readers.removeFirst();
                    if(reader.isDone())continue;
                    lane.consume(data.length);
                    if(reader.complete(data)){delivered=true;break;}
                    // A cancellation can win after isDone(). Retry this SAME oldest segment
                    // with the next waiter; never leave old ring data behind pending readers.
                    lane.queuedReceive+=data.length;lane.pendingCredit-=data.length;
                }
                if(!delivered)lane.append(data);
            }else if(type==WINDOW) {
                if(data.length!=4)throw error("INVALID_WINDOW");
                int credit=ByteBuffer.wrap(data).getInt();
                if(credit<1||credit>lane.peerWindow-lane.credit)throw error("INVALID_CREDIT");
                lane.credit+=credit;lane.lastProgress=System.nanoTime();
            }else if(type==FIN) {
                if(data.length!=0||lane.remoteFin)throw error("INVALID_FIN");
                lane.remoteFin=true;
                if(lane.queuedReceive==0&&!lane.readers.isEmpty()) {
                    while(!lane.readers.isEmpty())if(lane.readers.removeFirst().complete(null))lane.remoteConsumed=true;
                }
            }else if(type==FIN_ACK) {
                if(data.length!=0||!lane.localFinSent||lane.localFinAck)throw error("INVALID_FIN_ACK");
                lane.localFinAck=true;lane.outputFinished.complete(null);
            }else throw error("UNKNOWN_FRAME_TYPE");
            monitor.notifyAll();
        }
    }
    private void writeLoop(){
        try {
            link.opened().get(options.timeout().toMillis(),TimeUnit.MILLISECONDS);
            while(!closing.get()) {
                Frame frame;
                synchronized(monitor) {
                    expire();
                    frame=next();
                    if(frame==null){monitor.wait(100);continue;}
                }
                byte[] wire=ByteBuffer.allocate(HEADER+frame.data().length).putInt(MAGIC).put((byte)VERSION).put((byte)frame.lane())
                        .put((byte)frame.type()).put((byte)0).putInt(frame.data().length).put(frame.data()).array();
                link.send(wire).get(options.timeout().toMillis(),TimeUnit.MILLISECONDS);
                synchronized(monitor) {
                    if(frame.type()==HELLO){helloSent=true;ready();}
                    else {
                        Lane lane=lane(frame.lane());
                        if(frame.type()==DATA&&!lane.ended) {
                            Write write=frame.write();write.offset+=frame.data().length;lane.sent+=frame.data().length;lane.lastProgress=System.nanoTime();
                            if(write.offset==write.bytes.length){lane.writes.removeFirst();lane.queuedWrite-=write.bytes.length;write.result.complete(null);}
                        }else if(frame.type()==FIN_ACK){lane.finAckSent=true;lane.finishGracefully();}
                    }
                    monitor.notifyAll();
                }
            }
        }catch(InterruptedException stop){Thread.currentThread().interrupt();if(!closing.get())abort("WRITER_INTERRUPTED");}
        catch(Throwable failure){if(!closing.get())abort("WRITE_FAILED");}
        finally{workerEnded();}
    }
    private Frame next(){
        if(!helloSent)return new Frame(0,HELLO,ByteBuffer.allocate(12).putInt(options.frameBytes()).putInt(options.nativeWindow()).putInt(options.replicaWindow()).array(),null);
        if(!helloReceived)return null;
        // A reset has finite reserved space even if its own data queue is full.
        for(Lane lane:List.of(nativeLane,replicaLane))
            if(lane.resetPending){lane.resetPending=false;return new Frame(lane.id,RESET,new byte[0],null);}
        boolean dataReady=canSend(nativeLane)||canSend(replicaLane);
        // Bound control bursts, alternate their lanes, then give a ready data frame a turn.
        // Continuous WINDOW updates therefore cannot starve either application stream.
        if(controlTurns<4||!dataReady)for(int i=0;i<2;i++) {
            Lane lane=controlNext==1?nativeLane:replicaLane;controlNext=3-controlNext;
            Frame control=control(lane);
            if(control!=null){controlTurns++;return control;}
        }
        Frame data=nextData();
        if(data!=null){controlTurns=0;return data;}
        return null;
    }
    private Frame control(Lane lane) {
        if(lane.ended)return null;
        if(lane.remoteConsumed&&!lane.finAckSent)return new Frame(lane.id,FIN_ACK,new byte[0],null);
        if(lane.pendingCredit>0){
            int amount=lane.pendingCredit;lane.pendingCredit=0;lane.allowance+=amount;
            return new Frame(lane.id,WINDOW,ByteBuffer.allocate(4).putInt(amount).array(),null);
        }
        if(lane.outputClosing&&lane.writes.isEmpty()&&!lane.localFinSent){
            lane.localFinSent=true;return new Frame(lane.id,FIN,new byte[0],null);
        }
        return null;
    }
    private Frame nextData() {
        boolean nativeReady=canSend(nativeLane),replicaReady=canSend(replicaLane);
        if(nativeReady&&(!replicaReady||nativeTurns>0)){nativeTurns=Math.max(0,nativeTurns-1);return data(nativeLane);}
        if(replicaReady){nativeTurns=4;return data(replicaLane);}
        return null;
    }
    private boolean canSend(Lane lane){return !lane.ended&&!lane.writes.isEmpty()&&lane.credit>0;}
    private Frame data(Lane lane){
        Write write=lane.writes.peekFirst();int bytes=Math.min(frameBytes,Math.min(lane.credit,write.bytes.length-write.offset));
        lane.credit-=bytes;return new Frame(lane.id,DATA,Arrays.copyOfRange(write.bytes,write.offset,write.offset+bytes),write);
    }
    private void expire()throws IOException {
        long now=System.nanoTime();
        if(!opened.isDone()&&now-started>options.timeout().toNanos())throw error("HELLO_TIMEOUT");
        for(Lane lane:List.of(nativeLane,replicaLane))if(!lane.ended) {
            Write write=lane.writes.peekFirst();
            if(write!=null&&now-lane.lastProgress>options.timeout().toNanos()||lane.outputClosing&&!lane.localFinAck&&now-lane.finStarted>options.timeout().toNanos())
                lane.end(true,"LANE_TIMEOUT");
        }
    }
    private void ready(){if(helloSent&&helloReceived)opened.complete(null);}
    private void abort(String reason){
        if(!closing.compareAndSet(false,true))return;code=reason;
        synchronized(monitor){opened.completeExceptionally(error(reason));nativeLane.end(false,reason);replicaLane.end(false,reason);monitor.notifyAll();}
        link.close();reader.interrupt();writer.interrupt();
    }
    private void workerEnded(){if(workers.decrementAndGet()==0)closed.complete(null);}
    @Override public void close(){abort("APPLICATION_CLOSED");}
}
