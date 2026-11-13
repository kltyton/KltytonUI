/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.builtins.BuiltinsKt;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;

/** Installs the calendar-derived accessor properties shared by the date-carrying Temporal types. */
final class CalendarGetters {
    interface DateOf {
        IsoDate get(Object thisValue, String name);
    }

    interface CalOf {
        String get(Object thisValue, String name);
    }

    static final int KIND_DATE = 0;
    static final int KIND_YEAR_MONTH = 1;
    static final int KIND_MONTH_DAY = 2;

    private CalendarGetters() {
    }

    /** CalendarISOToDate of the receiver (brand check first, as the getters' RequireInternalSlot). */
    private static CalDate cd(Object t, String name, CalOf cal, DateOf date) {
        IsoDate d = date.get(t, name);
        return TCal.isoToDate(cal.get(t, name), d);
    }

    static void install(Realm realm, JSObject proto, int kind, CalOf cal, DateOf date) {
        BuiltinsKt.getter(proto, realm, "calendarId",
                (function, t, arguments, newTarget) -> cal.get(t, "calendarId"));
        if (kind != KIND_MONTH_DAY) {
            BuiltinsKt.getter(proto, realm, "era", (function, t, arguments, newTarget) -> {
                CalDate c = cd(t, "era", cal, date);
                return c.era != null ? c.era : Undefined.INSTANCE;
            });
            BuiltinsKt.getter(proto, realm, "eraYear", (function, t, arguments, newTarget) -> {
                CalDate c = cd(t, "eraYear", cal, date);
                return c.era == null ? Undefined.INSTANCE : Double.valueOf((double) c.eraYear);
            });
            BuiltinsKt.getter(proto, realm, "year", (function, t, arguments, newTarget) ->
                    Double.valueOf((double) cd(t, "year", cal, date).year));
            BuiltinsKt.getter(proto, realm, "month", (function, t, arguments, newTarget) ->
                    Double.valueOf((double) cd(t, "month", cal, date).month));
        }
        BuiltinsKt.getter(proto, realm, "monthCode", (function, t, arguments, newTarget) ->
                MonthCodes.toString(cd(t, "monthCode", cal, date).monthCode));
        if (kind != KIND_YEAR_MONTH) {
            BuiltinsKt.getter(proto, realm, "day", (function, t, arguments, newTarget) ->
                    Double.valueOf((double) cd(t, "day", cal, date).day));
        }
        if (kind == KIND_DATE) {
            BuiltinsKt.getter(proto, realm, "dayOfWeek", (function, t, arguments, newTarget) ->
                    Double.valueOf((double) TM.dayOfWeek(date.get(t, "dayOfWeek"))));
            BuiltinsKt.getter(proto, realm, "dayOfYear", (function, t, arguments, newTarget) ->
                    Double.valueOf((double) cd(t, "dayOfYear", cal, date).dayOfYear));
            // only the ISO 8601 calendar has a well-defined week numbering
            BuiltinsKt.getter(proto, realm, "weekOfYear", (function, t, arguments, newTarget) -> {
                IsoDate d = date.get(t, "weekOfYear");
                if (TCal.ISO.equals(cal.get(t, "weekOfYear"))) return Double.valueOf((double) TM.weekOfYear(d)[0]);
                return Undefined.INSTANCE;
            });
            BuiltinsKt.getter(proto, realm, "yearOfWeek", (function, t, arguments, newTarget) -> {
                IsoDate d = date.get(t, "yearOfWeek");
                if (TCal.ISO.equals(cal.get(t, "yearOfWeek"))) return Double.valueOf((double) TM.weekOfYear(d)[1]);
                return Undefined.INSTANCE;
            });
            BuiltinsKt.getter(proto, realm, "daysInWeek", (function, t, arguments, newTarget) -> {
                date.get(t, "daysInWeek");
                return 7.0;
            });
        }
        if (kind != KIND_MONTH_DAY) {
            BuiltinsKt.getter(proto, realm, "daysInMonth", (function, t, arguments, newTarget) ->
                    Double.valueOf((double) cd(t, "daysInMonth", cal, date).daysInMonth));
            BuiltinsKt.getter(proto, realm, "daysInYear", (function, t, arguments, newTarget) ->
                    Double.valueOf((double) cd(t, "daysInYear", cal, date).daysInYear));
            BuiltinsKt.getter(proto, realm, "monthsInYear", (function, t, arguments, newTarget) ->
                    Double.valueOf((double) cd(t, "monthsInYear", cal, date).monthsInYear));
            BuiltinsKt.getter(proto, realm, "inLeapYear", (function, t, arguments, newTarget) ->
                    Boolean.valueOf(cd(t, "inLeapYear", cal, date).inLeapYear));
        }
    }
}
