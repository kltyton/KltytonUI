/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

/** One part of a formatted value: type, text and (for ranges) source. */
final class FmtPart {
    final String type;
    final String value;
    final String source;

    FmtPart(String type, String value, String source) {
        this.type = type;
        this.value = value;
        this.source = source;
    }
}
