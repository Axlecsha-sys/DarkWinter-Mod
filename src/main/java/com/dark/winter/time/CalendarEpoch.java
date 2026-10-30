// Defines the immutable per-world origin used to derive calendar time.
package com.dark.winter.time;

import java.time.LocalDate;

/** Per-world immutable origin. Live time is derived from the world's saved clock, never wall time. */
public record CalendarEpoch(boolean modern,long startEpochDay,long originTicks,double dayMultiplier) {
    public static final LocalDate START_DATE=LocalDate.of(1997,10,8);
    public CalendarEpoch {
        LocalDate.ofEpochDay(startEpochDay); // validate file/packet values
        if (!Double.isFinite(dayMultiplier) || dayMultiplier<1 || dayMultiplier>32)
            throw new IllegalArgumentException("Invalid calendar day multiplier");
    }
    public static CalendarEpoch newWorld(long rawTicks,double multiplier) {
        return new CalendarEpoch(true,START_DATE.toEpochDay(),rawTicks,multiplier);
    }
    public static CalendarEpoch legacy() { return new CalendarEpoch(false,START_DATE.toEpochDay(),0,4); }
    public double clockTicks(double rawTicks) {
        if (!Double.isFinite(rawTicks)) throw new IllegalArgumentException("Invalid world clock");
        return (rawTicks-originTicks)/dayMultiplier;
    }
    public record Stamp(LocalDate date,int hour,int minute,double clockTicks) { }
    public Stamp at(double rawTicks) {
        double clock=clockTicks(rawTicks);
        // tick zero is 06:00; calendar rolls at local midnight, not at 06:00.
        double sinceMidnight=clock+6000.0;
        long days=(long)Math.floor(sinceMidnight/24000.0);
        double today=sinceMidnight-days*24000.0;
        int minuteOfDay=Math.min(1439,Math.max(0,(int)Math.floor(today*1440.0/24000.0)));
        LocalDate date=LocalDate.ofEpochDay(startEpochDay).plusDays(days);
        return new Stamp(date,minuteOfDay/60,minuteOfDay%60,clock);
    }
}
