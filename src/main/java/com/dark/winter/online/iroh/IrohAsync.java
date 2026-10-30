// Bounded Java bridge to the pinned Iroh 1.2.0 Kotlin suspend ABI.
package com.dark.winter.online.iroh;

import computer.iroh.RecvStream;
import computer.iroh.SendStream;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlinx.coroutines.CompletableJob;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.JobKt;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Bounded Java bridge to the pinned Iroh 1.2.0 Kotlin suspend ABI.
 * Calls, cancellation and publications run off the caller and native callback threads.
 * A transport result grants no profile, admission or host authority.
 */
public final class IrohAsync {
    private IrohAsync() {}
    private static final int MAX_PENDING=256,MAX_READ=1024*1024;
    private static final Semaphore CAPACITY=new Semaphore(MAX_PENDING);
    private static final Semaphore CLEANUP=new Semaphore(32);
    private static final ScheduledThreadPoolExecutor TIMER=new ScheduledThreadPoolExecutor(1,r->{
        Thread thread=new Thread(r,"Iroh-deadlines");thread.setDaemon(true);return thread;
    });
    static {
        TIMER.setRemoveOnCancelPolicy(true);
        TIMER.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }
    public enum Code { INVALID_ARGUMENT, CAPACITY, TIMEOUT, CANCELLED, NATIVE, ABI }
    public static final class Failure extends IOException {
        private final Code code;
        private Failure(Code code){super("Iroh transport operation "+code.name().toLowerCase(java.util.Locale.ROOT));this.code=code;}
        public Code code(){return code;}
    }
    @FunctionalInterface public interface Suspend<T> {
        Object invoke(Continuation<? super T> continuation)throws Exception;
    }
    private static final class CallFuture<T> extends CompletableFuture<T> {
        final CompletableFuture<Void> settled=new CompletableFuture<>();
    }

    public static <T>CompletableFuture<T> call(Duration timeout,Suspend<T> operation){
        return call(timeout,operation,IrohAsync::closeHandle);
    }
    /**
     * discardLate owns a real successful value if timeout/cancellation won its publication race.
     * Endpoint/Connection callers should provide lifecycle-aware cleanup, beyond freeing a wrapper.
     */
    public static <T>CompletableFuture<T> call(Duration timeout,Suspend<T> operation,Consumer<? super T> discardLate){
        return start(timeout,operation,discardLate,CAPACITY);
    }
    /** Cleanup has a separate bounded reservation so pending reads cannot prevent shutdown. */
    public static <T>CompletableFuture<T> cleanup(Duration timeout,Suspend<T> operation){
        return start(timeout,operation,IrohAsync::closeHandle,CLEANUP);
    }
    private static <T>CompletableFuture<T> start(Duration timeout,Suspend<T> operation,Consumer<? super T> discardLate,Semaphore reservation){
        if(timeout==null||operation==null||discardLate==null||timeout.compareTo(Duration.ofMillis(1))<0
                ||timeout.compareTo(Duration.ofMinutes(2))>0)return rejected(Code.INVALID_ARGUMENT);
        if(!reservation.tryAcquire())return rejected(Code.CAPACITY);
        Call<T> call=new Call<>(operation,discardLate,reservation);
        try {
            call.deadline=TIMER.schedule(()->call.stop(Code.TIMEOUT),timeout.toNanos(),TimeUnit.NANOSECONDS);
            call.result.whenComplete((value,failure)->{
                if(call.result.isCancelled())call.stop(Code.CANCELLED);
            });
            run("invoke",call::invoke);
        }catch(Throwable unavailable){call.finish(null,new Failure(Code.NATIVE));}
        return call.result;
    }
    /**
     * Actual native-call quiescence, not the public timeout/cancellation notification.
     * It can remain pending if the native implementation ignores cancellation.
     */
    public static CompletableFuture<Void> settled(CompletableFuture<?> operation){
        if(operation instanceof CallFuture<?> owned)return owned.settled.copy();
        return CompletableFuture.failedFuture(new Failure(Code.INVALID_ARGUMENT));
    }
    public static int pendingCalls(){return MAX_PENDING-CAPACITY.availablePermits();}

    private static <T>CompletableFuture<T> rejected(Code code){
        CallFuture<T> result=new CallFuture<>();result.settled.complete(null);result.completeExceptionally(new Failure(code));return result;
    }
    private static void run(String label,Runnable work){
        Thread.ofVirtual().name("Iroh-"+label).start(work);
    }
    private static void closeHandle(Object value){
        if(value instanceof AutoCloseable closeable)try{closeable.close();}catch(Exception ignored){}
    }
    private static final class Call<T> {
        final Suspend<T> operation;
        final Consumer<? super T> discardLate;
        final Semaphore reservation;
        final CallFuture<T> result=new CallFuture<>();
        final CompletableJob job=JobKt.Job((Job)null);
        final AtomicBoolean finished=new AtomicBoolean(),stopping=new AtomicBoolean();
        volatile ScheduledFuture<?> deadline;
        volatile Code stopCode;
        final Continuation<T> continuation=new Continuation<>(){
            public CoroutineContext getContext(){return Dispatchers.getDefault().plus(job);}
            @SuppressWarnings("unchecked") public void resumeWith(Object value){
                try{ResultKt.throwOnFailure(value);finish((T)value,null);}
                catch(Throwable failure){finish(null,failure);}
            }
        };
        Call(Suspend<T> operation,Consumer<? super T> discardLate,Semaphore reservation){this.operation=operation;this.discardLate=discardLate;this.reservation=reservation;}
        void invoke(){
            if(result.isDone()){finish(null,new Failure(Code.CANCELLED));return;}
            try{
                Object value=operation.invoke(continuation);
                if(value!=IntrinsicsKt.getCOROUTINE_SUSPENDED())continuation.resumeWith(value);
            }catch(Throwable failure){finish(null,failure);}
        }
        void stop(Code code){
            if(finished.get()||!stopping.compareAndSet(false,true))return;
            stopCode=code;
            // Native cancellation may execute FFI finalizers; it must never block a timer or caller.
            run("cancel",()->job.cancel(new CancellationException("Iroh operation cancelled")));
            run("timeout",()->{
                if(!result.isDone())result.completeExceptionally(new Failure(code));
            });
        }
        void finish(T value,Throwable failure){
            if(!finished.compareAndSet(false,true))return;
            ScheduledFuture<?> timer=deadline;if(timer!=null)timer.cancel(false);
            run("complete",()->{
                job.complete();
                // UniFFI's generated complete/free has returned before this continuation is resumed.
                reservation.release();result.settled.complete(null);
                Failure publicFailure=failure==null?null:failure instanceof Failure known?known:
                        new Failure(failure instanceof CancellationException?(stopCode==null?Code.CANCELLED:stopCode):Code.NATIVE);
                boolean published=publicFailure==null?result.complete(value):result.completeExceptionally(publicFailure);
                if(!published&&publicFailure==null&&value!=null){
                    try{discardLate.accept(value);}catch(Throwable ignored){closeHandle(value);}
                }
            });
        }
    }

    /** Real bounded read; an empty array is the native stream's EOF, not a fabricated ACK. */
    public static Suspend<byte[]> read(RecvStream stream,int limit){
        Objects.requireNonNull(stream);
        if(limit<1||limit>MAX_READ)throw new IllegalArgumentException("Iroh read bounds");
        return continuation->invokeUnsigned("read-qim9Vi0",RecvStream.class,stream,limit,continuation);
    }
    public static CompletableFuture<byte[]> read(RecvStream stream,int limit,Duration timeout){
        return IrohAsync.<byte[]>call(timeout,read(stream,limit));
    }
    public static Suspend<byte[]> readExact(RecvStream stream,int length){
        Objects.requireNonNull(stream);
        if(length<1||length>MAX_READ)throw new IllegalArgumentException("Iroh read bounds");
        return continuation->invokeUnsigned("readExact-qim9Vi0",RecvStream.class,stream,length,continuation);
    }
    public static Suspend<Unit> stop(RecvStream stream,long code){
        Objects.requireNonNull(stream);if(code<0)throw new IllegalArgumentException("Iroh stop bounds");
        return continuation->invokeUnsigned("stop-4PLdz1A",RecvStream.class,stream,code,continuation);
    }
    public static Suspend<Unit> reset(SendStream stream,long code){
        Objects.requireNonNull(stream);if(code<0)throw new IllegalArgumentException("Iroh reset bounds");
        return continuation->invokeUnsigned("reset-4PLdz1A",SendStream.class,stream,code,continuation);
    }
    private static Object invokeUnsigned(String name,Class<?> type,Object target,Object argument,Continuation<?> continuation)throws Exception{
        try {
            Class<?> primitive=argument instanceof Integer?int.class:long.class;
            Method method=type.getMethod(name,primitive,Continuation.class);
            return method.invoke(target,argument,continuation);
        }catch(InvocationTargetException wrapped){
            Throwable failure=wrapped.getCause();
            if(failure instanceof Exception checked)throw checked;
            if(failure instanceof Error error)throw error;
            throw new Failure(Code.NATIVE);
        }catch(ReflectiveOperationException incompatible){throw new Failure(Code.ABI);}
    }
}
