// JVM adapter only. Native world data and jobs are owned by C++ and do not contain Java references.
package com.dark.winter.core;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import static java.lang.foreign.ValueLayout.*;

public final class CoreBridge implements AutoCloseable {
    public static final int CELLS=4096, PREPARED_BYTES=586, PENDING=1;
    public record Key(int x,int y,int z,long revision) {}
    private static Library loaded;
    private final Library library;
    private final Arena arena;
    private final MemorySegment handle,cells,bytes,word;
    private boolean closed;

    private static final class Library {
        final Path path;
        final Arena lifetime=Arena.ofShared();
        final Map<String,MethodHandle> methods=new HashMap<>();
        Library(Path path)throws IOException {
            this.path=path;
            try {
                SymbolLookup lookup=SymbolLookup.libraryLookup(path,lifetime);
                Linker linker=Linker.nativeLinker();
                var descriptors=Map.ofEntries(
                    Map.entry("abi_version",FunctionDescriptor.of(JAVA_INT)),
                    Map.entry("create",FunctionDescriptor.of(JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT,ADDRESS)),
                    Map.entry("destroy",FunctionDescriptor.of(JAVA_INT,ADDRESS)),
                    Map.entry("define_material",FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT,JAVA_INT,ADDRESS,JAVA_INT)),
                    Map.entry("put",FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_LONG,ADDRESS,JAVA_INT)),
                    Map.entry("prepare",FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_LONG,JAVA_LONG,ADDRESS)),
                    Map.entry("poll",FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_LONG)),
                    Map.entry("cancel",FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_LONG)),
                    Map.entry("release",FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_LONG)),
                    Map.entry("copy_section",FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT,JAVA_INT,JAVA_INT,ADDRESS,JAVA_INT,ADDRESS)),
                    Map.entry("copy_prepared",FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_LONG,ADDRESS,JAVA_INT)),
                    Map.entry("copy_index",FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,ADDRESS,JAVA_INT)),
                    Map.entry("save",FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,JAVA_INT)),
                    Map.entry("load",FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,JAVA_INT)),
                    Map.entry("stat",FunctionDescriptor.of(JAVA_LONG,ADDRESS,JAVA_INT)));
                descriptors.forEach((name,descriptor)->methods.put(name,linker.downcallHandle(lookup.findOrThrow("dw_core_"+name),descriptor)));
                int abi=(int)methods.get("abi_version").invokeExact();
                if(abi!=1)throw new IOException("Unsupported core ABI "+abi);
            } catch(Throwable failure) {
                lifetime.close();
                if(failure instanceof IOException io)throw io;
                throw new IOException("Cannot load core library",failure);
            }
        }
    }
    private static synchronized Library library(Path path)throws IOException {
        if(ByteOrder.nativeOrder()!=ByteOrder.LITTLE_ENDIAN)throw new IOException("Stage-one adapter requires a little-endian host");
        Path actual=path.toRealPath();
        if(loaded==null)loaded=new Library(actual);
        else if(!loaded.path.equals(actual))throw new IOException("A different core library is already loaded; restart before changing it");
        return loaded;
    }
    public static CoreBridge open(int workers,int queue,int maximum)throws IOException {
        Path nativeLibrary=com.winterkore.engine.WkeNativeLibraries.resolve("darkwinter.core.library","wke_core.dll");
        return new CoreBridge(library(nativeLibrary),workers,queue,maximum);
    }
    private CoreBridge(Library library,int workers,int queue,int maximum)throws IOException {
        this.library=library;arena=Arena.ofShared();
        MemorySegment created=MemorySegment.NULL;
        try {
            cells=arena.allocate(CELLS*4L,4);bytes=arena.allocate(32768,8);word=arena.allocate(8,8);
            int result=call("create",workers,queue,maximum,word);
            check(result,"create");
            created=word.get(ADDRESS,0);
            if(created.equals(MemorySegment.NULL))throw new IOException("Native core returned a null handle");
            handle=created;
        } catch(Throwable failure) {
            if(!created.equals(MemorySegment.NULL))call("destroy",created);
            arena.close();
            if(failure instanceof IOException io)throw io;
            throw new IOException("Cannot create core",failure);
        }
    }
    // Rare control/IO calls use this helper. Section submission/polling below use exact typed handles.
    private int call(String name,Object... arguments)throws IOException {
        try{return (int)library.methods.get(name).invokeWithArguments(arguments);}
        catch(Throwable failure){throw new IOException("Core call "+name+" failed",failure);}
    }
    private void live(){if(closed)throw new IllegalStateException("Core is closed");}
    private static void check(int code,String action)throws IOException {
        if(code!=0)throw new IOException("Core "+action+" result="+code);
    }
    public synchronized void define(int id,boolean air,String state)throws IOException {
        live();byte[] utf8=state.getBytes(StandardCharsets.UTF_8);
        if(utf8.length==0||utf8.length>4096)throw new IOException("Invalid material name length");
        MemorySegment.copy(MemorySegment.ofArray(utf8),0,bytes,0,utf8.length);
        check(call("define_material",handle,id,air?1:0,bytes,utf8.length),"define material");
    }
    public synchronized void put(Key key,int[] input)throws IOException {
        live();if(input.length!=CELLS)throw new IllegalArgumentException("Section must contain 4096 cells");
        MemorySegment.copy(MemorySegment.ofArray(input),0,cells,0,CELLS*4L);
        try {
            int result=(int)library.methods.get("put").invokeExact(handle,key.x(),key.y(),key.z(),key.revision(),cells,CELLS);
            check(result,"put");
        }catch(IOException failure){throw failure;}catch(Throwable failure){throw new IOException("Core put failed",failure);}
    }
    public synchronized long prepare(Key key,long dependency)throws IOException {
        live();
        try {
            int result=(int)library.methods.get("prepare").invokeExact(handle,key.x(),key.y(),key.z(),key.revision(),dependency,word);
            check(result,"prepare");return word.get(JAVA_LONG,0);
        }catch(IOException failure){throw failure;}catch(Throwable failure){throw new IOException("Core prepare failed",failure);}
    }
    public synchronized int poll(long ticket)throws IOException {
        live();
        try{return (int)library.methods.get("poll").invokeExact(handle,ticket);}
        catch(Throwable failure){throw new IOException("Core poll failed",failure);}
    }
    public synchronized int cancel(long ticket)throws IOException {live();return call("cancel",handle,ticket);}
    public synchronized void release(long ticket)throws IOException {live();check(call("release",handle,ticket),"release");}
    public int await(long ticket)throws IOException {
        long deadline=System.nanoTime()+20_000_000_000L;
        for(;;) {
            int result=poll(ticket);if(result!=PENDING)return result;
            if(Thread.currentThread().isInterrupted()){cancel(ticket);throw new IOException("Core wait interrupted");}
            if(System.nanoTime()-deadline>=0){cancel(ticket);throw new IOException("Core wait deadline");}
            LockSupport.parkNanos(100_000);
        }
    }
    public synchronized int[] section(Key key)throws IOException {
        live();check(call("copy_section",handle,key.x(),key.y(),key.z(),cells,CELLS,word),"read section");
        if(word.get(JAVA_LONG,0)!=key.revision())throw new IOException("Section revision changed");
        return cells.toArray(JAVA_INT);
    }
    public synchronized byte[] prepared(Key key)throws IOException {
        live();check(call("copy_prepared",handle,key.x(),key.y(),key.z(),key.revision(),bytes,PREPARED_BYTES),"read prepared");
        return bytes.asSlice(0,PREPARED_BYTES).toArray(JAVA_BYTE);
    }
    public synchronized long stat(int field)throws IOException {
        live();
        try {
            long value=(long)library.methods.get("stat").invokeExact(handle,field);
            if(value<0)throw new IOException("Invalid native statistic");return value;
        }catch(IOException failure){throw failure;}catch(Throwable failure){throw new IOException("Core stat failed",failure);}
    }
    public synchronized List<Key> index()throws IOException {
        live();int count=Math.toIntExact(stat(5));
        try(Arena temporary=Arena.ofConfined()) {
            MemorySegment xyz=temporary.allocate(Math.max(1,count*12L),4),revisions=temporary.allocate(Math.max(1,count*8L),8);
            check(call("copy_index",handle,xyz,revisions,count),"read index");
            var result=new ArrayList<Key>(count);
            for(int i=0;i<count;i++)result.add(new Key(xyz.getAtIndex(JAVA_INT,i*3L),xyz.getAtIndex(JAVA_INT,i*3L+1),
                    xyz.getAtIndex(JAVA_INT,i*3L+2),revisions.getAtIndex(JAVA_LONG,i)));
            return result;
        }
    }
    public void prepareAll()throws IOException {
        List<Long> tasks=new ArrayList<>();
        for(Key key:index())tasks.add(prepare(key,0));
        for(long ticket:tasks){int result=await(ticket);release(ticket);check(result,"prepare completion");}
    }
    private synchronized void file(String operation,Path path)throws IOException {
        live();byte[] utf8=path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8);
        if(utf8.length==0||utf8.length>32768)throw new IOException("Core path length");
        MemorySegment.copy(MemorySegment.ofArray(utf8),0,bytes,0,utf8.length);
        check(call(operation,handle,bytes,utf8.length),operation);
    }
    public void save(Path path)throws IOException {file("save",path);}
    public void load(Path path)throws IOException {file("load",path);}
    public Map<String,Long> statistics()throws IOException {
        String[] names={"workers","queued","running","handles","peakRunning","sections","materials","snapshotLogicalBytes",
                "preparedLogicalBytes","published","staleResults"};
        Map<String,Long> result=new LinkedHashMap<>();
        for(int i=0;i<names.length;i++)result.put(names[i],stat(i));
        return result;
    }
    @Override public synchronized void close()throws IOException {
        if(closed)return;closed=true;
        try{check(call("destroy",handle),"destroy");}finally{arena.close();}
    }
}
