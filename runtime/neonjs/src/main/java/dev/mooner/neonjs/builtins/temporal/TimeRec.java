/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

/** Time Record; {@code days} carries overflow days from balancing. */
public final class TimeRec {
    public static final TimeRec MIDNIGHT = new TimeRec(0, 0, 0, 0, 0, 0);
    public static final TimeRec NOON = new TimeRec(12, 0, 0, 0, 0, 0);

    public final int hour;
    public final int minute;
    public final int second;
    public final int millisecond;
    public final int microsecond;
    public final int nanosecond;
    public final long days;

    public TimeRec(int hour, int minute, int second, int millisecond, int microsecond, int nanosecond) {
        this(hour, minute, second, millisecond, microsecond, nanosecond, 0L);
    }

    public TimeRec(int hour, int minute, int second, int millisecond, int microsecond, int nanosecond, long days) {
        this.hour = hour;
        this.minute = minute;
        this.second = second;
        this.millisecond = millisecond;
        this.microsecond = microsecond;
        this.nanosecond = nanosecond;
        this.days = days;
    }

    public long nanosOfDay() {
        return ((((hour * 60L + minute) * 60L + second) * 1000L + millisecond) * 1000L + microsecond) * 1000L
                + nanosecond;
    }

    public int subSecondNanos() {
        return millisecond * 1_000_000 + microsecond * 1000 + nanosecond;
    }

    /** Time of day for 0 &lt;= {@code ns} &lt; nsPerDay. */
    public static TimeRec fromNanosOfDay(long ns) {
        return fromNanosOfDay(ns, 0L);
    }

    /** Time of day for 0 &lt;= {@code ns} &lt; nsPerDay. */
    public static TimeRec fromNanosOfDay(long ns, long days) {
        long r = ns;
        int nanosecond = (int) (r % 1000);
        r /= 1000;
        int microsecond = (int) (r % 1000);
        r /= 1000;
        int millisecond = (int) (r % 1000);
        r /= 1000;
        int second = (int) (r % 60);
        r /= 60;
        int minute = (int) (r % 60);
        r /= 60;
        return new TimeRec((int) r, minute, second, millisecond, microsecond, nanosecond, days);
    }

    public int getHour() {
        return hour;
    }

    public int getMinute() {
        return minute;
    }

    public int getSecond() {
        return second;
    }

    public int getMillisecond() {
        return millisecond;
    }

    public int getMicrosecond() {
        return microsecond;
    }

    public int getNanosecond() {
        return nanosecond;
    }

    public long getDays() {
        return days;
    }
}
