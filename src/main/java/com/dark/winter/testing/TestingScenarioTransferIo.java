// One interruptible dependency worker and one bounded waiting request.
package com.dark.winter.testing;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** One interruptible dependency worker and one bounded waiting request. Inputs never retain a Minecraft server. */
final class TestingScenarioTransferIo {
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),task->{Thread thread=new Thread(task,"DarkWinter scenario dependency I/O");thread.setDaemon(true);return thread;},
            new ThreadPoolExecutor.AbortPolicy());
    private TestingScenarioTransferIo(){}
    static Future<TestingScenarioTransfers.Prepared> prepare(Path source,Path target)throws IOException {
        return submit(()->TestingScenarioTransfers.prepare(source,target));
    }
    static Future<TestingScenarioTransfers.LevelPayload> level(TestingScenarioTransfers.Prepared prepared,int index)throws IOException {
        return submit(()->TestingScenarioTransfers.readLevel(prepared,index));
    }
    static Future<Void> confirm(TestingScenarioTransfers.Prepared prepared,Map<String,Path> files,Map<String,String> hashes)throws IOException {
        Map<String,Path> inputs=Map.copyOf(files);Map<String,String> expected=Map.copyOf(hashes);
        return submit(()->{
            verifyFiles(inputs,expected);
            var receipt=prepared==null?null:prepared.plan().verify();
            verifyFiles(inputs,expected);interrupted();
            if(prepared!=null)prepared.plan().publishVerified(receipt);
            return null;
        });
    }
    private static void verifyFiles(Map<String,Path> files,Map<String,String> hashes)throws IOException {
        for(var entry:files.entrySet()){
            interrupted();Path file=entry.getValue();
            if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                    ||!Objects.equals(hashes.get(entry.getKey()),TestingArchive.hash(file)))
                throw new IOException("Источник категории изменился после обзора: "+entry.getKey());
        }
    }
    private static <T> Future<T> submit(Callable<T> task)throws IOException {
        TestingArchiveScope.Token context=TestingArchiveScope.capture().orElse(null);
        try{return WORKER.submit(()->{if(context==null)return task.call();return context.call(()->{try{return task.call();}catch(IOException io){throw io;}catch(Exception failure){throw new IOException("Scoped dependency worker failed",failure);}});});}catch(RejectedExecutionException busy){throw new IOException("Дождитесь освобождения проверки зависимостей",busy);}
    }
    static void cancel(Future<?> task){if(task!=null)task.cancel(true);WORKER.purge();}
    static <T> T completed(Future<T> task)throws IOException {
        if(task==null||!task.isDone())throw new IOException("Асинхронная проверка ещё не готова");
        try{return task.get();}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException("Проверка прервана");}
        catch(CancellationException error){throw new InterruptedIOException("Проверка отменена");}
        catch(ExecutionException error){if(error.getCause() instanceof IOException io)throw io;throw new IOException("Ошибка подготовки зависимостей",error.getCause());}
    }
    private static void interrupted()throws InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Проверка отменена");}
}
