// Host-independent std140 controls for native-resolution edge reconstruction.
package com.winterkore.engine.graphics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/** Hosts own persistence and graph execution. This class never stores image history. */
public final class WkeAntialiasControls {
    public static final String BLOCK = "WkeAntialias";
    public static final int BYTES = 16;
    public static final boolean DEFAULT_ENABLED = true;
    public static final float SEARCH_PIXELS = 8f;
    public static final float RELATIVE_THRESHOLD = .10f;
    private static volatile long uniformUploads, enabledUploads;
    private static volatile boolean lastEnabled;

    private WkeAntialiasControls() { }

    public static void write(ByteBuffer target, boolean enabled, boolean flipDepthY) {
        if (!target.isDirect() || target.isReadOnly() || target.limit() < BYTES)
            throw new IllegalArgumentException("WkeAntialias requires a writable direct 16-byte buffer");
        ByteBuffer bytes=target.duplicate().order(ByteOrder.nativeOrder());
        bytes.putFloat(0,enabled?1f:0f);
        bytes.putFloat(4,SEARCH_PIXELS);
        bytes.putFloat(8,RELATIVE_THRESHOLD);
        bytes.putFloat(12,flipDepthY?1f:0f);
        lastEnabled=enabled;
        uniformUploads++;
        if(enabled) enabledUploads++;
    }

    /** Upload evidence only: rendered output and GPU cost require a frame capture. */
    public static Map<String,Object> diagnostics() {
        return Map.of("method","directional spatial edge reconstruction",
                "enabled",lastEnabled,"uniformUploads",uniformUploads,
                "enabledUploads",enabledUploads,"historyFrames",0,
                "nativeResolution",true,"searchPixels",SEARCH_PIXELS,
                "maximumDisplacementPixels",.5f);
    }
}
