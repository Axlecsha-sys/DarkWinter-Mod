// Loads packaged WinterKore Engine libraries with content-addressed, hash-verified extraction.
package com.winterkore.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Java host adapter only: WKE's C++ core has no dependency on this class or Minecraft.
 * Resolving a library never loads it; the FFM owner controls its native lifetime.
 */
public final class WkeNativeLibraries {
    private static final long MAX_LIBRARY_BYTES = 64L * 1024 * 1024;
    private static final String RESOURCE_ROOT = "/assets/wke/native/windows-x86_64/";
    private WkeNativeLibraries() { }

    /** Explicit development/test overrides take priority and retain their existing semantics. */
    public static Path resolve(String overrideProperty, String libraryName) throws IOException {
        String configured = System.getProperty(overrideProperty, "");
        if (!configured.isBlank()) {
            Path path = Path.of(configured).toRealPath();
            if (!Files.isRegularFile(path)) throw new IOException("WKE library override is not a file: " + path);
            return path;
        }
        return bundled(libraryName);
    }

    public static boolean bundledAvailable(String libraryName) {
        return supportedPlatform() && validName(libraryName)
                && WkeNativeLibraries.class.getResource(RESOURCE_ROOT + libraryName) != null
                && WkeNativeLibraries.class.getResource(RESOURCE_ROOT + libraryName + ".sha256") != null;
    }

    public static boolean explicitlyConfigured(String property) {
        return !System.getProperty(property, "").isBlank();
    }

    /** A unique filename per binary hash allows a new build while an older DLL remains loaded. */
    public static synchronized Path bundled(String libraryName) throws IOException {
        if (!supportedPlatform()) throw new IOException("Packaged WKE requires Windows x86-64");
        if (!validName(libraryName)) throw new IOException("Invalid WKE library name");
        String expected = expectedDigest(libraryName);
        String configuredCache = System.getProperty("winterkore.native.cache", "");
        Path cache = (configuredCache.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "WinterKoreEngine", "native")
                : Path.of(configuredCache)).toAbsolutePath().normalize();
        Files.createDirectories(cache);
        cache = cache.toRealPath();
        String stem = libraryName.substring(0, libraryName.length() - 4);
        Path destination = cache.resolve(stem + "-" + expected + ".dll");
        if (Files.exists(destination)) {
            verifyFile(destination, expected);
            return destination.toRealPath();
        }
        Path temporary = Files.createTempFile(cache, stem + "-", ".part");
        try {
            MessageDigest digest = sha256();
            long bytes = 0;
            try (InputStream input = resource(libraryName); OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) continue;
                    bytes += count;
                    if (bytes > MAX_LIBRARY_BYTES) throw new IOException("WKE library exceeds its extraction limit");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            if (bytes == 0 || !expected.equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new IOException("Packaged WKE library SHA-256 mismatch: " + libraryName);
            }
            // Another JVM may have published this exact content while this JVM was copying.
            if (Files.exists(destination)) {
                verifyFile(destination, expected);
            } else {
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    try {
                        Files.move(temporary, destination);
                    } catch (java.nio.file.FileAlreadyExistsException published) {
                        verifyFile(destination, expected);
                    }
                } catch (java.nio.file.FileAlreadyExistsException published) {
                    verifyFile(destination, expected);
                }
            }
            verifyFile(destination, expected);
            return destination.toRealPath();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String expectedDigest(String libraryName) throws IOException {
        try (InputStream input = resource(libraryName + ".sha256")) {
            byte[] value = input.readNBytes(1025);
            if (value.length > 1024) throw new IOException("Oversized WKE SHA-256 manifest");
            String expected = new String(value, StandardCharsets.US_ASCII).trim().toLowerCase(Locale.ROOT);
            if (!expected.matches("[0-9a-f]{64}")) throw new IOException("Invalid WKE SHA-256 manifest");
            return expected;
        }
    }

    private static InputStream resource(String name) throws IOException {
        InputStream input = WkeNativeLibraries.class.getResourceAsStream(RESOURCE_ROOT + name);
        if (input == null) throw new IOException("Packaged WKE library resource is missing: " + name);
        return input;
    }

    private static void verifyFile(Path path, String expected) throws IOException {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            throw new IOException("Invalid cached WKE library: " + path);
        }
        long size = Files.size(path);
        if (size <= 0 || size > MAX_LIBRARY_BYTES) throw new IOException("Invalid cached WKE library size");
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        if (!expected.equals(HexFormat.of().formatHex(digest.digest()))) {
            throw new IOException("Cached WKE library SHA-256 mismatch: " + path.getFileName());
        }
    }

    private static boolean validName(String name) {
        return name != null && name.matches("wke_[a-z0-9_]+\\.dll");
    }

    private static boolean supportedPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") && (architecture.equals("amd64") || architecture.equals("x86_64"));
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
