// Save and network representation; gusts depend on world ticks and therefore agree on client/server.
package com.dark.winter.weather;

/** Save and network representation; gusts depend on world ticks and therefore agree on client/server. */
public record ClimateTimeline(ClimateValues from, ClimateValues target, long startedAt, int durationTicks,
                              boolean automatic, long nextChangeAt, float northRotation) {
    public ClimateTimeline {
        if (from == null || target == null || startedAt < 0 || durationTicks < 0 || durationTicks > 6000
                || nextChangeAt < 0 || !Float.isFinite(northRotation) || Math.abs(northRotation) > 360)
            throw new IllegalArgumentException("Invalid climate timeline");
    }
    public ClimateValues values(double ticks) {
        if (!Double.isFinite(ticks)) throw new IllegalArgumentException("Nonfinite climate tick");
        if (durationTicks == 0 || ticks >= startedAt + durationTicks) return target;
        double t = Math.clamp((ticks - startedAt) / durationTicks, 0, 1);
        return from.blend(target, t * t * (3 - 2 * t));
    }
    public ClimateSample sample(double ticks) {
        ClimateValues v = values(ticks);
        // Slow, coherent gusts. Calm is exactly zero; there is no random per-frame HUD flicker.
        double seconds = ticks / 20;
        float gust = (float)(.94 + .04 * Math.sin(seconds * .28) + .02 * Math.sin(seconds * .73 + 1.4));
        return new ClimateSample(v.windMetersPerSecond() * gust,
                WindDirection.flowX(v.sourceBearingDegrees(), northRotation),
                WindDirection.flowZ(v.sourceBearingDegrees(), northRotation),
                v.sourceBearingDegrees(), v.airTemperatureC());
    }
    public ClimateTimeline retarget(ClimateValues next, long ticks, int duration, boolean auto, long nextChange) {
        return new ClimateTimeline(values(ticks), next, ticks, duration, auto, nextChange, northRotation);
    }
    public ClimateTimeline automatic(boolean enabled, long next) {
        return new ClimateTimeline(from, target, startedAt, durationTicks, enabled, next, northRotation);
    }
    public boolean transitioning(long ticks) { return durationTicks > 0 && ticks < startedAt + durationTicks; }

    public ClimateTimeline restoreClock(long gameTime, long savedAt) {
        if (gameTime < 0 || savedAt < 0) throw new IllegalArgumentException("Invalid climate save tick");
        if (gameTime >= savedAt) return this;
        long offset = savedAt - gameTime;
        long next = gameTime + Math.max(0, nextChangeAt - savedAt);
        if (startedAt >= offset)
            return new ClimateTimeline(from, target, startedAt - offset, durationTicks, automatic, next, northRotation);
        int remaining = (int)Math.max(0, durationTicks - Math.max(0, savedAt - startedAt));
        return new ClimateTimeline(values(savedAt), target, gameTime, remaining, automatic, next, northRotation);
    }
}
