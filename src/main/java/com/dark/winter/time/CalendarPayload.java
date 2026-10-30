// Small server-to-client epoch packet.
package com.dark.winter.time;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Small server-to-client epoch packet. It never accepts a client's time setting. */
public record CalendarPayload(CalendarEpoch epoch) implements CustomPacketPayload {
    public static final Type<CalendarPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("dark-winter","calendar_epoch"));
    public static final StreamCodec<FriendlyByteBuf,CalendarPayload> CODEC=new StreamCodec<>() {
        @Override public CalendarPayload decode(FriendlyByteBuf b) {
            return new CalendarPayload(new CalendarEpoch(b.readBoolean(),b.readLong(),b.readLong(),b.readDouble()));
        }
        @Override public void encode(FriendlyByteBuf b,CalendarPayload payload) {
            CalendarEpoch e=payload.epoch();
            b.writeBoolean(e.modern());b.writeLong(e.startEpochDay());b.writeLong(e.originTicks());b.writeDouble(e.dayMultiplier());
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
