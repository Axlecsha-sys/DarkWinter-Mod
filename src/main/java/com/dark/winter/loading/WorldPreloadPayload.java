// Carries measured completed-chunk progress for native world loading.
package com.dark.winter.loading;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Actual completed FULL chunks, rather than a timer-based loading percentage. */
public record WorldPreloadPayload(int completed, int total, int inFlight, int centerX, int centerZ,
                                  String dimension, Stage stage, long elapsedMillis) implements CustomPacketPayload {
    public enum Stage { LOADING, SAVING, READY, FAILED, CANCELLED }

    public static final Type<WorldPreloadPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("darkwinter", "world_preload"));
    public static final StreamCodec<FriendlyByteBuf, WorldPreloadPayload> CODEC = new StreamCodec<>() {
        @Override public WorldPreloadPayload decode(FriendlyByteBuf b) {
            return new WorldPreloadPayload(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readInt(), b.readInt(),
                    b.readUtf(256), b.readEnum(Stage.class), b.readVarLong());
        }
        @Override public void encode(FriendlyByteBuf b, WorldPreloadPayload p) {
            b.writeVarInt(p.completed()); b.writeVarInt(p.total()); b.writeVarInt(p.inFlight());
            b.writeInt(p.centerX()); b.writeInt(p.centerZ()); b.writeUtf(p.dimension(), 256);
            b.writeEnum(p.stage()); b.writeVarLong(p.elapsedMillis());
        }
    };

    public boolean active() { return stage == Stage.LOADING || stage == Stage.SAVING; }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
