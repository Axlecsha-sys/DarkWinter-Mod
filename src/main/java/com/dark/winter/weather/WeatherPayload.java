// Server-authoritative transition: no client weather settings are accepted.
package com.dark.winter.weather;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-authoritative transition: no client weather settings are accepted. */
public record WeatherPayload(WeatherTimeline timeline, ClimateTimeline climate, ClimateGeography geography) implements CustomPacketPayload {
    public static final Type<WeatherPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("darkwinter", "weather"));
    public static final StreamCodec<FriendlyByteBuf, WeatherPayload> CODEC = new StreamCodec<>() {
        @Override public WeatherPayload decode(FriendlyByteBuf b) {
            WeatherValues from = new WeatherValues(b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(),
                    b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat());
            WeatherTimeline weather = new WeatherTimeline(from, WeatherPreset.byId(b.readUtf(32)),
                    b.readLong(), b.readVarInt(), b.readBoolean(), b.readLong(), b.readFloat());
            ClimateTimeline climate = new ClimateTimeline(readClimate(b), readClimate(b), b.readLong(),
                    b.readVarInt(), b.readBoolean(), b.readLong(), b.readFloat());
            ClimateGeography geography = new ClimateGeography(b.readFloat(), b.readFloat(), b.readFloat(),
                    b.readFloat(), b.readFloat(), b.readVarInt(), b.readFloat());
            return new WeatherPayload(weather, climate, geography);
        }
        @Override public void encode(FriendlyByteBuf b, WeatherPayload payload) {
            WeatherTimeline s = payload.timeline();
            WeatherValues v = s.from();
            b.writeFloat(v.cloudCover()); b.writeFloat(v.snowfall()); b.writeFloat(v.windSpeed());
            b.writeFloat(v.windX()); b.writeFloat(v.windZ()); b.writeFloat(v.fogMultiplier());
            b.writeFloat(v.sunVisibility()); b.writeFloat(v.cloudRoundness()); b.writeFloat(v.cloudScale());
            b.writeFloat(v.cloudStretch()); b.writeFloat(v.fogBlue()); b.writeFloat(v.skyBlue());
            b.writeUtf(s.target().id(), 32); b.writeLong(s.startedAt()); b.writeVarInt(s.durationTicks());
            b.writeBoolean(s.automatic()); b.writeLong(s.nextChangeAt());
            b.writeFloat(s.groundSnowFrom());
            ClimateTimeline c = payload.climate();
            writeClimate(b, c.from()); writeClimate(b, c.target()); b.writeLong(c.startedAt());
            b.writeVarInt(c.durationTicks()); b.writeBoolean(c.automatic()); b.writeLong(c.nextChangeAt()); b.writeFloat(c.northRotation());
            ClimateGeography g = payload.geography();
            b.writeFloat(g.latitude()); b.writeFloat(g.longitude()); b.writeFloat(g.utcOffsetHours()); b.writeFloat(g.northRotation());
            b.writeFloat(g.visualDayLengthMultiplier()); b.writeVarInt(g.calendarStartDay()); b.writeFloat(g.calendarDaysPerMinecraftDay());
        }
    };
    private static ClimateValues readClimate(FriendlyByteBuf b) {
        return new ClimateValues(b.readFloat(), b.readFloat(), b.readFloat());
    }
    private static void writeClimate(FriendlyByteBuf b, ClimateValues v) {
        b.writeFloat(v.windMetersPerSecond()); b.writeFloat(v.sourceBearingDegrees()); b.writeFloat(v.airTemperatureC());
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
