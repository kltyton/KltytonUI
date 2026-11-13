/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs;

/** The sandbox's execution-time limit stopped the run. */
public final class NeonTimeoutException extends NeonTerminatedException {
    public NeonTimeoutException(String message) {
        super(message);
    }
}
