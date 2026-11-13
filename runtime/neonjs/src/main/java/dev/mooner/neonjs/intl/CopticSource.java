/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.builtins.temporal.CalendarYearInfo;
import dev.mooner.neonjs.builtins.temporal.TemporalCalendarSource;

/** "coptic", "ethiopic" and "ethioaa": twelve 30-day months and a 5- or 6-day thirteenth, leap when year % 4 == 3. */
final class CopticSource implements TemporalCalendarSource {
    /** R.D. of 1 Thout 1 AM (Julian 284-08-29). */
    static final long COPTIC_EPOCH = 103605L;
    /** R.D. of 1 Maskaram 1 (Amete Mihret, Julian 8-08-29). */
    static final long ETHIOPIC_EPOCH = 2796L;

    private final long epochRd;
    private final int yearOffset;

    CopticSource(long epochRd, int yearOffset) {
        this.epochRd = epochRd;
        this.yearOffset = yearOffset;
    }

    private long startOfYear(long year) {
        return epochRd + Civil.RD_TO_EPOCH + 365 * (year - 1) + Math.floorDiv(year, 4L);
    }

    @Override
    public CalendarYearInfo yearInfo(int year) {
        long y = (long) year - yearOffset;
        long start = startOfYear(y);
        long[] starts = new long[14];
        for (int month = 0; month < 13; month++) {
            starts[month] = start + 30 * month;
        }
        starts[13] = startOfYear(y + 1);
        return Civil.info(starts, Civil.CODES_13, Math.floorMod(y, 4L) == 3L);
    }

    @Override
    public long monthsBeforeYear(int year) {
        return 13L * year;
    }

    @Override
    public int estimateYear(long epochDay) {
        return (int) (Math.floorDiv((epochDay - (epochRd + Civil.RD_TO_EPOCH)) * 4, 1461L)
                + 1 + yearOffset);
    }
}
