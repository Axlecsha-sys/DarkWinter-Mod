// Immutable absolute game-tick timeline; saving or joining never restarts a transition.
package com.dark.winter.weather;

/** Immutable absolute game-tick timeline; saving or joining never restarts a transition. */
public record WeatherTimeline(WeatherValues from, WeatherPreset target, long startedAt, int durationTicks,
                              boolean automatic, long nextChangeAt, float groundSnowFrom) {
    public WeatherTimeline {
        if (from == null || target == null || startedAt < 0 || durationTicks < 0 || durationTicks > 6000 || nextChangeAt < 0
                || !Float.isFinite(groundSnowFrom) || groundSnowFrom < 0 || groundSnowFrom > 1)
            throw new IllegalArgumentException("Invalid weather timeline");
    }

    /** Schema 1 and existing callers contain no ground-snow state. */
    public WeatherTimeline(WeatherValues from, WeatherPreset target, long startedAt, int durationTicks,
                           boolean automatic, long nextChangeAt) {
        this(from, target, startedAt, durationTicks, automatic, nextChangeAt, 0);
    }

    public static WeatherTimeline initial(long gameTime, long nextChangeAt) {
        return new WeatherTimeline(WeatherPreset.OVERCAST.profile(), WeatherPreset.OVERCAST,
                gameTime, 0, true, nextChangeAt);
    }

    public WeatherValues sample(double gameTime) {
        if (durationTicks == 0 || gameTime >= startedAt + durationTicks) return target.profile();
        double t = Math.clamp((gameTime - startedAt) / durationTicks, 0, 1);
        // Zero slope at both ends keeps short and long transitions equally gentle.
        return from.blend(target.profile(), t * t * (3 - 2 * t));
    }

    public WeatherTimeline retarget(WeatherPreset preset, long gameTime, int duration, boolean auto, long next) {
        return new WeatherTimeline(sample(gameTime), preset, gameTime, duration, auto, next, groundSnow(gameTime));
    }

    public WeatherTimeline automatic(boolean enabled, long next) {
        return new WeatherTimeline(from, target, startedAt, durationTicks, enabled, next, groundSnowFrom);
    }

    public float groundSnow(double gameTime) {
        float to = target == WeatherPreset.GROUND_SNOW ? 1 : 0;
        if (durationTicks == 0 || gameTime >= startedAt + durationTicks) return to;
        double t = Math.clamp((gameTime - startedAt) / durationTicks, 0, 1);
        return (float)(groundSnowFrom + (to - groundSnowFrom) * t * t * (3 - 2 * t));
    }

    public boolean transitioning(double gameTime) { return durationTicks > 0 && gameTime < startedAt + durationTicks; }

    /** A restored level.dat may precede the most recent metadata save after a crash or backup restore. */
    public WeatherTimeline restoreClock(long gameTime, long savedAt) {
        if (gameTime < 0 || savedAt < 0) throw new IllegalArgumentException("Invalid weather save tick");
        if (gameTime >= savedAt) return this;
        long offset = savedAt - gameTime;
        long next = gameTime + Math.max(0, nextChangeAt - savedAt);
        if (startedAt >= offset)
            return new WeatherTimeline(from, target, startedAt - offset, durationTicks, automatic, next, groundSnowFrom);
        int remaining = (int)Math.max(0, durationTicks - Math.max(0, savedAt - startedAt));
        return new WeatherTimeline(sample(savedAt), target, gameTime, remaining, automatic, next, groundSnow(savedAt));
    }
}
