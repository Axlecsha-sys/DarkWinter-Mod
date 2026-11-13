// Host-independent std140 layout and byte serialization used by WKE effect frontends.
package com.winterkore.engine.graphics.effects;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

public final class WkeSceneUniforms {
    public static final String BLOCK = "DarkWinterScene"; // Stable ABI identifier retained for existing shader packs.
    public static final int BYTES = 416;
    public static final List<String> VECTORS = List.of(
        "Inv0","Inv1","Inv2","Inv3","Light0","Light1","Light2","Light3",
        "Sun","Air","Shadow","Climate","WindTime","Flags","CameraInfo","Options",
        "Look","Analog","FogLayer","FogStyle","FogRange","WeatherShape","WeatherTint","CloudDrift","Film","FilmTiming");
    private WkeSceneUniforms() { }
    public static ByteBuffer writable(ByteBuffer destination) {
        if (!destination.isDirect() || destination.isReadOnly() || destination.limit() < BYTES)
            throw new IllegalArgumentException(BLOCK+" requires a writable direct buffer with limit >= "+BYTES);
        return destination.duplicate().order(ByteOrder.nativeOrder());
    }
    public static void vec4(ByteBuffer destination, int byteOffset, float x, float y, float z, float w) {
        if ((byteOffset & 15) != 0 || byteOffset < 0 || byteOffset > BYTES-16)
            throw new IllegalArgumentException("Invalid WKE scene vec4 offset "+byteOffset);
        destination.putFloat(byteOffset,x); destination.putFloat(byteOffset+4,y);
        destination.putFloat(byteOffset+8,z); destination.putFloat(byteOffset+12,w);
    }
}
