// Checksummed active-world weather manifest and bounded, immutable archive pages.
package com.winterkore.engine.surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/** Weather advances only through host ticks; opening a save never applies wall-clock weather. */
public final class SnowWeatherTimelineStore {
    private static final int MAGIC=0x574b4554,PAGE_MAGIC=0x574b4550,VERSION=1,PHASE_BYTES=169;
    private static final int MAX_MANIFEST_BYTES=44+(SnowWeatherTimeline.PAGE_PHASES+1)*PHASE_BYTES;
    private static final int MAX_PAGE_BYTES=36+SnowWeatherTimeline.PAGE_PHASES*PHASE_BYTES;
    private SnowWeatherTimelineStore(){}
    public static SnowWeatherTimeline load(Path directory,long currentTick)throws IOException {
        Path file=directory.resolve("weather.bin");
        SnowWeatherTimeline timeline;
        if(!Files.exists(file))timeline=new SnowWeatherTimeline(currentTick);
        else {
            ByteBuffer b=checked(file,MAX_MANIFEST_BYTES);
            try {
                if(b.getInt()!=MAGIC||b.getInt()!=VERSION)throw new IllegalArgumentException("Weather manifest identity");
                long epoch=b.getLong(),tick=b.getLong(),archived=b.getLong();int count=b.getInt();
                if(epoch<=0||tick<0||archived<0||archived%SnowWeatherTimeline.PAGE_PHASES!=0||count<0||count>SnowWeatherTimeline.PAGE_PHASES)
                    throw new IllegalArgumentException("Weather manifest bounds");
                var active=readPhase(b);
                var closed=new ArrayList<SnowWeatherTimeline.Phase>(count);
                for(int i=0;i<count;i++){
                    var phase=readPhase(b);if(phase.id!=archived+i)throw new IllegalArgumentException("Weather phase sequence");closed.add(phase);
                }
                if(active.id!=archived+count||active.endTick!=tick||b.remaining()!=0)throw new IllegalArgumentException("Weather active phase");
                timeline=new SnowWeatherTimeline(epoch,tick,archived,closed,active);
            }catch(RuntimeException ex){throw new IOException("Invalid WKE weather history; metadata was not reset: "+file,ex);}
            timeline.resumeAt(currentTick);
        }
        attach(directory,timeline);return timeline;
    }
    public static void save(Path directory,SnowWeatherTimeline timeline)throws IOException {
        attach(directory,timeline);
        ByteBuffer b=ByteBuffer.allocate(MAX_MANIFEST_BYTES).order(ByteOrder.BIG_ENDIAN);
        b.putInt(MAGIC).putInt(VERSION).putLong(timeline.epoch).putLong(timeline.tick).putLong(timeline.archivedCount).putInt(timeline.hotClosed.size());
        writePhase(b,timeline.active);for(var phase:timeline.hotClosed)writePhase(b,phase);
        finish(directory.resolve("weather.bin"),b);
    }
    private static void attach(Path directory,SnowWeatherTimeline timeline) {
        timeline.attach(new SnowWeatherTimeline.Archive(){
            private Path page(long first){return directory.resolve("weather-"+timeline.epoch+"-"+first+".bin");}
            public void write(long first,List<SnowWeatherTimeline.Phase> phases)throws IOException {
                if(first<0||first%SnowWeatherTimeline.PAGE_PHASES!=0||phases.size()!=SnowWeatherTimeline.PAGE_PHASES)
                    throw new IOException("Invalid snow history archive page");
                ByteBuffer b=ByteBuffer.allocate(MAX_PAGE_BYTES).order(ByteOrder.BIG_ENDIAN);
                b.putInt(PAGE_MAGIC).putInt(VERSION).putLong(timeline.epoch).putLong(first).putInt(phases.size());
                for(int i=0;i<phases.size();i++){
                    if(phases.get(i).id!=first+i)throw new IOException("Invalid snow archive phase identity");
                    writePhase(b,phases.get(i));
                }
                finish(page(first),b);
            }
            public List<SnowWeatherTimeline.Phase> read(long first)throws IOException {
                ByteBuffer b=checked(page(first),MAX_PAGE_BYTES);
                try {
                    if(b.getInt()!=PAGE_MAGIC||b.getInt()!=VERSION||b.getLong()!=timeline.epoch||b.getLong()!=first
                            ||b.getInt()!=SnowWeatherTimeline.PAGE_PHASES)throw new IllegalArgumentException("Snow archive identity");
                    var result=new ArrayList<SnowWeatherTimeline.Phase>(SnowWeatherTimeline.PAGE_PHASES);
                    for(int i=0;i<SnowWeatherTimeline.PAGE_PHASES;i++){
                        var phase=readPhase(b);if(phase.id!=first+i)throw new IllegalArgumentException("Snow archive phase sequence");result.add(phase);
                    }
                    if(b.remaining()!=0)throw new IllegalArgumentException("Snow archive length");return List.copyOf(result);
                }catch(RuntimeException ex){throw new IOException("Invalid snow weather archive: "+page(first),ex);}
            }
        });
    }
    private static void writePhase(ByteBuffer b,SnowWeatherTimeline.Phase phase){
        b.putLong(phase.id).putLong(phase.startTick).putLong(phase.endTick).put((byte)(phase.melting?1:0)).putDouble(phase.vertical).putDouble(phase.melt);
        for(double value:phase.snow)b.putDouble(value);for(double value:phase.wind)b.putDouble(value);
    }
    private static SnowWeatherTimeline.Phase readPhase(ByteBuffer b) {
        long id=b.getLong(),start=b.getLong(),end=b.getLong();int melting=Byte.toUnsignedInt(b.get());
        double vertical=b.getDouble(),melt=b.getDouble();double[] snow=new double[SnowWeatherTimeline.DIRECTIONS],wind=new double[SnowWeatherTimeline.DIRECTIONS];
        for(int i=0;i<snow.length;i++)snow[i]=b.getDouble();for(int i=0;i<wind.length;i++)wind[i]=b.getDouble();
        if(id<0||start<0||end<start||melting>1)throw new IllegalArgumentException("Snow phase bounds");
        // Reuse the cursor's finite/integral validation before accepting persistent history.
        new SnowWeatherTimeline.Mark(1,id,end,vertical,melt,snow,wind);
        return new SnowWeatherTimeline.Phase(id,start,end,melting!=0,vertical,melt,snow,wind);
    }
    private static ByteBuffer checked(Path path,int maximum)throws IOException {
        long size=Files.size(path);if(size<16||size>maximum)throw new IOException("Invalid snow history size: "+path);
        byte[] bytes;try(var input=Files.newInputStream(path)){bytes=input.readNBytes(maximum+1);}
        if(bytes.length!=size)throw new IOException("Snow history changed during bounded read: "+path);
        CRC32 crc=new CRC32();crc.update(bytes,0,bytes.length-8);
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if(b.getLong(bytes.length-8)!=crc.getValue())throw new IOException("Snow history checksum mismatch: "+path);
        b.limit(bytes.length-8);return b;
    }
    private static void finish(Path path,ByteBuffer b)throws IOException {
        CRC32 crc=new CRC32();crc.update(b.array(),0,b.position());b.putLong(crc.getValue());
        SnowSurfaceStore.atomicWrite(path,java.util.Arrays.copyOf(b.array(),b.position()));
    }
}
