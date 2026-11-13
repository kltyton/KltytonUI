/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.builtins.temporal.CalendarYearInfo;
import dev.mooner.neonjs.builtins.temporal.TemporalCalendarSource;

/** "gregory", "japanese" (arithmetic year = ISO year), "buddhist" (ISO + 543), "roc" (ISO - 1911). */
final class GregorianSource implements TemporalCalendarSource {
    private final int offset;

    GregorianSource(int offset) {
        this.offset = offset;
    }

    @Override
    public CalendarYearInfo yearInfo(int year) {
        long iso = (long) year - offset;
        long[] starts = new long[13];
        for (int month = 1; month <= 12; month++) {
            starts[month - 1] = Civil.epochDay(iso, month, 1);
        }
        starts[12] = Civil.epochDay(iso + 1, 1, 1);
        return Civil.info(starts, Civil.CODES_12, Civil.isLeap(iso));
    }

    @Override
    public long monthsBeforeYear(int year) {
        return 12L * year;
    }

    @Override
    public int estimateYear(long epochDay) {
        return (int) (Civil.yearOf(epochDay) + offset);
    }
}
