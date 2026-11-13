/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

/**
 * Internal import/export names. They contain a lone surrogate, which no module export name may
 * contain (export names must be well-formed Unicode), so they cannot collide with names like
 * {@code "*"} in {@code export { x as "*" }}.
 */
public final class ModuleNames {
    public static final ModuleNames INSTANCE = new ModuleNames();

    public static final String NAMESPACE = "\uD800*";
    public static final String DEFERRED_NAMESPACE = "\uD800defer";
    public static final String MODULE_SOURCE = "\uD800source";

    private ModuleNames() {
    }
}
