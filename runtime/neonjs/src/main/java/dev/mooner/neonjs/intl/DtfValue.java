/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

/** A value to format: the format record, the epoch milliseconds and whether to format in UTC (plain Temporal types). */
final class DtfValue {
    final DtfFormat format;
    final long ms;
    final boolean utc;

    DtfValue(DtfFormat format, long ms, boolean utc) {
        this.format = format;
        this.ms = ms;
        this.utc = utc;
    }
}
