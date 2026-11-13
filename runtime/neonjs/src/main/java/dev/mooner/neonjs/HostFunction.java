/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs;

/** A host function callable from JS (Java-friendly SAM). */
@FunctionalInterface
public interface HostFunction {
    Object call(NeonValue[] args);
}
