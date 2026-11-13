package com.dark.winter.placement;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
/** Bounded authoritative snapshot; clients never submit offsets or arbitrary transforms. */
public record AnchorPayload(String dimension,long revision,int page,int pages,List<Anchor> anchors) implements CustomPacketPayload {
    public static final Type<AnchorPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("darkwinter","surface_anchors"));
    public static final StreamCodec<FriendlyByteBuf,AnchorPayload> CODEC=new StreamCodec<>(){
        public AnchorPayload decode(FriendlyByteBuf b){
            String dimension=b.readUtf(256);long revision=b.readLong();int page=b.readVarInt(),pages=b.readVarInt();
            if(pages<1||pages>256||page<0||page>=pages)throw new IllegalArgumentException("Anchor pages");
            int count=b.readVarInt();if(count<0||count>256)throw new IllegalArgumentException("Anchor snapshot size");
            var list=new ArrayList<Anchor>(count);
            for(int n=0;n<count;n++){
                BlockPos owner=b.readBlockPos(),support=b.readBlockPos();int ordinal=b.readUnsignedByte();
                if(ordinal>=Direction.values().length)throw new IllegalArgumentException("Anchor direction");
                Vec3 offset=new Vec3(b.readDouble(),b.readDouble(),b.readDouble()),point=new Vec3(b.readDouble(),b.readDouble(),b.readDouble());
                int size=b.readUnsignedByte();if(size<1||size>16)throw new IllegalArgumentException("Anchor parts");
                var members=new ArrayList<BlockPos>(size);for(int j=0;j<size;j++)members.add(b.readBlockPos());
                list.add(new Anchor(owner,support,Direction.values()[ordinal],offset,point,members,b.readDouble()));
            }return new AnchorPayload(dimension,revision,page,pages,List.copyOf(list));
        }
        public void encode(FriendlyByteBuf b,AnchorPayload payload){
            b.writeUtf(payload.dimension,256);b.writeLong(payload.revision);b.writeVarInt(payload.page);b.writeVarInt(payload.pages);b.writeVarInt(payload.anchors.size());for(Anchor a:payload.anchors){
                b.writeBlockPos(a.owner());b.writeBlockPos(a.support());b.writeByte(a.face().ordinal());
                b.writeDouble(a.offset().x);b.writeDouble(a.offset().y);b.writeDouble(a.offset().z);
                b.writeDouble(a.point().x);b.writeDouble(a.point().y);b.writeDouble(a.point().z);
                b.writeByte(a.members().size());for(BlockPos p:a.members())b.writeBlockPos(p);
                b.writeDouble(a.yaw());
            }
        }
    };
    public AnchorPayload {anchors=List.copyOf(anchors);if(anchors.size()>256||pages<1||pages>256||page<0||page>=pages)throw new IllegalArgumentException("Invalid anchor page");}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
