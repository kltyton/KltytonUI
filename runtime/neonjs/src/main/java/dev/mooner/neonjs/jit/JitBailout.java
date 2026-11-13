/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.jit;

/** Thrown when a code block cannot be compiled (unsupported instruction, method too large). */
public final class JitBailout extends RuntimeException {
    public JitBailout(String message) {
        super(message, null, false, false);
    }
}
