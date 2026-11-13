/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

/** One part of a formatted duration. */
final class DurationPart {
    final String type;
    final String value;
    final String unit;

    DurationPart(String type, String value, String unit) {
        this.type = type;
        this.value = value;
        this.unit = unit;
    }
}
