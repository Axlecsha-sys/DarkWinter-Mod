// Persists per-save calendar metadata while preserving legacy world timing.
package com.dark.winter.time;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.Properties;

/** One tiny metadata file in each save. Missing old-world data is marked legacy, not reset. */
public final class CalendarStore {
    private CalendarStore() { }
    public static CalendarEpoch loadOrCreate(Path file,boolean freshWorld,long rawTicks,double multiplier) throws IOException {
        if (Files.exists(file)) return load(file);
        CalendarEpoch value=freshWorld?CalendarEpoch.newWorld(rawTicks,multiplier):CalendarEpoch.legacy();
        Files.createDirectories(file.getParent());
        Path temp=Files.createTempFile(file.getParent(),"darkwinter-calendar-",".tmp");
        try {
            Properties p=new Properties();
            p.setProperty("schema","1");
            p.setProperty("modern",Boolean.toString(value.modern()));
            p.setProperty("startDate",java.time.LocalDate.ofEpochDay(value.startEpochDay()).toString());
            p.setProperty("originTicks",Long.toString(value.originTicks()));
            p.setProperty("dayMultiplier",Double.toString(value.dayMultiplier()));
            try(Writer w=Files.newBufferedWriter(temp,StandardCharsets.UTF_8)) {
                p.store(w,"DarkWinter per-world calendar. Do not delete to reset gameplay.");
            }
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException ex) { Files.move(temp,file); }
        } finally { Files.deleteIfExists(temp); }
        return value;
    }
    public static CalendarEpoch load(Path file) throws IOException {
        Properties p=new Properties();
        try(Reader r=Files.newBufferedReader(file,StandardCharsets.UTF_8)) { p.load(r); }
        try {
            if (!"1".equals(p.getProperty("schema"))) throw new IllegalArgumentException("Unknown schema");
            String mode=p.getProperty("modern");
            if (!"true".equals(mode) && !"false".equals(mode)) throw new IllegalArgumentException("Invalid mode");
            return new CalendarEpoch(Boolean.parseBoolean(mode),
                    java.time.LocalDate.parse(p.getProperty("startDate")).toEpochDay(),
                    Long.parseLong(p.getProperty("originTicks")),Double.parseDouble(p.getProperty("dayMultiplier")));
        } catch(RuntimeException ex) { throw new IOException("Invalid calendar metadata: "+file+"; file was NOT reset",ex); }
    }
}
