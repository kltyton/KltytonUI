/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.regexp;

/** Thrown when a match needs more backtracking memory than allowed. */
public final class RegExpStackOverflowError extends RuntimeException {
    public RegExpStackOverflowError() {
        super("regular expression backtracking stack overflow", null, false, false);
    }
}
