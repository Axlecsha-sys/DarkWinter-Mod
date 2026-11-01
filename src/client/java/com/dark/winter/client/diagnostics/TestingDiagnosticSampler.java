// Samples process CPU and a bounded log tail on one background worker, publishing only immutable current-session values.
package com.dark.winter.client.diagnostics;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

public final class TestingDiagnosticSampler implements AutoCloseable {
    public static final int MAX_LOG_BYTES=65536, MAX_ROWS=32, MAX_ROW_CHARS=512;
    private static final long CPU_INTERVAL=1_000_000_000L, LOG_INTERVAL=2_000_000_000L;
    private static final Snapshot EMPTY=new Snapshot(-1,0,List.of(),"Сбор журнала выключен");
    private final Object lock=new Object();
    private final Executor suppliedExecutor;
    private final CpuReader cpuReader;
    private final LogReader logReader;
    private final LongSupplier wallMillis;
    private ExecutorService ownedExecutor;
    private volatile Snapshot latest=EMPTY;
    private Path selectedLog;
    private long epoch,lastCpuAttempt,lastLogAttempt;
    private boolean cpuAttempted,logAttempted,inFlight,closed;

    @FunctionalInterface public interface CpuReader { double read() throws Exception; }
    @FunctionalInterface public interface LogReader { LogResult read(Path log,long sampledMillis) throws Exception; }

    public TestingDiagnosticSampler() {
        this(null,TestingDiagnosticSampler::readCpu,TestingDiagnosticSampler::readBoundedLog,System::currentTimeMillis,true);
    }
    public TestingDiagnosticSampler(Executor executor,CpuReader cpuReader,LogReader logReader,LongSupplier wallMillis) {
        this(Objects.requireNonNull(executor),cpuReader,logReader,wallMillis,false);
    }
    private TestingDiagnosticSampler(Executor executor,CpuReader cpuReader,LogReader logReader,LongSupplier wallMillis,boolean owned) {
        suppliedExecutor=executor;this.cpuReader=Objects.requireNonNull(cpuReader);
        this.logReader=Objects.requireNonNull(logReader);this.wallMillis=Objects.requireNonNull(wallMillis);
    }

    /** The supplied clock is monotonic nanoseconds. The injected executor must dispatch asynchronously. */
    public Snapshot request(Path log,long now) {
        Path path=Objects.requireNonNull(log).toAbsolutePath().normalize();
        long requestedEpoch;boolean cpuDue,logDue;Snapshot previous;
        synchronized(lock) {
            if(closed)return latest;
            if(!path.equals(selectedLog)){reset();selectedLog=path;}
            previous=latest;
            if(inFlight)return previous;
            cpuDue=!cpuAttempted || now-lastCpuAttempt>=CPU_INTERVAL;
            logDue=!logAttempted || now-lastLogAttempt>=LOG_INTERVAL;
            if(!cpuDue && !logDue)return previous;
            if(cpuDue){cpuAttempted=true;lastCpuAttempt=now;}
            if(logDue){logAttempted=true;lastLogAttempt=now;}
            inFlight=true;requestedEpoch=epoch;
        }
        try {
            dispatch(()->sample(requestedEpoch,path,cpuDue,logDue,previous));
        } catch(RejectedExecutionException unavailable) {
            synchronized(lock) {
                if(!closed && epoch==requestedEpoch)
                    latest=new Snapshot(previous.cpuLoad(),previous.cpuSampleMillis(),previous.recentErrors(),"Фоновая диагностика недоступна");
                inFlight=false;
            }
        }
        return previous;
    }
    public Snapshot snapshot() { return latest; }

    public void invalidate() { synchronized(lock){if(!closed){reset();selectedLog=null;}} }

    private void reset() {
        epoch++;cpuAttempted=logAttempted=false;latest=EMPTY;
        // Keep an old job in flight until it exits; invalidation must not create a backlog.
    }
    @Override public void close() {
        ExecutorService owned;
        synchronized(lock) {
            if(closed)return;
            closed=true;reset();selectedLog=null;owned=ownedExecutor;ownedExecutor=null;
        }
        if(owned!=null)owned.shutdownNow();
    }
    private void dispatch(Runnable work) {
        Executor executor=suppliedExecutor;
        if(executor==null) {
            synchronized(lock) {
                if(closed)throw new RejectedExecutionException("Sampler closed");
                if(ownedExecutor==null)ownedExecutor=Executors.newSingleThreadExecutor(task->{
                    Thread thread=new Thread(task,"DarkWinter diagnostic sampler");thread.setDaemon(true);return thread;
                });
                executor=ownedExecutor;
            }
        }
        executor.execute(work);
    }
    private void sample(long requestedEpoch,Path path,boolean cpuDue,boolean logDue,Snapshot previous) {
        synchronized(lock) {
            if(closed || epoch!=requestedEpoch){inFlight=false;return;}
        }
        double cpu=previous.cpuLoad();long cpuTime=previous.cpuSampleMillis();
        List<String> rows=previous.recentErrors();String scope=previous.errorScope();
        try {
            if(cpuDue) {
                try {cpu=cpuReader.read();}
                catch(Exception unavailable){cpu=-1;}
                cpuTime=Math.max(0,wallMillis.getAsLong());
            }
            if(logDue) {
                try {
                    LogResult result=logReader.read(path,Math.max(0,wallMillis.getAsLong()));
                    rows=result.recentErrors();scope=result.errorScope();
                } catch(Exception unavailable) {
                    rows=List.of();scope="Журнал недоступен: "+Objects.toString(unavailable.getMessage(),unavailable.getClass().getSimpleName());
                }
            }
        } finally {
            synchronized(lock) {
                if(!closed && epoch==requestedEpoch)latest=new Snapshot(cpu,cpuTime,rows,scope);
                inFlight=false;
            }
        }
    }
    private static double readCpu() {
        var bean=ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean os?os.getProcessCpuLoad():-1;
    }

    /** Reads at most the last 64 KiB, discards the initial truncated line, and keeps the last 32 warning/error rows. */
    public static LogResult readBoundedLog(Path file,long sampledMillis) throws java.io.IOException {
        String text;
        try(var channel=Files.newByteChannel(file,StandardOpenOption.READ)) {
            long size=channel.size(),start=Math.max(0,size-MAX_LOG_BYTES);channel.position(start);
            ByteBuffer buffer=ByteBuffer.allocate((int)Math.min(MAX_LOG_BYTES,size-start));
            while(buffer.hasRemaining()){int read=channel.read(buffer);if(read<=0)break;}
            buffer.flip();text=StandardCharsets.UTF_8.decode(buffer).toString();
            if(start>0){int newline=text.indexOf('\n');text=newline<0?"":text.substring(newline+1);}
        }
        ArrayDeque<String> rows=new ArrayDeque<>(MAX_ROWS);
        text.lines().filter(TestingDiagnosticSampler::relevant).forEach(line->{
            if(rows.size()==MAX_ROWS)rows.removeFirst();rows.addLast(bounded(line,MAX_ROW_CHARS));
        });
        return new LogResult(List.copyOf(rows),"Последние 64 KiB: "+rows.size()+" строк WARN/ERROR; "+Instant.ofEpochMilli(sampledMillis));
    }
    private static boolean relevant(String line) {
        return line.contains("/WARN") || line.contains("/ERROR") || line.contains(" WARN ")
                || line.contains(" ERROR ") || line.startsWith("Caused by:");
    }
    private static String bounded(String value,int limit) {
        String text=Objects.toString(value,"");
        return text.length()<=limit?text:text.substring(0,limit-1)+"…";
    }
    private static List<String> boundedRows(List<String> rows) {
        Objects.requireNonNull(rows);
        List<String> result=new ArrayList<>(Math.min(MAX_ROWS,rows.size()));
        for(int i=Math.max(0,rows.size()-MAX_ROWS);i<rows.size();i++)result.add(bounded(rows.get(i),MAX_ROW_CHARS));
        return List.copyOf(result);
    }
    public record Snapshot(double cpuLoad,long cpuSampleMillis,List<String> recentErrors,String errorScope) {
        public Snapshot {
            if(!Double.isFinite(cpuLoad) || cpuLoad<0 || cpuLoad>1)cpuLoad=-1;
            cpuSampleMillis=Math.max(0,cpuSampleMillis);
            recentErrors=boundedRows(recentErrors);errorScope=bounded(errorScope,MAX_ROW_CHARS);
        }
    }
    public record LogResult(List<String> recentErrors,String errorScope) {
        public LogResult {recentErrors=boundedRows(recentErrors);errorScope=bounded(errorScope,MAX_ROW_CHARS);}
    }
}
