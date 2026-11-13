/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.builtins.temporal.CalendarYearInfo;
import dev.mooner.neonjs.builtins.temporal.TemporalCalendarSource;

/** "indian" (Saka): year Y starts on Gregorian March 22 (21 in leap years) of Y + 78. */
final class IndianSource implements TemporalCalendarSource {
    @Override
    public CalendarYearInfo yearInfo(int year) {
        long gregorian = (long) year + 78;
        boolean leap = Civil.isLeap(gregorian);
        long[] starts = new long[13];
        long start = Civil.epochDay(gregorian, 3, leap ? 21 : 22);
        for (int month = 1; month <= 12; month++) {
            starts[month - 1] = start;
            start += month == 1 ? (leap ? 31 : 30) : month <= 6 ? 31 : 30;
        }
        starts[12] = Civil.epochDay(gregorian + 1, 3, Civil.isLeap(gregorian + 1) ? 21 : 22);
        return Civil.info(starts, Civil.CODES_12, leap);
    }

    @Override
    public long monthsBeforeYear(int year) {
        return 12L * year;
    }

    @Override
    public int estimateYear(long epochDay) {
        return (int) (Civil.yearOf(epochDay) - 78);
    }
}
