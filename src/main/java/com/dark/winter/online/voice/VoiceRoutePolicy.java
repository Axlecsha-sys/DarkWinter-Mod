// Calculates voice delivery from authoritative membership, position, and acoustic state.
package com.dark.winter.online.voice;

import java.util.*;

/** Pure delivery policy. Every member/position/radio/acoustic field must come from the authoritative host. */
public final class VoiceRoutePolicy {
    public enum Mode { SPATIAL, GLOBAL, RADIO }
    public enum Speech { WHISPER, NORMAL, SHOUT }
    public enum Reason { ALLOWED, SELF, INACTIVE, BLOCKED, MUTED, PERMISSION, DIFFERENT_DIMENSION,
        OUT_OF_RANGE, ACOUSTICS_PENDING, NO_RADIO, RADIO_POWER, RADIO_CHANNEL, ZERO_GAIN }
    public record Position(String dimension, double x, double y, double z) {
        public Position {
            if (dimension == null || dimension.length() > 128 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || Math.abs(y) > 4096)
                throw new IllegalArgumentException("Authoritative voice position");
        }
        double distance(Position other) { return Math.hypot(Math.hypot(x - other.x, z - other.z), y - other.y); }
    }
    public record Radio(boolean present, boolean enabled, int channel, double battery, double volume) {
        public Radio {
            if (channel < 0 || channel > 255 || !Double.isFinite(battery) || battery < 0 || battery > 1
                    || !Double.isFinite(volume) || volume < 0 || volume > 2) throw new IllegalArgumentException("Radio state");
        }
        public static Radio absent() { return new Radio(false, false, 0, 0, 1); }
    }
    /** Listener preferences may deny or scale an otherwise authorized source; they never grant access. */
    public record Preferences(Set<String> muted, Map<String, Double> gains) {
        public Preferences {
            muted = Set.copyOf(muted); gains = Map.copyOf(gains);
            if (muted.size() > 16 || gains.size() > 16) throw new IllegalArgumentException("Voice preferences limit");
            muted.forEach(VoiceRoutePolicy::requireId);
            gains.forEach((id, gain) -> {
                requireId(id); if (!Double.isFinite(gain) || gain < 0 || gain > 2) throw new IllegalArgumentException("Individual voice gain");
            });
        }
        public static Preferences defaults() { return new Preferences(Set.of(), Map.of()); }
    }
    public record Member(String id, Position position, boolean active, boolean canSpeak, boolean canListen,
                         boolean globalVoiceAllowed, Mode mode, Speech speech, Radio radio,
                         Set<String> blockedPeers, Preferences preferences) {
        public Member {
            requireId(id); Objects.requireNonNull(position); Objects.requireNonNull(mode); Objects.requireNonNull(speech);
            Objects.requireNonNull(radio); Objects.requireNonNull(preferences);
            blockedPeers = Set.copyOf(blockedPeers);
            if (blockedPeers.size() > 16) throw new IllegalArgumentException("Voice block list limit");
            blockedPeers.forEach(VoiceRoutePolicy::requireId);
        }
    }
    /** Numeric ranges and every laboratory relaxation are explicit host settings. */
    public record Settings(double whisperRange, double normalRange, double shoutRange, double radioRange,
                           boolean globalEnabled, boolean spatialObstacles, boolean radioObstacles,
                           boolean radioRequiresItem, boolean radioRequiresPower, boolean radioRequiresChannel,
                           boolean radioRangeLimited) {
        public Settings {
            if (!finiteRange(whisperRange, 0.5, 64) || !finiteRange(normalRange, whisperRange, 256)
                    || !finiteRange(shoutRange, normalRange, 1024) || !finiteRange(radioRange, 1, 65536))
                throw new IllegalArgumentException("Voice range bounds");
        }
        public static Settings defaults() { return new Settings(4, 24, 64, 1024, true, true, true, true, true, true, true); }
    }
    public record Pair(String source, String listener) {
        public Pair { requireId(source); requireId(listener); if (source.equals(listener)) throw new IllegalArgumentException("Self acoustic pair"); }
    }
    /** Host samples walls/doors/terrain; a missing required result denies delivery until the sample is available. */
    public record Acoustics(double spatialGain, double radioQuality) {
        public Acoustics {
            if (!finiteRange(spatialGain, 0, 1) || !finiteRange(radioQuality, 0, 1))
                throw new IllegalArgumentException("Acoustic gain");
        }
    }
    public record Decision(boolean allowed, double gain, Reason reason) {}
    private VoiceRoutePolicy() {}
    static void requireId(String id) {
        if (id == null || !id.matches("dw1_[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Verified voice identity");
    }
    private static boolean finiteRange(double value, double min, double max) { return Double.isFinite(value) && value >= min && value <= max; }
    private static Decision denied(Reason reason) { return new Decision(false, 0, reason); }
    public static Decision evaluate(Settings settings, Member source, Member listener, Map<Pair, Acoustics> acoustics) {
        Objects.requireNonNull(settings); Objects.requireNonNull(source); Objects.requireNonNull(listener); Objects.requireNonNull(acoustics);
        if (source.id().equals(listener.id())) return denied(Reason.SELF);
        if (!source.active() || !listener.active()) return denied(Reason.INACTIVE);
        if (!source.canSpeak() || !listener.canListen()) return denied(Reason.PERMISSION);
        if (source.blockedPeers().contains(listener.id()) || listener.blockedPeers().contains(source.id())) return denied(Reason.BLOCKED);
        if (listener.preferences().muted().contains(source.id())) return denied(Reason.MUTED);
        double gain = listener.preferences().gains().getOrDefault(source.id(), 1.0);
        if (gain == 0) return denied(Reason.ZERO_GAIN);
        if (source.mode() == Mode.GLOBAL) {
            if (!settings.globalEnabled() || !source.globalVoiceAllowed() || !listener.globalVoiceAllowed())
                return denied(Reason.PERMISSION);
            return new Decision(true, gain, Reason.ALLOWED);
        }
        if (!source.position().dimension().equals(listener.position().dimension())) return denied(Reason.DIFFERENT_DIMENSION);
        double distance = source.position().distance(listener.position());
        Acoustics sampled = acoustics.get(new Pair(source.id(), listener.id()));
        if (source.mode() == Mode.RADIO) {
            Radio from = source.radio(), to = listener.radio();
            if (settings.radioRequiresItem() && (!from.present() || !to.present())) return denied(Reason.NO_RADIO);
            if (settings.radioRequiresPower() && (!from.enabled() || !to.enabled() || from.battery() <= 0 || to.battery() <= 0))
                return denied(Reason.RADIO_POWER);
            if (settings.radioRequiresChannel() && from.channel() != to.channel()) return denied(Reason.RADIO_CHANNEL);
            if (settings.radioRangeLimited() && distance >= settings.radioRange()) return denied(Reason.OUT_OF_RANGE);
            if (settings.radioObstacles()) {
                if (sampled == null) return denied(Reason.ACOUSTICS_PENDING);
                gain *= sampled.radioQuality();
            }
            if (settings.radioRangeLimited()) gain *= Math.sqrt(Math.max(0, 1 - distance / settings.radioRange()));
            gain *= to.volume();
        } else {
            double range = switch (source.speech()) {
                case WHISPER -> settings.whisperRange();
                case NORMAL -> settings.normalRange();
                case SHOUT -> settings.shoutRange();
            };
            if (distance >= range) return denied(Reason.OUT_OF_RANGE);
            if (settings.spatialObstacles()) {
                if (sampled == null) return denied(Reason.ACOUSTICS_PENDING);
                gain *= sampled.spatialGain();
            }
            gain *= Math.pow(1 - distance / range, 2);
        }
        gain = Math.min(2, gain);
        return gain > 0 ? new Decision(true, gain, Reason.ALLOWED) : denied(Reason.ZERO_GAIN);
    }
}
