// Persists one mod-owned transparency preference atomically without touching frozen render state.
package com.dark.winter.client.fixes.render;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

final class TransparencyPreferenceStore {
    static EarlyTransparency.Mode load(Path gameDirectory) {
        return EarlyTransparency.readPreference(gameDirectory);
    }

    static void save(Path gameDirectory,EarlyTransparency.Mode mode)throws IOException {
        if(gameDirectory==null||mode==null)throw new IOException("Missing transparency preference destination");
        Path directory=gameDirectory.resolve("config").resolve("darkwinter");
        Files.createDirectories(directory);
        Path destination=directory.resolve("transparency.properties");
        Path temporary=Files.createTempFile(directory,"transparency-",".tmp");
        try {
            ByteBuffer bytes=StandardCharsets.UTF_8.encode("mode="+mode.name()+"\n");
            try(FileChannel output=FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
                while(bytes.hasRemaining())output.write(bytes);
                output.force(true);
            }
            // An unsupported atomic replacement is a visible save failure, not a partial overwrite.
            Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try{Files.deleteIfExists(temporary);}catch(IOException|SecurityException ignored){}
        }
    }
    private TransparencyPreferenceStore() { }
}
