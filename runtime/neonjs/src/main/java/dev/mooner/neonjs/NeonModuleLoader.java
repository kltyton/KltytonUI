/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs;

import java.nio.charset.StandardCharsets;

/**
 * Host module loader: maps specifiers to canonical keys and keys to source text.
 * Derived from NeonJS and adapted to Java.
 */
public interface NeonModuleLoader {
    /**
     * Resolves {@code specifier} imported from the module {@code referrer} (null for top-level code).
     */
    String resolve(String specifier, String referrer);

    /**
     * Returns the module source for {@code key}, or null if it does not exist.
     */
    String load(String key);

    /**
     * Raw contents for an import whose type attribute is {@code bytes}
     * (default: the UTF-8 encoding of {@link #load(String)}).
     */
    default byte[] loadBytes(String key) {
        String source = load(key);
        return source == null ? null : source.getBytes(StandardCharsets.UTF_8);
    }
}
