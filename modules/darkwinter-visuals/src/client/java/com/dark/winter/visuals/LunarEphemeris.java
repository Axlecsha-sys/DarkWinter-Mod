// Visual lunar ephemeris for the fictional North-Canadian DarkWinter setting.
package com.dark.winter.visuals;

/**
 * Visual lunar ephemeris for the fictional North-Canadian DarkWinter setting.
 *
 * Not a NASA / JPL ephemeris: low-order, continuous approximation combining the
 * existing solar calendar with a separate 29.53059-day visual synodic cycle.
 * Local axes: +X east, +Y up, -Z north before northRotation.
 * One Minecraft day is one visual lunar day by design; the seasonal calendar
 * can advance more slowly than that. Nothing mutates server time or moon rules.
 */
public final class LunarEphemeris {
    public static final double SYNODIC_DAYS = 29.530588853;
    public static final double ANGULAR_DIAMETER_DEGREES = 0.52;
    // Start at a full moon; the screenshot's opening winter night remains moonlit.
    private static final double INITIAL_AGE_DAYS = SYNODIC_DAYS * 0.5;
    private static final double OBLIQUITY = Math.toRadians(23.4393);
    private static final double INCLINATION = Math.toRadians(5.145);

    private LunarEphemeris() { }

    public record Position(double x, double y, double z, double elevationDegrees,
                           double azimuthDegrees, double ageDays, int phaseIndex) { }

    public static Position calculate(double clockTicks, double latitudeDegrees,
                                     double longitudeDegrees, double utcOffsetHours,
                                     double northRotationDegrees, int startDayOfYear,
                                     double calendarDaysPerMinecraftDay) {
        if (!Double.isFinite(clockTicks) || !Double.isFinite(latitudeDegrees)
                || !Double.isFinite(longitudeDegrees) || !Double.isFinite(utcOffsetHours)
                || !Double.isFinite(northRotationDegrees) || !Double.isFinite(calendarDaysPerMinecraftDay)
                || Math.abs(latitudeDegrees) >= 90.0 || startDayOfYear < 1 || startDayOfYear > 365
                || calendarDaysPerMinecraftDay < 0.0) {
            throw new IllegalArgumentException("Invalid lunar observer / calendar");
        }

        double gameDay = Math.floor(clockTicks / 24000.0);
        double tickOfDay = clockTicks - gameDay * 24000.0;
        double hour = (tickOfDay / 1000.0 + 6.0) % 24.0;
        double season = startDayOfYear - 1.0
                + (clockTicks / 24000.0) * calendarDaysPerMinecraftDay;
        return calculateSeason(clockTicks,hour,season,latitudeDegrees,longitudeDegrees,utcOffsetHours,northRotationDegrees);
    }

    public static Position calculateCalendar(double clockTicks,double latitudeDegrees,double longitudeDegrees,
                                              double utcOffsetHours,double northRotationDegrees,java.time.LocalDate date) {
        double day=Math.floor(clockTicks/24000.0);
        double hour=((clockTicks-day*24000.0)/1000.0+6.0)%24.0;
        // Same seasonal origin convention as the existing visual ephemeris, not a JPL ephemeris.
        double season=(date.getDayOfYear()-1.0+hour/24.0)*365.0/date.lengthOfYear();
        return calculateSeason(clockTicks,hour,season,latitudeDegrees,longitudeDegrees,utcOffsetHours,northRotationDegrees);
    }

    private static Position calculateSeason(double clockTicks,double hour,double season,double latitudeDegrees,
                                              double longitudeDegrees,double utcOffsetHours,double northRotationDegrees) {
        // Approximate Earth's heliocentric seasonal solar ecliptic longitude.
        double anomaly = Math.toRadians(mod360(357.528 + 0.9856003 * season));
        double solarEcliptic = Math.toRadians(mod360(280.460 + 0.9856474 * season
                + 1.915 * Math.sin(anomaly) + .020 * Math.sin(2.0 * anomaly)));

        // Lunar phase advances across 29.53 Minecraft days, independently of the
        // deliberately slower DarkWinter seasonal progression (default .1 days/day).
        double lunarDays = clockTicks / 24000.0;
        double age = positiveMod(INITIAL_AGE_DAYS + lunarDays, SYNODIC_DAYS);
        double elongation = age * (2.0 * Math.PI / SYNODIC_DAYS);
        double lambda = solarEcliptic + elongation;
        // Mean ascending-node regression (stylized; uses lunar time scale).
        double node = Math.toRadians(mod360(125.08 - 0.052954 * lunarDays));
        double beta = Math.asin(Math.sin(INCLINATION) * Math.sin(lambda - node));

        // Ecliptic -> equatorial Moon right ascension / declination.
        double xE = Math.cos(beta) * Math.cos(lambda);
        double yE = Math.cos(beta) * Math.sin(lambda) * Math.cos(OBLIQUITY)
                - Math.sin(beta) * Math.sin(OBLIQUITY);
        double zE = Math.cos(beta) * Math.sin(lambda) * Math.sin(OBLIQUITY)
                + Math.sin(beta) * Math.cos(OBLIQUITY);
        double alphaMoon = Math.atan2(yE, xE);
        double deltaMoon = Math.asin(clamp(zE, -1.0, 1.0));
        double alphaSun = Math.atan2(Math.cos(OBLIQUITY) * Math.sin(solarEcliptic),
                Math.cos(solarEcliptic));

        // Local sidereal hour angle: same NOAA equation-of-time convention as the
        // geographic Sun, but with a continuous seasonal argument across the
        // boundary between Minecraft days (no artificial daily snap).
        double gamma = (2.0 * Math.PI / 365.0) * season;
        double equationOfTime = 229.18 * (.000075 + .001868*Math.cos(gamma)
                - .032077*Math.sin(gamma) - .014615*Math.cos(2.0*gamma)
                - .040849*Math.sin(2.0*gamma));
        double solarMinutes = positiveMod(hour*60.0 + equationOfTime
                + 4.0*longitudeDegrees - 60.0*utcOffsetHours,1440.0);
        double solarHourAngle = Math.toRadians(solarMinutes/4.0 - 180.0);
        double hourAngleMoon = solarHourAngle + alphaSun - alphaMoon;
        double latitude = Math.toRadians(latitudeDegrees);

        double east = -Math.cos(deltaMoon) * Math.sin(hourAngleMoon);
        double up = Math.sin(latitude) * Math.sin(deltaMoon)
                + Math.cos(latitude) * Math.cos(deltaMoon) * Math.cos(hourAngleMoon);
        double north = Math.cos(latitude) * Math.sin(deltaMoon)
                - Math.sin(latitude) * Math.cos(deltaMoon) * Math.cos(hourAngleMoon);
        double south = -north;
        double spin = Math.toRadians(northRotationDegrees);
        double x = Math.cos(spin) * east + Math.sin(spin) * south;
        double z = -Math.sin(spin) * east + Math.cos(spin) * south;
        double elevation = Math.toDegrees(Math.asin(clamp(up, -1.0, 1.0)));
        double azimuth = mod360(Math.toDegrees(Math.atan2(east, north)));
        // Minecraft phases in source order: full=0, waning=1..3, new=4, waxing=5..7.
        int octant = ((int)Math.floor(age / SYNODIC_DAYS * 8.0 + 0.5)) & 7;
        int index = (4 + octant) & 7;
        return new Position(x, up, z, elevation, azimuth, age, index);
    }

    public static float visibility(double elevationDegrees) {
        // Gentle near-horizon occultation; no moon rendered deep below the horizon.
        if (elevationDegrees <= -0.60) return 0f;
        if (elevationDegrees >= 0.20) return 1f;
        float t = (float)((elevationDegrees + .60) / .80);
        return t * t * (3f - 2f * t);
    }

    public static float moonHalfSizeAtDistance100() {
        return (float)(100.0 * Math.tan(Math.toRadians(ANGULAR_DIAMETER_DEGREES * .5)));
    }

    private static double mod360(double x) { return positiveMod(x, 360.0); }
    private static double positiveMod(double x, double period) {
        return (x % period + period) % period;
    }
    private static double clamp(double v, double a, double b) {return Math.max(a, Math.min(b, v));}
}
