/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

/** Date Duration Record (all components are integers well within the Long range). */
public final class DateDuration {
    public static final DateDuration ZERO = new DateDuration(0L, 0L, 0L, 0L);

    public final long years;
    public final long months;
    public final long weeks;
    public final long days;

    public DateDuration(long years, long months, long weeks, long days) {
        this.years = years;
        this.months = months;
        this.weeks = weeks;
        this.days = days;
    }

    public int sign() {
        if (years != 0L) return Long.signum(years);
        if (months != 0L) return Long.signum(months);
        if (weeks != 0L) return Long.signum(weeks);
        return Long.signum(days);
    }

    public long getYears() {
        return years;
    }

    public long getMonths() {
        return months;
    }

    public long getWeeks() {
        return weeks;
    }

    public long getDays() {
        return days;
    }
}
