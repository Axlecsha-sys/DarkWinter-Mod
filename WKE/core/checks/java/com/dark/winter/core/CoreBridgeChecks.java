// Runs the exact production FFM adapter against the copied real-world snapshot.
package com.dark.winter.core;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public final class CoreBridgeChecks {
    private static int checks;
    private static void require(boolean value,String detail){checks++;if(!value)throw new AssertionError(detail);}
    private static void rejected(Io action,String detail)throws Exception {
        boolean rejected=false;try{action.run();}catch(IOException|IllegalArgumentException|IllegalStateException expected){rejected=true;}
        require(rejected,detail);
    }
    @FunctionalInterface private interface Io {void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        Path file=Path.of(args[0]);String hash=CoreImport.sha(file);
        byte[] raw=Files.readAllBytes(file);ByteBuffer header=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        require(Arrays.equals(Arrays.copyOf(raw,8),new byte[]{'D','W','V','O','X','E','L',0}),"independent DWV magic");
        require(header.getInt(8)==1&&header.getInt(12)==16,"DWV version and cell side");
        require(header.getLong(24)==raw.length-40,"DWV payload size");
        long fnv=0xcbf29ce484222325L;for(int i=40;i<raw.length;i++){fnv^=raw[i]&255;fnv*=0x100000001b3L;}
        require(fnv==header.getLong(32),"independent Java/native format checksum");
        require(CoreImport.state("minecraft:stone").equals("minecraft:stone"),"26.3 string palette");
        require(CoreImport.state(Map.of("","minecraft:air")).equals("minecraft:air"),"26.3 mixed-list wrapper");
        var properties=Map.of("axis","z","distance","8","index","1");
        require(CoreImport.state(Map.of("id","darkwinter:utility_wire","properties",properties))
                .equals("darkwinter:utility_wire[axis=z,distance=8,index=1]"),"26.3 property identity and ordering");
        require(CoreImport.state(Map.of("Name","darkwinter:utility_wire","Properties",properties))
                .equals("darkwinter:utility_wire[axis=z,distance=8,index=1]"),"legacy compound palette");
        rejected(()->CoreImport.state(Map.of("id","../../invalid")),"invalid palette identifier");
        rejected(()->CoreBridge.open(0,128,128),"invalid core configuration");
        Map<CoreBridge.Key,byte[]> baseline=new LinkedHashMap<>();
        Map<String,Long> stats;
        try(CoreBridge serial=CoreBridge.open(1,4096,4096);CoreBridge parallel=CoreBridge.open(4,4096,4096)) {
            serial.load(file);parallel.load(file);
            require(serial.index().equals(parallel.index())&&!serial.index().isEmpty(),"section index parity");
            require(serial.stat(6)>1,"real material dictionary");
            serial.prepareAll();parallel.prepareAll();
            for(var key:serial.index()) {
                require(Arrays.equals(serial.section(key),parallel.section(key)),"native state parity");
                byte[] one=serial.prepared(key),many=parallel.prepared(key);
                require(Arrays.equals(one,many),"FFM serial/parallel hierarchy parity");baseline.put(key,one);
            }
            var first=serial.index().getFirst();
            rejected(()->serial.put(first,new int[5]),"physical input length guard");
            rejected(()->serial.save(file),"save cannot replace input");
            require(hash.equals(CoreImport.sha(file)),"input file preserved");
            long task=parallel.prepare(first,0);int cancel=parallel.cancel(task),result=parallel.await(task);parallel.release(task);
            require(cancel==0?result==-4:cancel==-2&&result==0,"cancellation/publication boundary");
            stats=parallel.statistics();require(stats.get("handles")==0&&stats.get("queued")==0&&stats.get("running")==0,"no retained work handles");
        }
        for(int cycle=0;cycle<20;cycle++) {
            CoreBridge owned=CoreBridge.open(2,8,8);
            require(owned.stat(7)==0,"empty core owns no section payload");
            owned.close();owned.close();
            rejected(()->owned.stat(0),"closed owner rejects native access");
        }
        rejected(()->Nbt.root(new byte[]{10,0,0,123,0,0}),"unknown NBT tag rejected");
        rejected(()->Nbt.root(new byte[]{10,0,0,7,0,1,65,-1,-1,-1,-1}),"negative NBT array rejected");
        var report=new LinkedHashMap<String,Object>();report.put("completed",true);report.put("checks",checks);
        report.put("sections",baseline.size());report.put("worldSha256",hash);report.put("native",stats);
        report.put("scope","Real copied-world FFM, serial/parallel parity, input bounds and explicit owner lifecycle; not FPS");
        String json=CoreJson.text(report);Files.writeString(Path.of(file+".bridge.json"),json+"\n");System.out.println(json);
    }
}
