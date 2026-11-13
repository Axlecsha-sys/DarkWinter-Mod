package com.winterkore.engine.surface.host;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Bounded authoritative presentation snapshot. No client contacts or world edits are accepted. */
public record SnowSurfacePayload(String dimension,long tick,List<Mark> marks,List<Column> columns) implements CustomPacketPayload {
    public static final int MAX_MARKS=512,MAX_COLUMNS=2048;
    public static final Type<SnowSurfacePayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("wke","snow_surface"));
    public record Column(int x,int y,int z,int layers,float deltaMillimeters,int mask,int crust,float baseOffset,List<SnowHostGeometry.Patch> patches,float pathCompactionFraction) {
        public Column(int x,int y,int z,int layers,float deltaMillimeters,int mask,int crust,float baseOffset,List<SnowHostGeometry.Patch> patches){this(x,y,z,layers,deltaMillimeters,mask,crust,baseOffset,patches,0);}
        public Column(int x,int y,int z,int layers,float deltaMillimeters){this(x,y,z,layers,deltaMillimeters,0,0,layers==0?-.125f:0,List.of());}
        public Column(SnowHostGeometry.Cell geometry,float deltaMillimeters){this(geometry,deltaMillimeters,0);}
        public Column(SnowHostGeometry.Cell geometry,float deltaMillimeters,float pathCompactionFraction){
            this(geometry.pos().getX(),geometry.pos().getY(),geometry.pos().getZ(),geometry.layers(),deltaMillimeters,
                    geometry.mask(),geometry.crust(),(float)geometry.baseOffset(),geometry.patches(),pathCompactionFraction);
        }
        public SnowHostGeometry.Cell geometry(){
            return new SnowHostGeometry.Cell(new net.minecraft.core.BlockPos(x,y,z),layers,mask,crust,baseOffset,patches);
        }
        public Column {
            if(Math.abs((long)x)>30000000||Math.abs((long)z)>30000000||y< -2048||y>2048||layers<0||layers>8
                    ||!Float.isFinite(deltaMillimeters)||deltaMillimeters< -1500||deltaMillimeters>1500||mask<0||mask>255
                    ||!Float.isFinite(baseOffset)||baseOffset< -1||baseOffset>1||patches==null||patches.size()>SnowHostGeometry.MAX_PATCHES||!Float.isFinite(pathCompactionFraction)||pathCompactionFraction<0||pathCompactionFraction>1)
                throw new IllegalArgumentException("Invalid snow surface column");
            patches=List.copyOf(patches);
        }
    }
    public record Mark(double x,float y,double z,float yaw,float width,float length,float strength,boolean vehicle,float depthMillimeters,int soleProfile,int ownerY) {
        public Mark(double x,float y,double z,float yaw,float width,float length,float strength,boolean vehicle,float depthMillimeters,int soleProfile){this(x,y,z,yaw,width,length,strength,vehicle,depthMillimeters,soleProfile,Integer.MIN_VALUE);}
        public Mark(double x,float y,double z,float yaw,float width,float length,float strength,boolean vehicle){this(x,y,z,yaw,width,length,strength,vehicle,(vehicle?60:36)*strength,0);}
        public Mark(double x,float y,double z,float yaw,float width,float length,float strength,boolean vehicle,float depthMillimeters){this(x,y,z,yaw,width,length,strength,vehicle,depthMillimeters,0);}
        public Mark {
            if(!Double.isFinite(x)||!Double.isFinite(z)||Math.abs(x)>30000000||Math.abs(z)>30000000
                    ||!Float.isFinite(y)||y< -2048||y>2048||!Float.isFinite(yaw)
                    ||!Float.isFinite(width)||width<.04f||width>2||!Float.isFinite(length)||length<.08f||length>4
                    ||!Float.isFinite(strength)||strength<0||strength>1||!Float.isFinite(depthMillimeters)||depthMillimeters<0||depthMillimeters>1500||soleProfile<0||soleProfile>4||ownerY!=Integer.MIN_VALUE&&(ownerY< -2048||ownerY>2048))throw new IllegalArgumentException("Invalid snow mark");
        }
    }
    public SnowSurfacePayload(String dimension,long tick,List<Mark> marks){this(dimension,tick,marks,List.of());}
    public SnowSurfacePayload {
        if(dimension==null||dimension.length()>128||tick<0||marks==null||marks.size()>MAX_MARKS||columns==null||columns.size()>MAX_COLUMNS)
            throw new IllegalArgumentException("Invalid surface snapshot");
        marks=List.copyOf(marks);columns=List.copyOf(columns);
    }
    public static final StreamCodec<FriendlyByteBuf,SnowSurfacePayload> CODEC=new StreamCodec<>(){
        @Override public SnowSurfacePayload decode(FriendlyByteBuf b){
            String dimension=b.readUtf(128);long tick=b.readLong();int size=b.readVarInt();
            if(size<0||size>MAX_MARKS)throw new IllegalArgumentException("Snow mark count");
            var marks=new ArrayList<Mark>(size);
            for(int i=0;i<size;i++)marks.add(readMark(b));
            int count=b.readVarInt();if(count<0||count>MAX_COLUMNS)throw new IllegalArgumentException("Snow column count");
            var columns=new ArrayList<Column>(count);
            for(int i=0;i<count;i++)columns.add(readColumn(b));
            return new SnowSurfacePayload(dimension,tick,marks,columns);
        }
        @Override public void encode(FriendlyByteBuf b,SnowSurfacePayload p){
            b.writeUtf(p.dimension(),128);b.writeLong(p.tick());b.writeVarInt(p.marks().size());
            for(var m:p.marks())writeMark(b,m);
            b.writeVarInt(p.columns().size());
            for(var c:p.columns())writeColumn(b,c);
        }
    };
    static Column readColumn(FriendlyByteBuf b){
        int x=b.readInt(),y=b.readInt(),z=b.readInt(),layers=b.readUnsignedByte();
        float delta=b.readFloat();int mask=b.readUnsignedByte(),crust=b.readInt();float offset=b.readFloat();
        int count=b.readUnsignedByte();if(count>SnowHostGeometry.MAX_PATCHES)throw new IllegalArgumentException("Snow support patch count");
        var patches=new ArrayList<SnowHostGeometry.Patch>(count);
        for(int i=0;i<count;i++)patches.add(new SnowHostGeometry.Patch(b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat()));
        return new Column(x,y,z,layers,delta,mask,crust,offset,patches,b.readFloat());
    }
    static void writeColumn(FriendlyByteBuf b,Column c){
        b.writeInt(c.x());b.writeInt(c.y());b.writeInt(c.z());b.writeByte(c.layers());b.writeFloat(c.deltaMillimeters());
        b.writeByte(c.mask());b.writeInt(c.crust());b.writeFloat(c.baseOffset());b.writeByte(c.patches().size());
        for(var p:c.patches()){b.writeFloat(p.minX());b.writeFloat(p.minZ());b.writeFloat(p.maxX());b.writeFloat(p.maxZ());b.writeFloat(p.top());}b.writeFloat(c.pathCompactionFraction());
    }
    static Mark readMark(FriendlyByteBuf b){
        return new Mark(b.readDouble(),b.readFloat(),b.readDouble(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readBoolean(),b.readFloat(),b.readUnsignedByte(),b.readInt());
    }
    static void writeMark(FriendlyByteBuf b,Mark m){
        b.writeDouble(m.x());b.writeFloat(m.y());b.writeDouble(m.z());b.writeFloat(m.yaw());b.writeFloat(m.width());b.writeFloat(m.length());
        b.writeFloat(m.strength());b.writeBoolean(m.vehicle());b.writeFloat(m.depthMillimeters());b.writeByte(m.soleProfile());b.writeInt(m.ownerY());
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
