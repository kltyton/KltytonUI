/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.compiler.ModuleRequest;
import java.util.Map;

/** Host hooks for resolving and loading modules. */
public interface ModuleLoader {
    /** Resolves {@code specifier} relative to {@code referrerKey} (null for scripts / host) to a canonical key. */
    String resolve(String specifier, String referrerKey);

    /** Loads module source for a key; throw to signal failure. */
    ModuleSource load(String key, ModuleRequest request);

    /** Properties added to import.meta (e.g. url). */
    default Map<String, Object> importMetaProperties(String key) {
        return Map.of("url", key);
    }
}
