/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

/**
 * Year-level structure of one calendar in terms of arithmetic years (the {@code [[Year]]} of a Calendar Date Record).
 * Everything else (eras, month codes in fields, arithmetic, reference dates) is computed by Temporal from this.
 *
 * <p>Calls are pure, may happen on any thread, and are only made for |year| &lt;= NonIsoCalendar.MAX_YEAR. Results are
 * cached by the caller, so each call may be moderately expensive but must run in bounded time.
 */
public interface TemporalCalendarSource {
    /** Month boundaries, month codes and leap flag of arithmetic year {@code year}. */
    CalendarYearInfo yearInfo(int year);

    /**
     * Exact number of months in all years before {@code year} counted from a fixed, source-defined origin, so that
     * {@code monthsBeforeYear(y + 1) - monthsBeforeYear(y)} equals the month count of year y for every y.
     */
    long monthsBeforeYear(int year);

    /** An arithmetic year within a few years of the one containing {@code epochDay} (days since 1970-01-01). */
    int estimateYear(long epochDay);
}
