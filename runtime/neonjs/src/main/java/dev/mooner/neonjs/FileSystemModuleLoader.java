/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Loads modules from a directory tree. Specifiers resolve relative to the importing module; resolution outside
 * {@code root} is rejected, so scripts cannot read arbitrary files.
 * Derived from NeonJS and adapted to Java.
 */
public final class FileSystemModuleLoader implements NeonModuleLoader {
    private final Path root;

    public FileSystemModuleLoader(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public String resolve(String specifier, String referrer) {
        Path base;
        if (referrer != null) {
            Path parent = Paths.get(referrer).getParent();
            base = parent != null ? parent : root;
        } else {
            base = root;
        }
        Path resolved = (specifier.startsWith("/")
                ? root.resolve(specifier.substring(1))
                : base.resolve(specifier)).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Module '" + specifier + "' resolves outside the module root");
        }
        return resolved.toString();
    }

    @Override
    public String load(String key) {
        try {
            Path moduleFile = file(key);
            return moduleFile == null
                    ? null
                    : new String(Files.readAllBytes(moduleFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw FileSystemModuleLoader.<RuntimeException>rethrow(e);
        }
    }

    @Override
    public byte[] loadBytes(String key) {
        try {
            Path moduleFile = file(key);
            return moduleFile == null ? null : Files.readAllBytes(moduleFile);
        } catch (IOException e) {
            throw FileSystemModuleLoader.<RuntimeException>rethrow(e);
        }
    }

    private Path file(String key) throws IOException {
        Path path = Paths.get(key).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            return null;
        }
        Path real = path.toRealPath();
        if (!real.startsWith(root.toRealPath())) {
            throw new SecurityException("Module '" + key + "' resolves outside the module root");
        }
        return real;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException rethrow(Throwable throwable) throws T {
        throw (T) throwable;
    }
}
