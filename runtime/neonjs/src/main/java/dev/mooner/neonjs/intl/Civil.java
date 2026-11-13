/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.builtins.temporal.CalendarYearInfo;

/** Proleptic Gregorian arithmetic on epoch days (days since 1970-01-01). */
final class Civil {
    private Civil() {
    }

    /** R.D. (Calendrical Calculations fixed day, R.D. 1 = 0001-01-01) to epoch day. */
    static final long RD_TO_EPOCH = -719163L;

    /** Julian day number of epoch day 0 (1970-01-01): epoch day = {@code jd - JD_EPOCH}. */
    static final long JD_EPOCH = 2440588L;

    static final int[] CODES_12 = plainCodes(12);
    static final int[] CODES_13 = plainCodes(13);

    /** Epoch day of a proleptic Gregorian date. */
    static long epochDay(long year, int month, int day) {
        long yy = month <= 2 ? year - 1 : year;
        long era = Math.floorDiv(yy, 400L);
        long yoe = yy - era * 400;
        long mp = (month + 9) % 12;
        long doy = (153 * mp + 2) / 5 + day - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /** Proleptic Gregorian year of an epoch day. */
    static long yearOf(long day) {
        long z = day + 719468;
        long era = Math.floorDiv(z, 146097L);
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long month = mp < 10 ? mp + 3 : mp - 9;
        return yoe + era * 400 + (month <= 2 ? 1 : 0);
    }

    static boolean isLeap(long year) {
        return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    }

    static CalendarYearInfo info(long[] starts, int[] codes, boolean leap) {
        return new CalendarYearInfo(starts, codes, leap);
    }

    /** Month codes "M01".."M[n]". */
    static int[] plainCodes(int count) {
        int[] codes = new int[count];
        for (int i = 0; i < count; i++) {
            codes[i] = (i + 1) << 1;
        }
        return codes;
    }
}
