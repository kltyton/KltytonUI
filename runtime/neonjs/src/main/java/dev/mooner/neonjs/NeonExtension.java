package dev.mooner.neonjs;

import dev.mooner.neonjs.runtime.Realm;

/**
 * Installs globals and built-in modules into each engine context.
 * Derived from NeonJS and adapted to Java.
 */
@FunctionalInterface
public interface NeonExtension {
    void install(Realm realm);
}
