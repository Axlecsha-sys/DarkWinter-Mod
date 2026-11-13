// Closed-copy Anvil geometry importer. Unsupported compression/data fails instead of replacing blocks.
package com.dark.winter.core;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

public final class CoreImport {
    private record Raw(CoreBridge.Key key,String[] cells) {}
    private final Path source,region;
    private final Map<Path,String> sourceHashes=new TreeMap<>();
    private int missingChunks;
    private CoreImport(Path source){this.source=source;
        Path modern=source.resolve("dimensions/minecraft/overworld/region");
        region=Files.isDirectory(modern)?modern:source.resolve("region");
    }
    public static boolean air(String state) {
        int split=state.indexOf('[');String name=split<0?state:state.substring(0,split);
        return name.equals("minecraft:air")||name.equals("minecraft:cave_air")||name.equals("minecraft:void_air");
    }
    public static String sha(Path path)throws IOException {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try(var in=Files.newInputStream(path)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.GeneralSecurityException failure){throw new IOException(failure);}
    }
    private void observe(Path file)throws IOException {if(!sourceHashes.containsKey(file))sourceHashes.put(file,sha(file));}
    private Map<String,Object> chunk(int x,int z)throws IOException {
        Path file=region.resolve("r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)+".mca");
        if(!Files.isRegularFile(file)){missingChunks++;return null;}
        observe(file);
        byte[] encoded;int compression;
        try(RandomAccessFile input=new RandomAccessFile(file.toFile(),"r")) {
            int index=Math.floorMod(x,32)+32*Math.floorMod(z,32);
            input.seek(index*4L);int location=input.readInt();
            if(location==0){missingChunks++;return null;}
            int sector=location>>>8,count=location&255;
            long offset=sector*4096L;
            if(sector<2||count==0||offset+count*4096L>input.length())throw new IOException("Region sector bounds");
            input.seek(offset);int length=input.readInt();compression=input.readUnsignedByte();
            if(length<1||length>count*4096-4)throw new IOException("Chunk length bounds");
            if((compression&128)!=0) {
                if(length!=1)throw new IOException("Invalid external chunk marker");
                Path external=region.resolve("c."+x+"."+z+".mcc");
                if(!Files.isRegularFile(external)||Files.size(external)>8*1024*1024)throw new IOException("External chunk bounds");
                observe(external);encoded=Files.readAllBytes(external);compression&=127;
            } else {encoded=new byte[length-1];input.readFully(encoded);}
        }
        InputStream stream=switch(compression) {
            case 1 -> new GZIPInputStream(new ByteArrayInputStream(encoded));
            case 2 -> new InflaterInputStream(new ByteArrayInputStream(encoded));
            case 3 -> new ByteArrayInputStream(encoded);
            default -> throw new IOException("Unsupported Anvil compression "+compression+"; source retained");
        };
        try(stream;var out=new ByteArrayOutputStream()) {
            byte[] b=new byte[8192];for(int n;(n=stream.read(b))!=-1;){if(out.size()+n>8*1024*1024)throw new IOException("Inflated chunk limit");out.write(b,0,n);}
            return Nbt.root(out.toByteArray());
        }
    }
    static String state(Object value)throws IOException {
        // Modern NBT codecs use bare strings or {id,properties}; mixed lists wrap strings in {"":value}.
        Object name=value,properties=null;
        if(value instanceof Map<?,?>) {
            var entry=Nbt.compound(value);
            if(entry.size()==1&&entry.containsKey(""))return state(entry.get(""));
            name=entry.containsKey("id")?entry.get("id"):entry.get("Name");
            properties=entry.containsKey("properties")?entry.get("properties"):entry.get("Properties");
        }
        if(!(name instanceof String id)||!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IOException("Invalid block identifier");
        if(properties==null)return id;
        var sorted=new TreeMap<>(Nbt.compound(properties));var pairs=new ArrayList<String>();
        for(var item:sorted.entrySet()) {
            if(!(item.getValue() instanceof String text)||!item.getKey().matches("[a-zA-Z0-9_.+-]+")||!text.matches("[a-zA-Z0-9_.:+-]+"))
                throw new IOException("Unsupported block property");
            pairs.add(item.getKey()+"="+text);
        }
        return pairs.isEmpty()?id:id+"["+String.join(",",pairs)+"]";
    }
    private static String[] cells(Map<String,Object> section)throws IOException {
        String[] output=new String[4096];
        Object states=section.get("block_states");
        if(states==null){Arrays.fill(output,"minecraft:air");return output;}
        var data=Nbt.compound(states);Object list=data.get("palette");
        if(!(list instanceof List<?> palette)||palette.isEmpty()||palette.size()>4096)throw new IOException("Block palette bounds");
        String[] names=new String[palette.size()];for(int i=0;i<names.length;i++)names[i]=state(palette.get(i));
        if(names.length==1){Arrays.fill(output,names[0]);return output;}
        int bits=Math.max(4,32-Integer.numberOfLeadingZeros(names.length-1)),perLong=64/bits;
        if(!(data.get("data") instanceof long[] packed)||packed.length!=(4096+perLong-1)/perLong)throw new IOException("Packed block-state length");
        long mask=(1L<<bits)-1;
        for(int i=0;i<4096;i++) {
            int index=(int)((packed[i/perLong]>>>((i%perLong)*bits))&mask);
            if(index>=names.length)throw new IOException("Packed block-state index");
            output[i]=names[index];
        }
        return output;
    }
    private List<Raw> read(int minX,int maxX,int minZ,int maxZ,int minY,int maxY)throws IOException {
        if(minX>maxX||minZ>maxZ||minY>maxY||minX<-1875000||maxX>1875000||minZ<-1875000||maxZ>1875000
                ||minY<-2048||maxY>2047||(long)(maxX-minX+1)*(maxZ-minZ+1)*(maxY-minY+1)>4096)
            throw new IOException("Import bounds exceed 4096 sections");
        List<Raw> result=new ArrayList<>();
        for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++) {
            var chunk=chunk(x,z);if(chunk==null)continue;
            if(!(chunk.get("xPos") instanceof Number cx)||cx.intValue()!=x||!(chunk.get("zPos") instanceof Number cz)||cz.intValue()!=z)
                throw new IOException("Chunk coordinate mismatch");
            if(!(chunk.get("sections") instanceof List<?> sections))throw new IOException("Modern section list required");
            Object status=chunk.get("Status");if(status instanceof String text&&!text.equals("full")&&!text.equals("minecraft:full"))
                throw new IOException("Incomplete source chunk "+x+","+z+": "+text);
            Map<Integer,Map<String,Object>> byY=new HashMap<>();
            for(Object item:sections) {
                var section=Nbt.compound(item);if(!(section.get("Y") instanceof Number y))throw new IOException("Section Y missing");
                if(byY.put(y.intValue(),section)!=null)throw new IOException("Duplicate section Y");
            }
            for(int y=minY;y<=maxY;y++) {
                String[] blocks;
                if(byY.containsKey(y))blocks=cells(byY.get(y));else{blocks=new String[4096];Arrays.fill(blocks,"minecraft:air");}
                result.add(new Raw(new CoreBridge.Key(x,y,z,1),blocks));
            }
        }
        if(result.isEmpty())throw new IOException("No saved source chunks in bounds");
        return result;
    }
    private static void verifyPalette(Path file,List<String> expected)throws IOException {
        ByteBuffer input=ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        input.position(16);int count=input.getInt();input.position(40);
        if(count!=expected.size())throw new IOException("DWV palette count mismatch");
        for(int i=0;i<count;i++) {
            int id=input.getInt(),flags=input.getInt(),bytes=input.getInt();
            if(id<0||id>=expected.size()||bytes<1||bytes>4096)throw new IOException("DWV palette entry bounds");
            byte[] name=new byte[bytes];input.get(name);
            if(!new String(name,StandardCharsets.UTF_8).equals(expected.get(id))||flags!=(air(expected.get(id))?1:0))
                throw new IOException("DWV material identity mismatch");
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=8)throw new IllegalArgumentException("copy output.dwv minChunkX maxChunkX minChunkZ maxChunkZ minSectionY maxSectionY");
        Path source=Path.of(args[0]).toAbsolutePath().normalize(),output=Path.of(args[1]).toAbsolutePath().normalize();
        if(!Files.isRegularFile(source.resolve("core-import-copy.json")))throw new IOException("A prepared import copy receipt is required");
        if(output.startsWith(source)||Files.exists(output))throw new IOException("Output must be a new file outside the source copy");
        long started=System.nanoTime();CoreImport importer=new CoreImport(source);
        List<Raw> raw=importer.read(Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4]),Integer.parseInt(args[5]),
                Integer.parseInt(args[6]),Integer.parseInt(args[7]));
        TreeSet<String> names=new TreeSet<>();for(var section:raw)Collections.addAll(names,section.cells());
        names.remove("minecraft:air");List<String> palette=new ArrayList<>();palette.add("minecraft:air");palette.addAll(names);
        Map<String,Integer> ids=new HashMap<>();boolean[] isAir=new boolean[palette.size()];
        for(int i=0;i<palette.size();i++){ids.put(palette.get(i),i);isAir[i]=air(palette.get(i));}
        Map<CoreBridge.Key,int[]> original=new LinkedHashMap<>();long occupied=0,custom=0;
        try(CoreBridge core=CoreBridge.open(4,4096,4096)) {
            for(int i=1;i<palette.size();i++)core.define(i,air(palette.get(i)),palette.get(i));
            for(Raw section:raw) {
                int[] cells=new int[4096];
                for(int i=0;i<4096;i++){String state=section.cells()[i];cells[i]=ids.get(state);if(!isAir[cells[i]])occupied++;if(state.startsWith("darkwinter:"))custom++;}
                original.put(section.key(),cells);core.put(section.key(),cells);
            }
            core.prepareAll();
            for(var entry:original.entrySet()) {
                if(!Arrays.equals(entry.getValue(),core.section(entry.getKey())))throw new IOException("FFM voxel-copy mismatch");
                byte[] prepared=core.prepared(entry.getKey());
                for(int i=0;i<4096;i++)if(((prepared[i/8]>>>(i%8))&1)!=(isAir[entry.getValue()[i]]?0:1))
                    throw new IOException("Prepared leaf occupancy mismatch");
            }
            core.save(output);verifyPalette(output,palette);
        }
        try(CoreBridge restored=CoreBridge.open(3,4096,4096)) {
            restored.load(output);
            for(var entry:original.entrySet())if(!Arrays.equals(entry.getValue(),restored.section(entry.getKey())))throw new IOException("Saved cell roundtrip mismatch");
            restored.prepareAll();
            Map<String,String> evidence=new LinkedHashMap<>();
            for(var entry:importer.sourceHashes.entrySet()) {
                if(!entry.getValue().equals(sha(entry.getKey())))throw new IOException("Import copy changed while being read");
                evidence.put(source.relativize(entry.getKey()).toString(),entry.getValue());
            }
            var report=new LinkedHashMap<String,Object>();report.put("completed",true);report.put("sections",raw.size());report.put("occupiedCells",occupied);
            report.put("darkwinterCells",custom);report.put("materials",palette.size());report.put("missingSourceChunks",importer.missingChunks);
            report.put("palette",palette);report.put("worldSha256",sha(output));report.put("sourceSha256",evidence);
            report.put("wallMs",(System.nanoTime()-started)/1e6);report.put("native",restored.statistics());
            report.put("scope","Copied Anvil block identifiers/properties only; excludes block entities, entities, light, biomes and gameplay state; no FPS claim");
            String json=CoreJson.text(report);Files.writeString(Path.of(output+".import.json"),json+"\n");System.out.println(json);
        }
    }
}
