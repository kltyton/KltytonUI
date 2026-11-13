/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.regexp;

/** Early error in a regular expression pattern. */
public final class RegExpSyntaxError extends RuntimeException {
    public RegExpSyntaxError(String message) {
        super(message, null, false, false);
    }
}
