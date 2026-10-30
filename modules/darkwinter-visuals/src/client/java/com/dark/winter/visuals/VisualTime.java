// Keeps visible calendar values aligned with the visual Sun and Moon.
package com.dark.winter.visuals;

import net.minecraft.client.multiplayer.ClientLevel;
import com.dark.winter.client.CalendarClient;
import com.dark.winter.time.CalendarEpoch;
import java.util.Locale;

/** Utility shared by sky logic and the HUD so visible time/date match the visual Sun and Moon. */
public final class VisualTime {
    private static final int[] MONTH_LENGTHS = {31,28,31,30,31,30,31,31,30,31,30,31};
    private static final String[] MONTHS_RU = {
            "января","февраля","марта","апреля","мая","июня",
            "июля","августа","сентября","октября","ноября","декабря"
    };

    private VisualTime() { }

    public record Snapshot(int hour, int minute, int dayOfYear, int dayOfMonth, int monthIndex, int yearIndex, boolean historical) {
        public String timeText() {
            return String.format(Locale.ROOT,"%02d:%02d", hour, minute);
        }
        public String dateTextRu() {
            if (historical) return String.format(Locale.ROOT,"%02d.%02d.%04d",dayOfMonth,monthIndex+1,yearIndex);
            return dayOfMonth + " " + MONTHS_RU[monthIndex] + ", год " + yearIndex;
        }
    }

    public static double scaledClockTicks(double clockTicks, float multiplier) {
        return clockTicks / Math.max(1.0f, multiplier);
    }

    public static double clockTicks(ClientLevel level,float partialTicks) {
        CalendarEpoch epoch=CalendarClient.modern();
        double raw=level.getDefaultClockTime()+(double)partialTicks;
        return epoch!=null?epoch.clockTicks(raw):scaledClockTicks(raw,SolarRuntime.geography().visualDayLengthMultiplier());
    }

    public static Snapshot snapshot(ClientLevel level, float partialTicks) {
        VisualConfig config = VisualConfig.shared();
        config.poll();
        double rawTicks = level.getDefaultClockTime() + (double)partialTicks;
        CalendarEpoch epoch=CalendarClient.modern();
        if (epoch!=null) {
            CalendarEpoch.Stamp stamp=epoch.at(rawTicks);
            var d=stamp.date();
            return new Snapshot(stamp.hour(),stamp.minute(),d.getDayOfYear(),d.getDayOfMonth(),d.getMonthValue()-1,d.getYear(),true);
        }
        var geography=SolarRuntime.geography();
        double scaled = scaledClockTicks(rawTicks, geography.visualDayLengthMultiplier());
        double gameDay = Math.floor(scaled / 24000.0);
        double tickOfDay = scaled - gameDay * 24000.0;
        double localHour = ((tickOfDay / 1000.0) + 6.0) % 24.0;
        int hour = (int)Math.floor(localHour);
        int minute = Math.max(0, Math.min(59, (int)Math.floor((localHour - hour) * 60.0 + 0.5)));
        if (minute == 60) { minute = 0; hour = (hour + 1) % 24; }

        double calendarDayZeroBased = ((geography.calendarStartDay() - 1.0 + gameDay * geography.calendarDaysPerMinecraftDay()) % 365.0 + 365.0) % 365.0;
        int dayOfYear = (int)Math.floor(calendarDayZeroBased) + 1;
        int remaining = dayOfYear;
        int month = 0;
        while (month < MONTH_LENGTHS.length && remaining > MONTH_LENGTHS[month]) {
            remaining -= MONTH_LENGTHS[month];
            month++;
        }
        if (month >= MONTH_LENGTHS.length) { month = MONTH_LENGTHS.length - 1; remaining = MONTH_LENGTHS[month]; }
        int year = (int)Math.floor((geography.calendarStartDay() - 1.0 + gameDay * geography.calendarDaysPerMinecraftDay()) / 365.0) + 1;
        return new Snapshot(hour, minute, dayOfYear, remaining, month, Math.max(1, year),false);
    }
}
