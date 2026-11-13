/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.builtins.temporal.CalendarYearInfo;
import dev.mooner.neonjs.builtins.temporal.TemporalCalendarSource;

/** "hebrew": the arithmetic (molad and dehiyyot) calendar of Calendrical Calculations. */
final class HebrewSource implements TemporalCalendarSource {
    /** R.D. of 1 Tishri 1 AM (Julian -3761-10-07). */
    static final long EPOCH = -1373427L;
    static final int M02 = 4;
    static final int M03 = 6;
    static final int M05L = 11;
    static final int[] LEAP_CODES = {2, 4, 6, 8, 10, 11, 12, 14, 16, 18, 20, 22, 24};

    private static boolean isLeap(long year) {
        return Math.floorMod(7 * year + 1, 19L) < 7;
    }

    private static long monthsElapsed(long year) {
        return Math.floorDiv(235 * year - 234, 19L);
    }

    private static long elapsedDays(long year) {
        long months = monthsElapsed(year);
        long parts = 12084 + 13753 * months;
        long days = 29 * months + Math.floorDiv(parts, 25920L);
        return Math.floorMod(3 * (days + 1), 7L) < 3 ? days + 1 : days;
    }

    private static int yearLengthCorrection(long year) {
        long ny0 = elapsedDays(year - 1);
        long ny1 = elapsedDays(year);
        long ny2 = elapsedDays(year + 1);
        if (ny2 - ny1 == 356L) {
            return 2;
        }
        if (ny1 - ny0 == 382L) {
            return 1;
        }
        return 0;
    }

    private static long newYear(long year) {
        return EPOCH + Civil.RD_TO_EPOCH + elapsedDays(year) + yearLengthCorrection(year);
    }

    @Override
    public CalendarYearInfo yearInfo(int year) {
        long y = year;
        long start = newYear(y);
        long end = newYear(y + 1);
        int length = (int) (end - start);
        boolean leap = isLeap(y);
        boolean longHeshvan = length % 10 == 5;
        boolean shortKislev = length % 10 == 3;
        int[] codes = leap ? LEAP_CODES : Civil.CODES_12;
        long[] starts = new long[codes.length + 1];
        long day = start;
        for (int i = 0; i < codes.length; i++) {
            starts[i] = day;
            if (codes[i] == M02) {
                day += longHeshvan ? 30 : 29;
            } else if (codes[i] == M03) {
                day += shortKislev ? 29 : 30;
            } else if (codes[i] == M05L) {
                day += 30;
            } else {
                day += (codes[i] >> 1) % 2 == 1 ? 30 : 29;
            }
        }
        starts[codes.length] = end;
        return Civil.info(starts, codes, leap);
    }

    @Override
    public long monthsBeforeYear(int year) {
        return monthsElapsed(year);
    }

    @Override
    public int estimateYear(long epochDay) {
        return (int) (Math.floor((epochDay - (EPOCH + Civil.RD_TO_EPOCH)) / 365.2468) + 1);
    }
}
