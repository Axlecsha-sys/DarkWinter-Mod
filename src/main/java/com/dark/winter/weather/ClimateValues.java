// Smoothly changing ambient conditions; source bearing is geographic, independent of camera yaw.
package com.dark.winter.weather;

/** Smoothly changing ambient conditions; source bearing is geographic, independent of camera yaw. */
public record ClimateValues(float windMetersPerSecond, float sourceBearingDegrees, float airTemperatureC) {
    public ClimateValues {
        if (!Float.isFinite(windMetersPerSecond) || windMetersPerSecond < 0 || windMetersPerSecond > 30
                || !Float.isFinite(sourceBearingDegrees) || sourceBearingDegrees < 0 || sourceBearingDegrees >= 360
                || !Float.isFinite(airTemperatureC) || airTemperatureC < -90 || airTemperatureC > 50)
            throw new IllegalArgumentException("Invalid climate values");
    }
    public ClimateValues blend(ClimateValues target, double amount) {
        double t = Math.clamp(amount, 0, 1);
        // Interpolating vector components would cancel opposite winds halfway through a turn.
        double delta = ((target.sourceBearingDegrees - sourceBearingDegrees + 540) % 360) - 180;
        if (delta == -180) delta = 180;
        float bearing = (float)WindDirection.normalize(sourceBearingDegrees + delta * t);
        if (bearing >= 360) bearing = 0; // Float rounding immediately before north must remain in [0,360).
        return new ClimateValues((float)(windMetersPerSecond + (target.windMetersPerSecond - windMetersPerSecond) * t),
                bearing,
                (float)(airTemperatureC + (target.airTemperatureC - airTemperatureC) * t));
    }
}
