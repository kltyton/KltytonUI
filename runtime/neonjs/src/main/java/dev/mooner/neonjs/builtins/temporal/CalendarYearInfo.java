/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import java.util.Objects;

/** Immutable structure of one calendar year. */
public final class CalendarYearInfo {
    /** Epoch day of the first day of each month, followed by the first day of the next year (size = months + 1). */
    public final long[] monthStarts;

    /** Month code of each month, encoded as {@code number * 2 + (1 if leap month)}; e.g. "M05L" = 11. */
    public final int[] monthCodes;

    /** InLeapYear of the Calendar Date Record. */
    public final boolean inLeapYear;

    public final int monthCount;
    public final long start;
    public final long end;

    public CalendarYearInfo(long[] monthStarts, int[] monthCodes, boolean inLeapYear) {
        Objects.requireNonNull(monthStarts, "monthStarts");
        Objects.requireNonNull(monthCodes, "monthCodes");
        if (!(monthStarts.length == monthCodes.length + 1 && monthCodes.length > 0)) {
            throw new IllegalArgumentException("malformed calendar year");
        }
        for (int i = 0; i < monthCodes.length; i++) {
            if (!(monthStarts[i + 1] > monthStarts[i])) {
                throw new IllegalArgumentException("malformed calendar year");
            }
        }
        this.monthStarts = monthStarts;
        this.monthCodes = monthCodes;
        this.inLeapYear = inLeapYear;
        this.monthCount = monthCodes.length;
        this.start = monthStarts[0];
        this.end = monthStarts[monthCodes.length];
    }

    public int getMonthCount() {
        return monthCount;
    }

    public long getStart() {
        return start;
    }

    public long getEnd() {
        return end;
    }

    public long[] getMonthStarts() {
        return monthStarts;
    }

    public int[] getMonthCodes() {
        return monthCodes;
    }

    public boolean isInLeapYear() {
        return inLeapYear;
    }
}
