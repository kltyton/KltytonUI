/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs;

/** Execution was stopped by a sandbox limit or an interrupt; the JS code could not intercept it. */
public class NeonTerminatedException extends NeonException {
    public NeonTerminatedException(String message) {
        super(message);
    }

    public NeonTerminatedException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public boolean isTermination() {
        return true;
    }
}
