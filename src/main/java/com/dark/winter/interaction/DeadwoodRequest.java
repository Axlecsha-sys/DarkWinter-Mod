package com.dark.winter.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** A request to begin or cancel. Neither progress nor rewards are accepted from the client. */
public record DeadwoodRequest(BlockPos target,Vec3 aim,int serial,boolean start) implements CustomPacketPayload {
    public static final Type<DeadwoodRequest> TYPE=new Type<>(Identifier.fromNamespaceAndPath("darkwinter","deadwood_request"));
    public static final StreamCodec<FriendlyByteBuf,DeadwoodRequest> CODEC=new StreamCodec<>() {
        public DeadwoodRequest decode(FriendlyByteBuf b){return new DeadwoodRequest(b.readBlockPos(),new Vec3(b.readDouble(),b.readDouble(),b.readDouble()),b.readVarInt(),b.readBoolean());}
        public void encode(FriendlyByteBuf b,DeadwoodRequest p){b.writeBlockPos(p.target);b.writeDouble(p.aim.x);b.writeDouble(p.aim.y);b.writeDouble(p.aim.z);b.writeVarInt(p.serial);b.writeBoolean(p.start);}
    };
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
