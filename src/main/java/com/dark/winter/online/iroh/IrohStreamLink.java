// One owned real QUIC bi-stream, with one physical reader and one outstanding writer.
package com.dark.winter.online.iroh;

import com.dark.winter.online.transport.ReliableTransportLink;
import computer.iroh.BiStream;
import computer.iroh.RecvStream;
import computer.iroh.SendStream;
import kotlin.Unit;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * One owned real QUIC bi-stream, with one physical reader and one outstanding writer.
 * No peer authentication, Native ACL, invitation or host authority is asserted here.
 * Raw QUIC FIN is only EOF; logical consumption/FIN acknowledgments belong to the multiplexer.
 */
public final class IrohStreamLink implements ReliableTransportLink {
    public static final int READ_BYTES=16*1024,MAX_WRITE_BYTES=64*1024;
    public record Stats(long sentBytes,long receivedBytes,int pendingWriteBytes,boolean inputEof,boolean outputEof,boolean closing){}
    private final Object monitor=new Object();
    private final BiStream stream;
    private final SendStream send;
    private final RecvStream recv;
    private final Duration timeout,cleanupTimeout;
    private final CompletableFuture<Void> closed=new CompletableFuture<>();
    private final Set<CompletableFuture<?>> active=new HashSet<>();
    private CompletableFuture<Void> writer,finisher;
    private CompletableFuture<byte[]> reader;
    private int starting,pendingWriteBytes;
    private boolean closing,cleanupReady,disposed,inputEof,outputEof,finishRequested,finishStarted;
    private long sentBytes,receivedBytes;

    /** The caller transfers ownership of this already-created native stream, not its parent connection. */
    public IrohStreamLink(BiStream stream,Duration operationTimeout){
        this.stream=Objects.requireNonNull(stream);
        if(operationTimeout==null||operationTimeout.compareTo(Duration.ofMillis(1))<0
                ||operationTimeout.compareTo(Duration.ofMinutes(2))>0)throw new IllegalArgumentException("Iroh operation timeout bounds");
        timeout=operationTimeout;cleanupTimeout=timeout.compareTo(Duration.ofSeconds(5))>0?Duration.ofSeconds(5):timeout;
        send=stream.send();recv=stream.recv();
    }
    @Override public CompletableFuture<Void> opened(){return CompletableFuture.completedFuture(null);}
    @Override public CompletableFuture<Void> closed(){return closed.copy();}
    public Stats stats(){
        synchronized(monitor){return new Stats(sentBytes,receivedBytes,pendingWriteBytes,inputEof,outputEof,closing);}
    }
    @Override public CompletableFuture<Void> send(byte[] bytes){
        if(bytes==null||bytes.length>MAX_WRITE_BYTES)return failed("Iroh write bounds");
        CompletableFuture<Void> result;
        byte[] owned;
        synchronized(monitor){
            if(closing||finishRequested)return failed("Iroh output closed");
            if(writer!=null&&!writer.isDone())return failed("Iroh writer busy");
            if(bytes.length==0)return CompletableFuture.completedFuture(null);
            owned=bytes.clone();result=new CompletableFuture<>();writer=result;pendingWriteBytes=owned.length;
        }
        result.whenComplete((value,failure)->{if(result.isCancelled())close();});
        CompletableFuture<Unit> actual=operation(timeout,continuation->send.writeAll(owned,continuation),false);
        actual.whenComplete((value,failure)->{
            if(failure!=null){terminate(false);return;}
            boolean finish;
            synchronized(monitor){
                if(closing||writer!=result)return;
                sentBytes+=owned.length;pendingWriteBytes=0;finish=finishRequested&&!finishStarted;
            }
            // Keep the public result registered until its atomic publication races close().
            result.complete(null);
            synchronized(monitor){if(writer==result)writer=null;}
            if(finish)startFinish();
        });
        return result;
    }
    @Override public CompletableFuture<byte[]> read(){
        CompletableFuture<byte[]> result;
        synchronized(monitor){
            if(closing)return failed("Iroh link closed");
            if(inputEof)return CompletableFuture.completedFuture(null);
            if(reader!=null&&!reader.isDone())return failed("Iroh reader busy");
            result=new CompletableFuture<>();reader=result;
        }
        result.whenComplete((value,failure)->{if(result.isCancelled())close();});
        CompletableFuture<byte[]> actual=operation(timeout,IrohAsync.read(recv,READ_BYTES),false);
        actual.whenComplete((bytes,failure)->{
            if(failure!=null||bytes==null||bytes.length>READ_BYTES){terminate(false);return;}
            boolean complete;
            synchronized(monitor){
                if(closing||reader!=result)return;
                inputEof=bytes.length==0;
                if(!inputEof)receivedBytes+=bytes.length;
                complete=inputEof&&outputEof;
            }
            result.complete(bytes.length==0?null:bytes);
            synchronized(monitor){if(reader==result)reader=null;}
            if(complete)terminate(true);
        });
        return result;
    }
    /**
     * Optional raw half-close after the current write; it is not a remote-consumption acknowledgment.
     * The ReliableTransportLink API and multiplexer do not require or infer this acknowledgment.
     */
    public CompletableFuture<Void> finishOutput(){
        CompletableFuture<Void> result;boolean start;
        synchronized(monitor){
            if(finisher!=null)return finisher;
            if(closing)return failed("Iroh link closed");
            finishRequested=true;finisher=new CompletableFuture<>();result=finisher;
            start=writer==null||writer.isDone();
        }
        result.whenComplete((value,failure)->{if(result.isCancelled())close();});
        if(start)startFinish();return result;
    }
    private void startFinish(){
        CompletableFuture<Void> result;
        synchronized(monitor){
            if(closing||finishStarted||!finishRequested)return;
            if(writer!=null&&!writer.isDone())return;
            finishStarted=true;result=finisher;
        }
        CompletableFuture<Unit> actual=operation(timeout,continuation->send.finish(continuation),false);
        actual.whenComplete((value,failure)->{
            if(failure!=null){terminate(false);return;}
            boolean complete;
            synchronized(monitor){
                if(closing)return;
                outputEof=true;complete=inputEof;
            }
            result.complete(null);
            if(complete)terminate(true);
        });
    }
    /** Starts no native work after close; each started operation remains owned until real FFI quiescence. */
    private <T>CompletableFuture<T> operation(Duration budget,IrohAsync.Suspend<T> work,boolean cleanup){
        synchronized(monitor){
            if(closing&&!cleanup)return failed("Iroh link closed");
            starting++;
        }
        CompletableFuture<T> actual;
        try{actual=cleanup?IrohAsync.cleanup(budget,work):IrohAsync.call(budget,work);}
        catch(Throwable unavailable){
            synchronized(monitor){starting--;}
            finishDisposalIfReady();return failed("Iroh operation unavailable");
        }
        boolean cancel;
        synchronized(monitor){starting--;active.add(actual);cancel=closing&&!cleanup;}
        IrohAsync.settled(actual).whenComplete((value,failure)->{
            synchronized(monitor){active.remove(actual);}
            finishDisposalIfReady();
        });
        if(cancel)actual.cancel(true);
        return actual;
    }
    @Override public void close(){terminate(false);}
    private void terminate(boolean graceful){
        List<CompletableFuture<?>> cancel;
        List<CompletableFuture<?>> publicResults=new ArrayList<>();
        synchronized(monitor){
            if(closing)return;
            closing=true;
            cancel=new ArrayList<>(active);
            if(writer!=null)publicResults.add(writer);
            if(reader!=null)publicResults.add(reader);
            if(finisher!=null)publicResults.add(finisher);
            writer=null;reader=null;pendingWriteBytes=0;
        }
        // One bounded cleanup worker per owned stream; close() never blocks the caller.
        Thread.ofVirtual().name("Iroh-stream-close").start(()->{
            for(CompletableFuture<?> operation:cancel)operation.cancel(true);
            if(!graceful){
                operation(cleanupTimeout,IrohAsync.reset(send,0),true);
                operation(cleanupTimeout,IrohAsync.stop(recv,0),true);
            }
            synchronized(monitor){cleanupReady=true;}
            for(CompletableFuture<?> result:publicResults)if(!result.isDone())
                result.completeExceptionally(new IOException("Iroh link closed"));
            finishDisposalIfReady();
        });
    }
    private void finishDisposalIfReady(){
        synchronized(monitor){
            if(!closing||!cleanupReady||disposed||starting!=0||!active.isEmpty())return;
            disposed=true;
        }
        // All own native calls have completed their generated complete/free paths before dropping handles.
        boolean failed=false;
        for(AutoCloseable handle:new AutoCloseable[]{recv,send,stream})try{handle.close();}catch(Exception failure){failed=true;}
        if(failed)closed.completeExceptionally(new IOException("Iroh stream cleanup failed"));else closed.complete(null);
    }
    private static <T>CompletableFuture<T> failed(String reason){
        return CompletableFuture.failedFuture(new IOException(reason));
    }
}
