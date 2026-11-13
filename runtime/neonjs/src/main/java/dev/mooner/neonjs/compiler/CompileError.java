/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

/** Compilation failure for invalid intermediate state. */
public final class CompileError extends RuntimeException {
    public CompileError(String message) {
        super(message);
    }
}
