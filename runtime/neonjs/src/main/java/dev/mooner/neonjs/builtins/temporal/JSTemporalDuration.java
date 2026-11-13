/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.runtime.JSObject;

/** JavaScript Temporal.Duration instance. */
public final class JSTemporalDuration extends JSObject {
    public final double years;
    public final double months;
    public final double weeks;
    public final double days;
    public final double hours;
    public final double minutes;
    public final double seconds;
    public final double milliseconds;
    public final double microseconds;
    public final double nanoseconds;

    public JSTemporalDuration(JSObject proto, double years, double months, double weeks, double days,
                              double hours, double minutes, double seconds, double milliseconds,
                              double microseconds, double nanoseconds) {
        super(proto);
        this.years = years;
        this.months = months;
        this.weeks = weeks;
        this.days = days;
        this.hours = hours;
        this.minutes = minutes;
        this.seconds = seconds;
        this.milliseconds = milliseconds;
        this.microseconds = microseconds;
        this.nanoseconds = nanoseconds;
    }

    public double getYears() {
        return years;
    }

    public double getMonths() {
        return months;
    }

    public double getWeeks() {
        return weeks;
    }

    public double getDays() {
        return days;
    }

    public double getHours() {
        return hours;
    }

    public double getMinutes() {
        return minutes;
    }

    public double getSeconds() {
        return seconds;
    }

    public double getMilliseconds() {
        return milliseconds;
    }

    public double getMicroseconds() {
        return microseconds;
    }

    public double getNanoseconds() {
        return nanoseconds;
    }
}
