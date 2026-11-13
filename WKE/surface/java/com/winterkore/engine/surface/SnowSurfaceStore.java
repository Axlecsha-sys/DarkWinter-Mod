// Atomic, checksummed per-chunk persistence; portable outside Minecraft.
package com.winterkore.engine.surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;

public final class SnowSurfaceStore {
    private static final int MAGIC=0x574b4553,VERSION=6;
    private static final int HEADER=20,ROW=17,LEGACY_CONTACT=29,CONTACT=50,MARK=169,POLICY=18,LEGACY_MAX_CONTACTS=128;
    /** Decoder/allocation guard, not a trail-residency limit. A full ledger is never silently truncated. */
    public static final int MAX_BYTES=Integer.MAX_VALUE-8;
    private SnowSurfaceStore(){}
    public static SnowSurface load(Path path,int chunkX,int chunkZ,long tick)throws IOException {
        if(!Files.exists(path))return new SnowSurface(tick);
        long size=Files.size(path);if(size<HEADER+SnowSurface.COLUMNS*ROW+8||size>MAX_BYTES)throw new IOException("Invalid WKE snow surface size: "+path);
        try(var input=Files.newInputStream(path)){
            byte[] header=input.readNBytes(HEADER);if(header.length!=HEADER)throw new IOException("Truncated WKE surface header: "+path);
            ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
            if(h.getInt()!=MAGIC)throw new IllegalArgumentException("Surface magic");
            int version=h.getInt();
            if(version<2||version>VERSION||h.getInt()!=chunkX||h.getInt()!=chunkZ)throw new IllegalArgumentException("Surface identity/version");
            int count=h.getInt();
            if(count<0||version<6&&count>LEGACY_MAX_CONTACTS)throw new IllegalArgumentException("Surface contact count");
            int rowMinimum=ROW+(version>=3?2:0)+(version>=5?POLICY:0);
            int rowMaximum=ROW+(version>=3?MARK+1:0)+(version>=5?POLICY:0);
            int contactBytes=version==2?LEGACY_CONTACT:version==3?37:version==4?38:CONTACT;
            long minimum=HEADER+(long)SnowSurface.COLUMNS*rowMinimum+(long)count*(contactBytes+(version>=3?1:0))+8;
            long maximum=HEADER+(long)SnowSurface.COLUMNS*rowMaximum+(long)count*(contactBytes+(version>=3?MARK:0))+8;
            if(size<minimum||size>maximum)throw new IllegalArgumentException("Surface record count/length");
            byte[] bytes=new byte[(int)size];System.arraycopy(header,0,bytes,0,HEADER);
            if(input.readNBytes(bytes,HEADER,bytes.length-HEADER)!=bytes.length-HEADER||input.read()!=-1)
                throw new IOException("WKE surface changed during bounded read: "+path);
            CRC32 crc=new CRC32();crc.update(bytes,0,bytes.length-8);
            ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);b.position(HEADER);
            if(b.getLong(bytes.length-8)!=crc.getValue())throw new IllegalArgumentException("Surface checksum");
            SnowSurface surface=new SnowSurface(tick);
            for(int i=0;i<SnowSurface.COLUMNS;i++){
                int y=b.getInt();float depth=b.getFloat(),base=b.getFloat(),compacted=b.getFloat();int layers=Byte.toUnsignedInt(b.get());
                if(y!=Integer.MIN_VALUE&&(y< -2048||y>2048)||!Float.isFinite(depth)||!Float.isFinite(compacted)
                        ||depth<0||depth>(version>=5?SnowSurface.MAX_MASS_MM:1000)||!Float.isFinite(base)||base<0||base>depth
                        ||base>1000||compacted<0||compacted>(version>=5?depth:depth-base)||layers>8
                        ||depth-compacted>SnowSurface.MAX_DEPTH_MM+.001f)
                    throw new IllegalArgumentException("Surface row");
                surface.y[i]=y;surface.depth[i]=depth;surface.baseDepth[i]=base;surface.compacted[i]=compacted;surface.projected[i]=(byte)layers;
                if(version==2)surface.rebaseWeather[i]=y!=Integer.MIN_VALUE;
                else {surface.weatherAt[i]=readMark(b);surface.rebaseWeather[i]=readBoolean(b);}
                if(version>=5){
                    int material=Byte.toUnsignedInt(b.get());
                    if(material>=SnowSurface.Material.values().length)throw new IllegalArgumentException("Surface support material");
                    float potential=b.getFloat();boolean trap=readBoolean(b);
                    surface.supports[i]=new SnowSurface.Support(SnowSurface.Material.values()[material],potential,trap);
                    float budget=b.getFloat(),legacy=b.getFloat(),pathDensity=b.getFloat();
                    if(!Float.isFinite(budget)||budget<compacted||budget>SnowSurface.MAX_MASS_MM
                            ||!Float.isFinite(legacy)||legacy<0||legacy>1||!Float.isFinite(pathDensity)||pathDensity<0||pathDensity>1)
                        throw new IllegalArgumentException("Surface dynamics");
                    surface.settlementBudget[i]=budget;surface.legacyPackingFraction[i]=legacy;surface.pathCompaction[i]=pathDensity;
                }else{
                    // Old metadata stores existing height loss, not a new event to replay after migration.
                    surface.settlementBudget[i]=compacted;
                    surface.legacyPackingFraction[i]=depth>0?compacted/depth:0;
                }
            }
            for(int i=0;i<count;i++){
                float x=b.getFloat(),y=b.getFloat(),z=b.getFloat(),yaw=b.getFloat(),width=b.getFloat(),length=b.getFloat(),cover=b.getFloat();
                boolean vehicle=readBoolean(b);
                float contactDepth=version==2?(vehicle?60:36):b.getFloat(),compaction=version==2?(vehicle?.8f:.65f):b.getFloat();
                int soleProfile=version>=4?Byte.toUnsignedInt(b.get()):0;
                int ownerY=version>=5?b.getInt():Integer.MIN_VALUE;
                long ageTicks=version>=5?b.getLong():0;
                var contact=new SnowSurface.Contact(x,y,z,yaw,width,length,cover,vehicle,contactDepth,compaction,soleProfile,ownerY,ageTicks);
                if(version==5&&ageTicks>0){
                    // V5 already weathered the visible hole through a synthetic age fade. Bake
                    // that attained relief into cover once; migrating must never deepen old tracks.
                    double fade=1-ageTicks/(double)SnowSurface.TRACK_LIFETIME_TICKS;
                    float visible=(float)(Math.max(0,contactDepth-cover)*fade*fade),migratedCover=contactDepth-visible;
                    if(contactDepth-migratedCover>visible)migratedCover=Math.nextUp(migratedCover);
                    contact=new SnowSurface.Contact(x,y,z,yaw,width,length,migratedCover,vehicle,contactDepth,compaction,soleProfile,ownerY,0);
                }
                surface.contacts.add(contact);
                surface.contactWeatherAt.add(version==2?null:readMark(b));
            }
            if(b.remaining()!=8||b.getLong()!=crc.getValue())throw new IllegalArgumentException("Surface checksum/length");
            surface.clean();return surface;
        }catch(RuntimeException ex){throw new IOException("Invalid WKE surface; metadata was not reset: "+path,ex);}
    }
    public static void save(Path path,int chunkX,int chunkZ,SnowSurface surface)throws IOException {
        var contacts=surface.contacts();
        long encoded=HEADER+(long)SnowSurface.COLUMNS*(ROW+1+POLICY)+8;
        for(var mark:surface.weatherAt)encoded+=mark==null?1:MARK;
        for(var mark:surface.contactWeatherAt)encoded+=CONTACT+(mark==null?1:MARK);
        if(encoded>MAX_BYTES)throw new IOException("WKE surface ledger exceeds the supported atomic file size: "+path);
        ByteBuffer b=ByteBuffer.allocate((int)encoded).order(ByteOrder.BIG_ENDIAN);
        b.putInt(MAGIC).putInt(VERSION).putInt(chunkX).putInt(chunkZ).putInt(contacts.size());
        for(int i=0;i<SnowSurface.COLUMNS;i++){
            b.putInt(surface.y[i]).putFloat(surface.depth[i]).putFloat(surface.baseDepth[i]).putFloat(surface.compacted[i]).put(surface.projected[i]);
            writeMark(b,surface.weatherAt[i]);b.put((byte)(surface.rebaseWeather[i]?1:0));
            var support=surface.supports[i];
            b.put((byte)support.material().ordinal()).putFloat(support.driftPotential()).put((byte)(support.driftTrap()?1:0));
            b.putFloat(surface.settlementBudget[i]).putFloat(surface.legacyPackingFraction[i]).putFloat(surface.pathCompaction[i]);
        }
        for(int i=0;i<contacts.size();i++){
            var c=contacts.get(i);
            b.putFloat(c.x()).putFloat(c.y()).putFloat(c.z()).putFloat(c.yaw()).putFloat(c.width()).putFloat(c.length()).putFloat(c.cover()).put((byte)(c.vehicle()?1:0));
            b.putFloat(c.depthMillimeters()).putFloat(c.compaction()).put((byte)c.soleProfile()).putInt(c.ownerY()).putLong(c.ageTicks());
            writeMark(b,surface.contactWeatherAt.get(i));
        }
        CRC32 crc=new CRC32();crc.update(b.array(),0,b.position());b.putLong(crc.getValue());
        byte[] bytes=java.util.Arrays.copyOf(b.array(),b.position());
        atomicWrite(path,bytes);surface.clean();
    }
    private static boolean readBoolean(ByteBuffer b){
        int value=Byte.toUnsignedInt(b.get());if(value>1)throw new IllegalArgumentException("Surface boolean flag");return value!=0;
    }
    static void writeMark(ByteBuffer b,SnowWeatherTimeline.Mark mark){
        b.put((byte)(mark==null?0:1));if(mark==null)return;
        b.putLong(mark.epoch()).putLong(mark.phase()).putLong(mark.tick()).putDouble(mark.verticalSnowMillimeters()).putDouble(mark.meltMillimeters());
        for(int i=0;i<SnowWeatherTimeline.DIRECTIONS;i++)b.putDouble(mark.snowMillimeters(i));
        for(int i=0;i<SnowWeatherTimeline.DIRECTIONS;i++)b.putDouble(mark.windFillMillimeters(i));
    }
    static SnowWeatherTimeline.Mark readMark(ByteBuffer b){
        int present=Byte.toUnsignedInt(b.get());if(present==0)return null;if(present!=1)throw new IllegalArgumentException("Surface cursor flag");
        long epoch=b.getLong(),phase=b.getLong(),tick=b.getLong();double vertical=b.getDouble(),melt=b.getDouble();
        double[] snow=new double[SnowWeatherTimeline.DIRECTIONS],wind=new double[SnowWeatherTimeline.DIRECTIONS];
        for(int i=0;i<snow.length;i++)snow[i]=b.getDouble();for(int i=0;i<wind.length;i++)wind[i]=b.getDouble();
        return new SnowWeatherTimeline.Mark(epoch,phase,tick,vertical,melt,snow,wind);
    }
    static void atomicWrite(Path path,byte[] bytes)throws IOException {
        Path parent=path.toAbsolutePath().getParent();Files.createDirectories(parent);Path temporary=Files.createTempFile(parent,"snow-",".tmp");
        try{
            Files.write(temporary,bytes);
            try{Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException ex){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
}
