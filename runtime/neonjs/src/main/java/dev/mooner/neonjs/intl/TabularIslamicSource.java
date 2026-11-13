/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.builtins.temporal.CalendarYearInfo;
import dev.mooner.neonjs.builtins.temporal.TemporalCalendarSource;

/** Tabular Hijri calendar (leap years 2, 5, 7, 10, 13, 16, 18, 21, 24, 26, 29 of 30). */
final class TabularIslamicSource implements TemporalCalendarSource {
    /** R.D. of 1 Muharram 1 AH, civil epoch (Friday, Julian 622-07-16). */
    static final long CIVIL_EPOCH = 227015L;

    private final long epochRd;

    TabularIslamicSource(long epochRd) {
        this.epochRd = epochRd;
    }

    long startOfYear(long year) {
        return epochRd + Civil.RD_TO_EPOCH - 1 + (year - 1) * 354
                + Math.floorDiv(3 + 11 * year, 30L) + 1;
    }

    boolean isLeap(long year) {
        return Math.floorMod(14 + 11 * year, 30L) < 11;
    }

    @Override
    public CalendarYearInfo yearInfo(int year) {
        long y = year;
        long[] starts = new long[13];
        long start = startOfYear(y);
        for (int month = 1; month <= 12; month++) {
            starts[month - 1] = start;
            start += month % 2 == 1 ? 30 : 29;
        }
        starts[12] = startOfYear(y + 1);
        return Civil.info(starts, Civil.CODES_12, isLeap(y));
    }

    @Override
    public long monthsBeforeYear(int year) {
        return 12L * year;
    }

    @Override
    public int estimateYear(long epochDay) {
        return (int) Math.floorDiv((epochDay - (epochRd + Civil.RD_TO_EPOCH)) * 30, 10631L) + 1;
    }
}
