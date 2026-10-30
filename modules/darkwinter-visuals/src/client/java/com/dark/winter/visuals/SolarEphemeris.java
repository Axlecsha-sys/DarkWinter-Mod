// NOAA's approximate equation-of-time/declination solar model.
package com.dark.winter.visuals;

/**
 * NOAA's approximate equation-of-time/declination solar model.
 * East-positive longitude, east=+X, up=+Y, south=+Z before northRotation.
 * No system clock, no world mutation, no dependency on a rendered camera.
 */
public final class SolarEphemeris {
    private SolarEphemeris() { }
    public record Position(double x, double y, double z,
                           double azimuthDegrees, double elevationDegrees,
                           double declinationDegrees, double apparentSolarHour) { }

    public static Position calculate(double clockTicks, double latitudeDegrees,
                                     double longitudeDegrees, double utcOffsetHours,
                                     double northRotationDegrees, int startDayOfYear,
                                     double calendarDaysPerMinecraftDay) {
        if (!Double.isFinite(clockTicks) || !Double.isFinite(latitudeDegrees)
                || !Double.isFinite(longitudeDegrees) || !Double.isFinite(utcOffsetHours)
                || !Double.isFinite(northRotationDegrees) || !Double.isFinite(calendarDaysPerMinecraftDay)
                || Math.abs(latitudeDegrees) >= 90 || startDayOfYear < 1 || startDayOfYear > 365
                || calendarDaysPerMinecraftDay < 0) throw new IllegalArgumentException("Invalid solar coordinates/calendar");

        // Minecraft day: tick 0 -> 06:00, tick 6000 -> 12:00, tick 18000 -> 00:00.
        double gameDay = Math.floor(clockTicks / 24000.0);
        double tickOfDay = clockTicks - gameDay * 24000.0;
        double localHour = ((tickOfDay / 1000.0) + 6.0) % 24.0;
        double calendarDay = ((startDayOfYear - 1.0 + gameDay * calendarDaysPerMinecraftDay) % 365.0 + 365.0) % 365.0;
        double gamma = 2 * Math.PI / 365.0 * (calendarDay + (localHour - 12.0) / 24.0);
        return fromGamma(localHour,gamma,latitudeDegrees,longitudeDegrees,utcOffsetHours,northRotationDegrees);
    }

    public static Position calculateCalendar(double clockTicks,double latitudeDegrees,double longitudeDegrees,
                                              double utcOffsetHours,double northRotationDegrees,java.time.LocalDate date) {
        double day=Math.floor(clockTicks/24000.0);
        double localHour=((clockTicks-day*24000.0)/1000.0+6.0)%24.0;
        double gamma=2*Math.PI/date.lengthOfYear()*(date.getDayOfYear()-1.0+(localHour-12.0)/24.0);
        return fromGamma(localHour,gamma,latitudeDegrees,longitudeDegrees,utcOffsetHours,northRotationDegrees);
    }

    private static Position fromGamma(double localHour,double gamma,double latitudeDegrees,double longitudeDegrees,
                                      double utcOffsetHours,double northRotationDegrees) {
        double eqTimeMinutes = 229.18 * (0.000075 + 0.001868*Math.cos(gamma)
                - 0.032077*Math.sin(gamma) - 0.014615*Math.cos(2*gamma)
                - 0.040849*Math.sin(2*gamma));
        double decl = 0.006918 - 0.399912*Math.cos(gamma) + 0.070257*Math.sin(gamma)
                - 0.006758*Math.cos(2*gamma) + 0.000907*Math.sin(2*gamma)
                - 0.002697*Math.cos(3*gamma) + 0.00148*Math.sin(3*gamma);
        double solarMinutes = ((localHour*60.0 + eqTimeMinutes
                + 4.0*longitudeDegrees - 60.0*utcOffsetHours) % 1440.0 + 1440.0) % 1440.0;
        double hourAngle = Math.toRadians(solarMinutes/4.0 - 180.0);
        double phi = Math.toRadians(latitudeDegrees);

        // Local tangent-frame: East, Up, South; stable even at polar sunrise.
        double east = -Math.cos(decl)*Math.sin(hourAngle);
        double up = Math.sin(phi)*Math.sin(decl) + Math.cos(phi)*Math.cos(decl)*Math.cos(hourAngle);
        double north = Math.cos(phi)*Math.sin(decl) - Math.sin(phi)*Math.cos(decl)*Math.cos(hourAngle);
        double south = -north;
        double azimuth = (Math.toDegrees(Math.atan2(east,north))+360.0)%360.0;
        double elevation = Math.toDegrees(Math.asin(Math.max(-1,Math.min(1,up))));
        double spin=Math.toRadians(northRotationDegrees);
        // Rotate local geographic axes around world Y. Default +X east, -Z north.
        double x = Math.cos(spin)*east + Math.sin(spin)*south;
        double z = -Math.sin(spin)*east + Math.cos(spin)*south;
        return new Position(x,up,z,azimuth,elevation,Math.toDegrees(decl),solarMinutes/60.0);
    }
}
