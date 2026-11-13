package com.dark.winter.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record DeadwoodProgress(BlockPos target,int serial,int phase,int elapsed,int duration) implements CustomPacketPayload {
    public static final int RUNNING=0,COMPLETE=1,CANCELLED=2,NO_SPACE=3,INVALID=4;
    public static final Type<DeadwoodProgress> TYPE=new Type<>(Identifier.fromNamespaceAndPath("darkwinter","deadwood_progress"));
    public static final StreamCodec<FriendlyByteBuf,DeadwoodProgress> CODEC=new StreamCodec<>() {
        public DeadwoodProgress decode(FriendlyByteBuf b){return new DeadwoodProgress(b.readBlockPos(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt());}
        public void encode(FriendlyByteBuf b,DeadwoodProgress p){b.writeBlockPos(p.target);b.writeVarInt(p.serial);b.writeVarInt(p.phase);b.writeVarInt(p.elapsed);b.writeVarInt(p.duration);}
    };
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
