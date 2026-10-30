// Separate metadata permits old weather saves to gain climate without resetting their weather.
package com.dark.winter.weather;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Separate metadata permits old weather saves to gain climate without resetting their weather. */
public final class ClimateStore {
    private ClimateStore() { }
    public static ClimateTimeline load(Path file) throws IOException {
        return load(file, -1);
    }
    public static ClimateTimeline load(Path file, long currentGameTime) throws IOException {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
        try {
            if (!"1".equals(p.getProperty("schema"))) throw new IllegalArgumentException("Unknown climate schema");
            String auto = p.getProperty("automatic");
            if (!"true".equals(auto) && !"false".equals(auto)) throw new IllegalArgumentException("Invalid automatic flag");
            ClimateTimeline timeline = new ClimateTimeline(parse(p.getProperty("from")), parse(p.getProperty("target")),
                    Long.parseLong(p.getProperty("startedAt")), Integer.parseInt(p.getProperty("durationTicks")),
                    Boolean.parseBoolean(auto), Long.parseLong(p.getProperty("nextChangeAt")),
                    Float.parseFloat(p.getProperty("northRotation")));
            long savedAt = Long.parseLong(p.getProperty("savedAt", Long.toString(timeline.startedAt())));
            if (savedAt < 0) throw new IllegalArgumentException("Invalid save tick");
            return currentGameTime < 0 ? timeline : timeline.restoreClock(currentGameTime, savedAt);
        } catch (RuntimeException ex) { throw new IOException("Invalid DarkWinter climate metadata; file was not reset: " + file, ex); }
    }
    public static void save(Path file, ClimateTimeline state) throws IOException {
        save(file, state, state.startedAt());
    }
    public static void save(Path file, ClimateTimeline state, long savedAt) throws IOException {
        if (savedAt < 0) throw new IllegalArgumentException("Invalid save tick");
        Properties p = new Properties();
        p.setProperty("schema", "1"); p.setProperty("from", format(state.from())); p.setProperty("target", format(state.target()));
        p.setProperty("savedAt", Long.toString(savedAt));
        p.setProperty("startedAt", Long.toString(state.startedAt())); p.setProperty("durationTicks", Integer.toString(state.durationTicks()));
        p.setProperty("automatic", Boolean.toString(state.automatic())); p.setProperty("nextChangeAt", Long.toString(state.nextChangeAt()));
        p.setProperty("northRotation", Float.toString(state.northRotation()));
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "darkwinter-climate-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { p.store(writer, "DarkWinter per-world wind and temperature; speed m/s, bearing FROM geographic north."); }
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private static ClimateValues parse(String text) {
        String[] v = text.split(",", -1);
        if (v.length != 3) throw new IllegalArgumentException("Invalid climate profile");
        return new ClimateValues(Float.parseFloat(v[0]), Float.parseFloat(v[1]), Float.parseFloat(v[2]));
    }
    private static String format(ClimateValues value) {
        return value.windMetersPerSecond() + "," + value.sourceBearingDegrees() + "," + value.airTemperatureC();
    }
}
