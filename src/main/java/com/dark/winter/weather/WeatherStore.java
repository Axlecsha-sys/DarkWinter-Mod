// Per-save state, including the exact starting values of an interrupted transition.
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

/** Per-save state, including the exact starting values of an interrupted transition. */
public final class WeatherStore {
    private WeatherStore() { }

    public static WeatherTimeline load(Path file) throws IOException {
        return load(file, -1);
    }

    public static WeatherTimeline load(Path file, long currentGameTime) throws IOException {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
        try {
            if (!"1".equals(p.getProperty("schema"))) throw new IllegalArgumentException("Unknown schema");
            String[] fields = p.getProperty("from").split(",", -1);
            if (fields.length != 12) throw new IllegalArgumentException("Invalid profile length");
            float[] v = new float[12];
            for (int i = 0; i < v.length; i++) v[i] = Float.parseFloat(fields[i]);
            String mode = p.getProperty("automatic");
            if (!"true".equals(mode) && !"false".equals(mode)) throw new IllegalArgumentException("Invalid automatic flag");
            WeatherTimeline timeline = new WeatherTimeline(new WeatherValues(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11]),
                    WeatherPreset.byId(p.getProperty("target")), Long.parseLong(p.getProperty("startedAt")),
                    Integer.parseInt(p.getProperty("durationTicks")), Boolean.parseBoolean(mode), Long.parseLong(p.getProperty("nextChangeAt")),
                    Float.parseFloat(p.getProperty("groundSnowFrom", "0")));
            long savedAt = Long.parseLong(p.getProperty("savedAt", Long.toString(timeline.startedAt())));
            if (savedAt < 0) throw new IllegalArgumentException("Invalid save tick");
            return currentGameTime < 0 ? timeline : timeline.restoreClock(currentGameTime, savedAt);
        } catch (RuntimeException ex) {
            throw new IOException("Invalid DarkWinter weather metadata: " + file + "; file was not reset", ex);
        }
    }

    public static void save(Path file, WeatherTimeline state) throws IOException {
        save(file, state, state.startedAt());
    }

    public static void save(Path file, WeatherTimeline state, long savedAt) throws IOException {
        if (savedAt < 0) throw new IllegalArgumentException("Invalid save tick");
        Properties p = new Properties();
        p.setProperty("schema", "1");
        p.setProperty("savedAt", Long.toString(savedAt));
        p.setProperty("target", state.target().id());
        p.setProperty("startedAt", Long.toString(state.startedAt()));
        p.setProperty("durationTicks", Integer.toString(state.durationTicks()));
        p.setProperty("automatic", Boolean.toString(state.automatic()));
        p.setProperty("nextChangeAt", Long.toString(state.nextChangeAt()));
        p.setProperty("groundSnowFrom", Float.toString(state.groundSnowFrom()));
        WeatherValues v = state.from();
        p.setProperty("from", v.cloudCover()+","+v.snowfall()+","+v.windSpeed()+","+v.windX()+","+v.windZ()+","+
                v.fogMultiplier()+","+v.sunVisibility()+","+v.cloudRoundness()+","+v.cloudScale()+","+v.cloudStretch()+","+v.fogBlue()+","+v.skyBlue());
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "darkwinter-weather-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                p.store(writer, "DarkWinter per-world weather. Timings use game ticks.");
            }
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
