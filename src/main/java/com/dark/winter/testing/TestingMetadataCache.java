// Avoids redundant metadata writes using detached JSON snapshots acknowledged only after successful caller-owned persistence.
package com.dark.winter.testing;

import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

final class TestingMetadataCache {
    private final Map<Path,JsonElement> successful=new HashMap<>();

    @FunctionalInterface
    interface Writer { void write(Path file,JsonElement value) throws IOException; }

    synchronized boolean write(Path file,JsonElement value,Writer writer) throws IOException {
        Objects.requireNonNull(file,"file");Objects.requireNonNull(value,"value");Objects.requireNonNull(writer,"writer");
        Path key=file.toAbsolutePath().normalize();
        JsonElement candidate=value.deepCopy();
        JsonElement previous=successful.get(key);
        if(previous!=null && previous.equals(candidate)) return false;
        writer.write(file,candidate);
        successful.put(key,candidate.deepCopy());
        return true;
    }
}
