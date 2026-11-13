/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.regexp;

import java.util.Set;

/**
 * Alternatives of a class string set ({@code v} flag) that have the same length in code points: matches one of
 * {@link #strings} (UTF-16 encoded, canonicalized when {@link #ignoreCase}) at the current position, forward or
 * {@link #backward}.
 */
final class StringGroup {
    final int length;
    final Set<String> strings;
    final boolean ignoreCase;
    final boolean backward;

    StringGroup(int length, Set<String> strings, boolean ignoreCase, boolean backward) {
        this.length = length;
        this.strings = strings;
        this.ignoreCase = ignoreCase;
        this.backward = backward;
    }
}
