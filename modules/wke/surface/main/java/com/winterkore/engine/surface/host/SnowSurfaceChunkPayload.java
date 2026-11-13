// Complete snow fields travel in bounded pages; clients publish only fully assembled revisions.
package com.winterkore.engine.surface.host;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SnowSurfaceChunkPayload(String dimension,long tick,List<Long> activeChunks,List<Chunk> chunks) implements CustomPacketPayload {
    public static final int MAX_ACTIVE=64,MAX_CHUNKS=1,MAX_COLUMNS=2048,MAX_HALO=544,MAX_MARKS=512;
    public static final Type<SnowSurfaceChunkPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("wke","snow_chunk_surface"));
    /** Every X/Z in the centre and its one-cell border was visited, including positions with no snow owner. */
    public record Chunk(int x,int z,long revision,long weatherTick,List<SnowSurfacePayload.Column> columns,
            List<SnowSurfacePayload.Column> halo,List<SnowSurfacePayload.Mark> marks,int pageIndex,int pageCount,int totalMarks) {
        /** Logical complete fields have no record-count eviction. The transport splits them below. */
        public Chunk(int x,int z,long revision,long weatherTick,List<SnowSurfacePayload.Column> columns,
                List<SnowSurfacePayload.Column> halo,List<SnowSurfacePayload.Mark> marks){
            this(x,z,revision,weatherTick,columns,halo,marks,0,1,marks==null?0:marks.size());
        }
        public Chunk {
            if(Math.abs((long)x)>1875000||Math.abs((long)z)>1875000||revision<0||weatherTick<0
                    ||columns==null||columns.size()>MAX_COLUMNS||halo==null||halo.size()>MAX_HALO||marks==null
                    ||pageCount<1||pageIndex<0||pageIndex>=pageCount||totalMarks<0)
                throw new IllegalArgumentException("Invalid snow chunk page");
            if(pageCount==1){
                if(pageIndex!=0||marks.size()!=totalMarks)throw new IllegalArgumentException("Incomplete logical snow chunk");
            }else{
                long expectedPages=Math.max(1,((long)totalMarks+MAX_MARKS-1)/MAX_MARKS);
                long start=(long)pageIndex*MAX_MARKS;
                if(pageCount!=expectedPages||marks.size()!=Math.min(MAX_MARKS,(long)totalMarks-start)
                        ||pageIndex!=0&&(!columns.isEmpty()||!halo.isEmpty()))
                    throw new IllegalArgumentException("Inconsistent snow chunk page");
            }
            var positions=new HashSet<Long>();
            for(var c:columns)if((c.x()>>4)!=x||(c.z()>>4)!=z||!positions.add(new net.minecraft.core.BlockPos(c.x(),c.y(),c.z()).asLong()))
                throw new IllegalArgumentException("Snow centre column ownership");
            positions.clear();
            for(var c:halo){
                int dx=c.x()-x*16,dz=c.z()-z*16;
                if(dx< -1||dx>16||dz< -1||dz>16||dx>=0&&dx<16&&dz>=0&&dz<16
                        ||!positions.add(new net.minecraft.core.BlockPos(c.x(),c.y(),c.z()).asLong()))
                    throw new IllegalArgumentException("Snow halo column ownership");
            }
            columns=List.copyOf(columns);halo=List.copyOf(halo);marks=List.copyOf(marks);
        }
        public long key(){return ((long)x&0xffffffffL)|((long)z<<32);}
    }
    public SnowSurfaceChunkPayload {
        if(dimension==null||dimension.length()>128||tick<0||activeChunks==null||activeChunks.size()>MAX_ACTIVE
                ||chunks==null||chunks.size()>MAX_CHUNKS||new HashSet<>(activeChunks).size()!=activeChunks.size())
            throw new IllegalArgumentException("Invalid snow chunk stream");
        activeChunks=List.copyOf(activeChunks);chunks=List.copyOf(chunks);
        for(var c:chunks){
            if(!activeChunks.contains(c.key()))throw new IllegalArgumentException("Inactive snow frame");
            if(c.marks().size()>MAX_MARKS)throw new IllegalArgumentException("Snow field needs transport paging");
        }
    }
    /** Fixed-size transport pages are materialized on demand; no physical contact is discarded. */
    public static List<Chunk> pages(Chunk logical){
        if(logical.pageIndex()!=0||logical.pageCount()!=1||logical.totalMarks()!=logical.marks().size())
            throw new IllegalArgumentException("A complete logical snow field is required");
        int total=logical.totalMarks(),count=(int)Math.max(1,((long)total+MAX_MARKS-1)/MAX_MARKS);
        if(count==1)return List.of(logical);
        return new AbstractList<>(){
            @Override public int size(){return count;}
            @Override public Chunk get(int index){
                if(index<0||index>=count)throw new IndexOutOfBoundsException(index);
                int first=(int)((long)index*MAX_MARKS),end=(int)Math.min((long)total,(long)first+MAX_MARKS);
                return new Chunk(logical.x(),logical.z(),logical.revision(),logical.weatherTick(),
                        index==0?logical.columns():List.of(),index==0?logical.halo():List.of(),
                        logical.marks().subList(first,end),index,count,total);
            }
        };
    }
    private static List<SnowSurfacePayload.Column> readColumns(FriendlyByteBuf b,int limit){
        int size=b.readVarInt();if(size<0||size>limit)throw new IllegalArgumentException("Snow column count");
        var result=new ArrayList<SnowSurfacePayload.Column>(size);
        for(int i=0;i<size;i++)result.add(SnowSurfacePayload.readColumn(b));
        return result;
    }
    private static void writeColumns(FriendlyByteBuf b,List<SnowSurfacePayload.Column> columns){
        b.writeVarInt(columns.size());
        for(var c:columns)SnowSurfacePayload.writeColumn(b,c);
    }
    public static final StreamCodec<FriendlyByteBuf,SnowSurfaceChunkPayload> CODEC=new StreamCodec<>(){
        @Override public SnowSurfaceChunkPayload decode(FriendlyByteBuf b){
            String dimension=b.readUtf(128);long tick=b.readLong();int count=b.readVarInt();
            if(count<0||count>MAX_ACTIVE)throw new IllegalArgumentException("Snow active chunk count");
            var active=new ArrayList<Long>(count);for(int i=0;i<count;i++)active.add(b.readLong());
            count=b.readVarInt();if(count<0||count>MAX_CHUNKS)throw new IllegalArgumentException("Snow frame count");
            var chunks=new ArrayList<Chunk>(count);
            for(int i=0;i<count;i++){
                int x=b.readInt(),z=b.readInt();long revision=b.readLong(),weatherTick=b.readLong();
                var columns=readColumns(b,MAX_COLUMNS);var halo=readColumns(b,MAX_HALO);
                int pageIndex=b.readVarInt(),pageCount=b.readVarInt(),totalMarks=b.readVarInt();
                int size=b.readVarInt();if(size<0||size>MAX_MARKS)throw new IllegalArgumentException("Snow stream mark count");
                var marks=new ArrayList<SnowSurfacePayload.Mark>(size);
                for(int m=0;m<size;m++)marks.add(SnowSurfacePayload.readMark(b));
                chunks.add(new Chunk(x,z,revision,weatherTick,columns,halo,marks,pageIndex,pageCount,totalMarks));
            }
            return new SnowSurfaceChunkPayload(dimension,tick,active,chunks);
        }
        @Override public void encode(FriendlyByteBuf b,SnowSurfaceChunkPayload p){
            b.writeUtf(p.dimension(),128);b.writeLong(p.tick());b.writeVarInt(p.activeChunks().size());
            for(long key:p.activeChunks())b.writeLong(key);
            b.writeVarInt(p.chunks().size());
            for(var c:p.chunks()){
                b.writeInt(c.x());b.writeInt(c.z());b.writeLong(c.revision());b.writeLong(c.weatherTick());
                writeColumns(b,c.columns());writeColumns(b,c.halo());
                b.writeVarInt(c.pageIndex());b.writeVarInt(c.pageCount());b.writeVarInt(c.totalMarks());b.writeVarInt(c.marks().size());
                for(var m:c.marks())SnowSurfacePayload.writeMark(b,m);
            }
        }
    };
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
