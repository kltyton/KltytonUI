/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

/** Temporal unit. */
enum TUnit {
    YEAR("year", "years", 0L, 0, true),
    MONTH("month", "months", 0L, 0, true),
    WEEK("week", "weeks", 0L, 0, true),
    DAY("day", "days", 86_400_000_000_000L, 0, true),
    HOUR("hour", "hours", 3_600_000_000_000L, 24, false),
    MINUTE("minute", "minutes", 60_000_000_000L, 60, false),
    SECOND("second", "seconds", 1_000_000_000L, 60, false),
    MILLISECOND("millisecond", "milliseconds", 1_000_000L, 1000, false),
    MICROSECOND("microsecond", "microseconds", 1000L, 1000, false),
    NANOSECOND("nanosecond", "nanoseconds", 1L, 1000, false);

    public static final TUnit[] ALL = values();

    public final String singular;
    public final String plural;
    public final long nanos;
    public final int maxIncrement;
    public final boolean isDate;

    TUnit(String singular, String plural, long nanos, int maxIncrement, boolean isDate) {
        this.singular = singular;
        this.plural = plural;
        this.nanos = nanos;
        this.maxIncrement = maxIncrement;
        this.isDate = isDate;
    }

    public boolean isCalendar() {
        return this == YEAR || this == MONTH || this == WEEK;
    }

    public static TUnit larger(TUnit a, TUnit b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
