// Small bounded PCM utilities shared by local playout and the host mixer.
package com.dark.winter.online.voice;

import java.nio.*;
import java.util.*;

/** Small bounded PCM utilities shared by local playout and the host mixer. */
public final class VoicePcm {
    private VoicePcm() {}
    /** Converts signed PCM16/native-order mono/stereo to mono48k; at most 200ms per call. */
    public static float[] mono48k(VoicePeer.DecodedAudio audio) {
        Objects.requireNonNull(audio);
        int count = (int) Math.round((double) audio.frames() * VoicePeer.SAMPLE_RATE / audio.sampleRate());
        if (count < 1 || count > VoicePeer.FRAME_SAMPLES * 20) throw new IllegalArgumentException("Audio conversion exceeds 200ms");
        ByteBuffer bytes = ByteBuffer.wrap(audio.pcm()).order(ByteOrder.nativeOrder());
        float[] mono = new float[audio.frames()];
        for (int i = 0; i < mono.length; i++) {
            float value = 0;
            for (int channel = 0; channel < audio.channels(); channel++) value += bytes.getShort();
            mono[i] = value / audio.channels();
        }
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            double source = (double) i * audio.sampleRate() / VoicePeer.SAMPLE_RATE;
            int left = Math.min(mono.length - 1, (int) source), right = Math.min(mono.length - 1, left + 1);
            out[i] = (float) (mono[left] + (mono[right] - mono[left]) * (source - left));
        }
        return out;
    }
    public static float[] mono10ms(byte[] pcm) {
        Objects.requireNonNull(pcm);
        if (pcm.length != VoicePeer.FRAME_BYTES) throw new IllegalArgumentException("One audio frame is 960 bytes");
        ByteBuffer bytes = ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder());
        float[] out = new float[VoicePeer.FRAME_SAMPLES];
        for (int i = 0; i < out.length; i++) out[i] = bytes.getShort();
        return out;
    }
    /** Saturates after mixing; gain must be explicit and bounded. */
    public static byte[] pcm16(float[] samples, double gain) {
        Objects.requireNonNull(samples);
        if (samples.length < 1 || samples.length > VoicePeer.FRAME_SAMPLES * 20 || !Double.isFinite(gain) || gain < 0 || gain > 2)
            throw new IllegalArgumentException("PCM output bounds");
        ByteBuffer output = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.nativeOrder());
        for (float sample : samples) {
            if (!Float.isFinite(sample)) throw new IllegalArgumentException("Non-finite PCM");
            output.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * gain))));
        }
        return output.array();
    }
    public static double level(byte[] pcm) {
        float[] values = mono10ms(pcm); double energy = 0;
        for (float value : values) energy += (double) value * value;
        return Math.sqrt(energy / values.length) / 32768.0;
    }
}
