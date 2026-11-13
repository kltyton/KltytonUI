/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs;

/** {@link NeonContext#interrupt()} or a Java thread interrupt stopped the run. */
public final class NeonInterruptedException extends NeonTerminatedException {
    public NeonInterruptedException(String message) {
        super(message);
    }
}
