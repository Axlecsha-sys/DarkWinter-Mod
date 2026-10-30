// Sole accept loop for one already-owned endpoint, dispatching exact configured ALPNs.
package com.dark.winter.online.iroh;

import computer.iroh.Connection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Sole accept loop for one already-owned endpoint, dispatching exact configured ALPNs.
 * It never closes the endpoint service or any IdentityStore.
 * A registration receives real transport connectivity, not DarkWinter admission.
 */
public final class IrohProtocolRouter implements AutoCloseable {
    private static final Map<IrohEndpointService,IrohProtocolRouter> OWNERS=new IdentityHashMap<>();
    private static final int MAX_INCOMING=48;
    private final IrohEndpointService service;
    private final Map<String,Registration> registrations=new ConcurrentHashMap<>();
    private final Set<Connection> incoming=ConcurrentHashMap.newKeySet();
    private final Semaphore capacity=new Semaphore(MAX_INCOMING);
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Connection>> accepting=new AtomicReference<>();
    private final ExecutorService workers=Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("Iroh-router-",0).factory());
    private final ScheduledThreadPoolExecutor reap=new ScheduledThreadPoolExecutor(1,task->{
        Thread thread=new Thread(task,"Iroh-router-reap");thread.setDaemon(true);return thread;
    });
    private volatile String status="running";

    public IrohProtocolRouter(IrohEndpointService service){
        this.service=Objects.requireNonNull(service);
        service.publicKeyHex(); // Cached readiness only.
        synchronized(OWNERS){
            if(OWNERS.containsKey(service))throw new IllegalStateException("The endpoint already has an accept router");
            OWNERS.put(service,this);
        }
        reap.setRemoveOnCancelPolicy(true);reap.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        reap.scheduleWithFixedDelay(this::reapClosed,1,1,TimeUnit.SECONDS);
        workers.execute(this::acceptLoop);
    }
    public IrohEndpointService service(){return service;}
    public String publicKeyHex(){return service.publicKeyHex();}
    public String status(){return status;}
    public int incomingCount(){return incoming.size();}

    /** Closing an old registration can never unregister a subsequent registration. */
    public AutoCloseable register(String alpn,Consumer<Connection> handler)throws IOException{
        if(alpn==null||!alpn.matches("[A-Za-z0-9/._-]{1,128}")||handler==null)throw failure();
        Registration value=new Registration(alpn,handler);
        synchronized(registrations){
            if(closed.get()||registrations.putIfAbsent(alpn,value)!=null)throw failure();
        }
        return value;
    }
    private final class Registration implements AutoCloseable {
        final String alpn;final Consumer<Connection> handler;final AtomicBoolean removed=new AtomicBoolean();
        Registration(String alpn,Consumer<Connection> handler){this.alpn=alpn;this.handler=handler;}
        public void close(){if(removed.compareAndSet(false,true))registrations.remove(alpn,this);}
    }
    private void acceptLoop(){
        while(!closed.get()){
            CompletableFuture<Connection> operation=service.accept(Duration.ofSeconds(15));accepting.set(operation);
            try{
                if(closed.get()){operation.cancel(true);return;}
                Connection connection=operation.get(17,TimeUnit.SECONDS);
                if(closed.get()||!capacity.tryAcquire()){end(connection);continue;}
                incoming.add(connection);
                String alpn;
                try{
                    byte[] raw=connection.alpn();
                    if(raw.length<1||raw.length>128)throw failure();
                    alpn=new String(raw,StandardCharsets.US_ASCII);
                    if(!Arrays.equals(raw,alpn.getBytes(StandardCharsets.US_ASCII)))throw failure();
                }catch(Throwable invalid){release(connection,true);continue;}
                Registration registration=registrations.get(alpn);
                if(registration==null||registration.removed.get()){release(connection,true);continue;}
                try{
                    workers.execute(()->{
                        if(closed.get()||registration.removed.get()||registrations.get(alpn)!=registration){release(connection,true);return;}
                        try{registration.handler.accept(connection);}
                        catch(Throwable rejected){release(connection,true);}
                    });
                }catch(RuntimeException stopped){release(connection,true);}
            }catch(ExecutionException failed){
                Throwable cause=unwrap(failed);
                if(closed.get())return;
                if(cause instanceof IrohAsync.Failure known&&known.code()==IrohAsync.Code.TIMEOUT||cause instanceof IrohEndpointService.PeerRejected){
                    // Public timeout does not imply that EndpointService released its sole native accept.
                    try{service.quiesced(operation).get(5,TimeUnit.SECONDS);}
                    catch(Exception notQuiescent){failClosed();return;}
                    if(!service.nativeAvailableOnWorker()){failClosed();return;}
                    continue;
                }
                failClosed();return;
            }catch(InterruptedException stopped){operation.cancel(true);Thread.currentThread().interrupt();if(!closed.get())failClosed();return;}
            catch(Exception failed){operation.cancel(true);if(!closed.get())failClosed();return;}
            finally{accepting.compareAndSet(operation,null);}
        }
    }
    private static Throwable unwrap(Throwable failure){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable actual=failure;
        for(int i=0;i<16&&seen.add(actual)&&(actual instanceof ExecutionException||actual instanceof CompletionException)&&actual.getCause()!=null;i++)
            actual=actual.getCause();
        return actual;
    }
    private void reapClosed(){
        if(closed.get())return;
        for(Connection connection:incoming){
            try{if(connection.closeReason()!=null)release(connection,true);}
            catch(Throwable disposed){release(connection,false);}
        }
    }
    private void release(Connection connection,boolean close){
        if(incoming.remove(connection)){if(close)end(connection);capacity.release();}
    }
    private void failClosed(){status="failed";close();}
    private static void end(Connection connection){
        if(connection==null)return;
        try{connection.close(0,new byte[0]);}catch(Throwable ignored){}
        try{connection.close();}catch(Throwable ignored){}
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        if(!status.equals("failed"))status="closed";
        synchronized(OWNERS){OWNERS.remove(service,this);}
        synchronized(registrations){for(Registration registration:registrations.values())registration.close();}
        CompletableFuture<Connection> pending=accepting.getAndSet(null);if(pending!=null)pending.cancel(true);
        reap.shutdownNow();
        // Connections were accepted by this router; outbound connections and the service remain owned by callers.
        workers.execute(()->{for(Connection connection:incoming)release(connection,true);});
        workers.shutdown();
    }
    private static IOException failure(){return new IOException("Iroh protocol router unavailable");}
    @Override public String toString(){return "IrohProtocolRouter[state="+status+"]";}
}
