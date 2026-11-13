/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs;

/** A sandbox instruction, allocation or stack limit stopped the run. */
public final class NeonResourceLimitException extends NeonTerminatedException {
    public NeonResourceLimitException(String message) {
        super(message);
    }
}
