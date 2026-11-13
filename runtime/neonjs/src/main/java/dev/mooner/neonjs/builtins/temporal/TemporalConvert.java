/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Undefined;

import java.math.BigInteger;
import java.util.List;

/** ToTemporalX conversions and related abstract operations. */
final class TConv {
    static final int OFFSET_OPTION = 0;
    static final int OFFSET_EXACT = 1;
    static final int OFFSET_WALL = 2;

    private static final String[] TIME_KEYS = {"hour", "microsecond", "millisecond", "minute", "nanosecond", "second"};
    private static final int[] TIME_SLOTS = {0, 4, 3, 1, 5, 2};

    private TConv() {
    }

    /** ToTemporalTimeRecord: [h, mi, s, ms, us, ns]; NaN marks unset fields when {@code partial}. */
    static double[] toTimeRecordFields(JSObject obj, boolean partial) {
        double[] r = new double[6];
        if (partial) {
            for (int i = 0; i < r.length; i++) r[i] = Double.NaN;
        }
        boolean any = false;
        for (int k = 0; k < TIME_KEYS.length; k++) {
            Object v = obj.get(TIME_KEYS[k], obj);
            if (v != Undefined.INSTANCE) {
                r[TIME_SLOTS[k]] = TemporalAbstractOps.toIntegerWithTruncation(v);
                any = true;
            }
        }
        if (!any) throw TemporalAbstractOps.tTypeErr("time-like object must have at least one time property");
        return r;
    }

    static boolean isValidTime(double h, double mi, double s, double ms, double us, double ns) {
        return h >= 0.0 && h <= 23.0 && mi >= 0.0 && mi <= 59.0 && s >= 0.0 && s <= 59.0
                && ms >= 0.0 && ms <= 999.0 && us >= 0.0 && us <= 999.0 && ns >= 0.0 && ns <= 999.0;
    }

    /** RegulateTime */
    static TimeRec regulateTime(double h, double mi, double s, double ms, double us, double ns, Overflow overflow) {
        if (overflow == Overflow.CONSTRAIN) {
            return new TimeRec(
                    (int) coerceIn(h, 0.0, 23.0), (int) coerceIn(mi, 0.0, 59.0), (int) coerceIn(s, 0.0, 59.0),
                    (int) coerceIn(ms, 0.0, 999.0), (int) coerceIn(us, 0.0, 999.0), (int) coerceIn(ns, 0.0, 999.0));
        }
        if (!isValidTime(h, mi, s, ms, us, ns)) throw TemporalAbstractOps.tRangeErr("time is out of range");
        return new TimeRec((int) h, (int) mi, (int) s, (int) ms, (int) us, (int) ns);
    }

    private static double coerceIn(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    /** ToTemporalTime, returning the Time Record. */
    static TimeRec toTime(Object item) {
        return toTime(item, Undefined.INSTANCE);
    }

    /** ToTemporalTime, returning the Time Record. */
    static TimeRec toTime(Object item, Object options) {
        if (item instanceof JSObject) {
            if (item instanceof JSTemporalPlainTime) {
                TOpt.overflow(TOpt.optionsObject(options));
                return ((JSTemporalPlainTime) item).time;
            }
            if (item instanceof JSTemporalPlainDateTime) {
                TOpt.overflow(TOpt.optionsObject(options));
                return ((JSTemporalPlainDateTime) item).dt.time;
            }
            if (item instanceof JSTemporalZonedDateTime) {
                JSTemporalZonedDateTime zoned = (JSTemporalZonedDateTime) item;
                IsoDateTime dt = TZ.isoDateTimeFor(zoned.timeZone, zoned.epochNs);
                TOpt.overflow(TOpt.optionsObject(options));
                return dt.time;
            }
            double[] f = toTimeRecordFields((JSObject) item, false);
            Overflow overflow = TOpt.overflow(TOpt.optionsObject(options));
            return regulateTime(f[0], f[1], f[2], f[3], f[4], f[5], overflow);
        }
        if (!(item instanceof CharSequence)) {
            throw TemporalAbstractOps.tTypeErr("cannot convert " + Ops.typeOf(item) + " to a PlainTime");
        }
        IsoParse r = TemporalParser.parseIsoDateTime(item.toString(), TemporalParser.G_TIME);
        TOpt.overflow(TOpt.optionsObject(options));
        return r.time();
    }

    /** ToTimeRecordOrMidnight */
    static TimeRec toTimeOrMidnight(Object item) {
        return item == Undefined.INSTANCE ? TimeRec.MIDNIGHT : toTime(item);
    }

    /** InterpretTemporalDateTimeFields */
    static IsoDateTime interpretDateTimeFields(String cal, CalFields f, Overflow overflow) {
        IsoDate d = TCal.dateFromFields(cal, f, overflow);
        TimeRec t = regulateTime(f.hour, f.minute, f.second, f.millisecond, f.microsecond, f.nanosecond, overflow);
        return new IsoDateTime(d, t);
    }

    /** ToTemporalDate */
    static JSTemporalPlainDate toDate(Object item) {
        return toDate(item, Undefined.INSTANCE);
    }

    /** ToTemporalDate */
    static JSTemporalPlainDate toDate(Object item, Object options) {
        if (item instanceof JSObject) {
            if (item instanceof JSTemporalPlainDate) {
                JSTemporalPlainDate date = (JSTemporalPlainDate) item;
                TOpt.overflow(TOpt.optionsObject(options));
                return TCreate.plainDate(date.date, date.calendar);
            }
            if (item instanceof JSTemporalZonedDateTime) {
                JSTemporalZonedDateTime zoned = (JSTemporalZonedDateTime) item;
                IsoDateTime dt = TZ.isoDateTimeFor(zoned.timeZone, zoned.epochNs);
                TOpt.overflow(TOpt.optionsObject(options));
                return TCreate.plainDate(dt.date, zoned.calendar);
            }
            if (item instanceof JSTemporalPlainDateTime) {
                JSTemporalPlainDateTime dateTime = (JSTemporalPlainDateTime) item;
                TOpt.overflow(TOpt.optionsObject(options));
                return TCreate.plainDate(dateTime.dt.date, dateTime.calendar);
            }
            String cal = TCal.calendarWithISODefault((JSObject) item);
            CalFields f = TCal.prepareFields(cal, (JSObject) item, TCal.DATE_FIELDS, 0);
            Overflow overflow = TOpt.overflow(TOpt.optionsObject(options));
            IsoDate d = TCal.dateFromFields(cal, f, overflow);
            return TCreate.plainDate(d, cal);
        }
        if (!(item instanceof CharSequence)) {
            throw TemporalAbstractOps.tTypeErr("cannot convert " + Ops.typeOf(item) + " to a PlainDate");
        }
        IsoParse r = TemporalParser.parseIsoDateTime(item.toString(), TemporalParser.G_DATETIME);
        String cal = TCal.canonicalize(r.calendar != null ? r.calendar : TCal.ISO);
        TOpt.overflow(TOpt.optionsObject(options));
        return TCreate.plainDate(r.date(), cal);
    }

    /** ToTemporalDateTime */
    static JSTemporalPlainDateTime toDateTime(Object item) {
        return toDateTime(item, Undefined.INSTANCE);
    }

    /** ToTemporalDateTime */
    static JSTemporalPlainDateTime toDateTime(Object item, Object options) {
        if (item instanceof JSObject) {
            if (item instanceof JSTemporalPlainDateTime) {
                JSTemporalPlainDateTime dateTime = (JSTemporalPlainDateTime) item;
                TOpt.overflow(TOpt.optionsObject(options));
                return TCreate.plainDateTime(dateTime.dt, dateTime.calendar);
            }
            if (item instanceof JSTemporalZonedDateTime) {
                JSTemporalZonedDateTime zoned = (JSTemporalZonedDateTime) item;
                IsoDateTime dt = TZ.isoDateTimeFor(zoned.timeZone, zoned.epochNs);
                TOpt.overflow(TOpt.optionsObject(options));
                return TCreate.plainDateTime(dt, zoned.calendar);
            }
            if (item instanceof JSTemporalPlainDate) {
                JSTemporalPlainDate date = (JSTemporalPlainDate) item;
                TOpt.overflow(TOpt.optionsObject(options));
                return TCreate.plainDateTime(new IsoDateTime(date.date, TimeRec.MIDNIGHT), date.calendar);
            }
            String cal = TCal.calendarWithISODefault((JSObject) item);
            CalFields f = TCal.prepareFields(cal, (JSObject) item, TCal.DATE_FIELDS | TCal.TIME_FIELDS, 0);
            Overflow overflow = TOpt.overflow(TOpt.optionsObject(options));
            IsoDateTime dt = interpretDateTimeFields(cal, f, overflow);
            return TCreate.plainDateTime(dt, cal);
        }
        if (!(item instanceof CharSequence)) {
            throw TemporalAbstractOps.tTypeErr("cannot convert " + Ops.typeOf(item) + " to a PlainDateTime");
        }
        IsoParse r = TemporalParser.parseIsoDateTime(item.toString(), TemporalParser.G_DATETIME);
        TimeRec time = r.hasTime ? r.time() : TimeRec.MIDNIGHT;
        String cal = TCal.canonicalize(r.calendar != null ? r.calendar : TCal.ISO);
        TOpt.overflow(TOpt.optionsObject(options));
        return TCreate.plainDateTime(new IsoDateTime(r.date(), time), cal);
    }

    /** ToTemporalYearMonth */
    static JSTemporalPlainYearMonth toYearMonth(Object item) {
        return toYearMonth(item, Undefined.INSTANCE);
    }

    /** ToTemporalYearMonth */
    static JSTemporalPlainYearMonth toYearMonth(Object item, Object options) {
        if (item instanceof JSObject) {
            if (item instanceof JSTemporalPlainYearMonth) {
                JSTemporalPlainYearMonth yearMonth = (JSTemporalPlainYearMonth) item;
                TOpt.overflow(TOpt.optionsObject(options));
                return TCreate.plainYearMonth(yearMonth.date, yearMonth.calendar);
            }
            String cal = TCal.calendarWithISODefault((JSObject) item);
            CalFields f = TCal.prepareFields(cal, (JSObject) item,
                    TCal.F_YEAR | TCal.F_MONTH | TCal.F_MONTHCODE, 0);
            Overflow overflow = TOpt.overflow(TOpt.optionsObject(options));
            IsoDate d = TCal.yearMonthFromFields(cal, f, overflow);
            return TCreate.plainYearMonth(d, cal);
        }
        if (!(item instanceof CharSequence)) {
            throw TemporalAbstractOps.tTypeErr("cannot convert " + Ops.typeOf(item) + " to a PlainYearMonth");
        }
        IsoParse r = TemporalParser.parseIsoDateTime(item.toString(), TemporalParser.G_YEARMONTH);
        String cal = TCal.canonicalize(r.calendar != null ? r.calendar : TCal.ISO);
        TOpt.overflow(TOpt.optionsObject(options));
        IsoDate d = r.date();
        if (!TM.isoYearMonthWithinLimits(d.year, d.month)) {
            throw TemporalAbstractOps.tRangeErr("year-month outside of supported range");
        }
        CalFields f = TCal.isoDateToFields(cal, d, TCal.TYPE_YEAR_MONTH);
        return TCreate.plainYearMonth(TCal.yearMonthFromFields(cal, f, Overflow.CONSTRAIN), cal);
    }

    /** ToTemporalMonthDay */
    static JSTemporalPlainMonthDay toMonthDay(Object item) {
        return toMonthDay(item, Undefined.INSTANCE);
    }

    /** ToTemporalMonthDay */
    static JSTemporalPlainMonthDay toMonthDay(Object item, Object options) {
        if (item instanceof JSObject) {
            if (item instanceof JSTemporalPlainMonthDay) {
                JSTemporalPlainMonthDay monthDay = (JSTemporalPlainMonthDay) item;
                TOpt.overflow(TOpt.optionsObject(options));
                return TCreate.plainMonthDay(monthDay.date, monthDay.calendar);
            }
            String cal = TCal.calendarWithISODefault((JSObject) item);
            CalFields f = TCal.prepareFields(cal, (JSObject) item, TCal.DATE_FIELDS, 0);
            Overflow overflow = TOpt.overflow(TOpt.optionsObject(options));
            IsoDate d = TCal.monthDayFromFields(cal, f, overflow);
            return TCreate.plainMonthDay(d, cal);
        }
        if (!(item instanceof CharSequence)) {
            throw TemporalAbstractOps.tTypeErr("cannot convert " + Ops.typeOf(item) + " to a PlainMonthDay");
        }
        IsoParse r = TemporalParser.parseIsoDateTime(item.toString(), TemporalParser.G_MONTHDAY);
        String cal = TCal.canonicalize(r.calendar != null ? r.calendar : TCal.ISO);
        TOpt.overflow(TOpt.optionsObject(options));
        if (cal.equals(TCal.ISO)) return TCreate.plainMonthDay(new IsoDate(1972, r.month, r.day), cal);
        IsoDate d = r.date();
        if (!TM.isoDateWithinLimits(d)) throw TemporalAbstractOps.tRangeErr("date outside of supported range");
        CalFields f = TCal.isoDateToFields(cal, d, TCal.TYPE_MONTH_DAY);
        return TCreate.plainMonthDay(TCal.monthDayFromFields(cal, f, Overflow.CONSTRAIN), cal);
    }

    /** ToTemporalInstant, returning epoch nanoseconds. */
    static BigInteger toInstantNs(Object item0) {
        Object item = item0;
        if (item instanceof JSObject) {
            if (item instanceof JSTemporalInstant) return ((JSTemporalInstant) item).epochNs;
            if (item instanceof JSTemporalZonedDateTime) return ((JSTemporalZonedDateTime) item).epochNs;
            item = Ops.toPrimitive(item, Ops.HINT_STRING);
        }
        if (!(item instanceof CharSequence)) {
            throw TemporalAbstractOps.tTypeErr("cannot convert " + Ops.typeOf(item) + " to an Instant");
        }
        IsoParse r = TemporalParser.parseIsoDateTime(item.toString(), TemporalParser.G_INSTANT);
        long offsetNs = r.z ? 0L : requireNonNull(TemporalParser.parseUtcOffset(r.offset));
        IsoDateTime balanced = TM.balanceDateTime(r.date(), r.time(), -offsetNs);
        TM.checkIsoDaysRange(balanced.date);
        BigInteger ns = TM.utcEpochNs(balanced);
        if (!TM.isValidEpochNs(ns)) throw TemporalAbstractOps.tRangeErr("instant outside of supported range");
        return ns;
    }

    /** InterpretISODateTimeOffset; {@code time} null means start-of-day. */
    static BigInteger interpretOffset(
            IsoDate date, TimeRec time, int behaviour, long offsetNs, TimeZone tz, Disambiguation disambiguation,
            OffsetOption offsetOption, boolean matchMinutes) {
        if (time == null) return TZ.startOfDay(tz, date);
        IsoDateTime dt = new IsoDateTime(date, time);
        if (behaviour == OFFSET_WALL || (behaviour == OFFSET_OPTION && offsetOption == OffsetOption.IGNORE)) {
            return TZ.epochNsFor(tz, dt, disambiguation);
        }
        if (behaviour == OFFSET_EXACT || (behaviour == OFFSET_OPTION && offsetOption == OffsetOption.USE)) {
            IsoDateTime balanced = TM.balanceDateTime(date, time, -offsetNs);
            TM.checkIsoDaysRange(balanced.date);
            BigInteger ns = TM.utcEpochNs(balanced);
            if (!TM.isValidEpochNs(ns)) throw TemporalAbstractOps.tRangeErr("instant outside of supported range");
            return ns;
        }
        TM.checkIsoDaysRange(date);
        BigInteger utc = TM.utcEpochNs(dt);
        List<BigInteger> possible = TZ.possibleEpochNs(tz, dt);
        for (BigInteger candidate : possible) {
            long candidateOffset = utc.subtract(candidate).longValue();
            if (candidateOffset == offsetNs) return candidate;
            if (matchMinutes) {
                long rounded = TNum.roundToIncrement(candidateOffset, 60_000_000_000L, RMode.HALF_EXPAND);
                if (rounded == offsetNs) return candidate;
            }
        }
        if (offsetOption == OffsetOption.REJECT) {
            throw TemporalAbstractOps.tRangeErr("offset does not match the time zone");
        }
        return TZ.disambiguate(possible, tz, dt, disambiguation);
    }

    /** ToTemporalZonedDateTime */
    static JSTemporalZonedDateTime toZoned(Object item) {
        return toZoned(item, Undefined.INSTANCE);
    }

    /** ToTemporalZonedDateTime */
    static JSTemporalZonedDateTime toZoned(Object item, Object options) {
        boolean hasUTCDesignator = false;
        boolean matchMinutes = false;
        TimeZone tz;
        String offsetString;
        String cal;
        IsoDate date;
        TimeRec time;
        Disambiguation disambiguation;
        OffsetOption offsetOption;
        if (item instanceof JSObject) {
            if (item instanceof JSTemporalZonedDateTime) {
                JSTemporalZonedDateTime zoned = (JSTemporalZonedDateTime) item;
                JSObject o = TOpt.optionsObject(options);
                TOpt.disambiguation(o);
                TOpt.offset(o, OffsetOption.REJECT);
                TOpt.overflow(o);
                return TCreate.zoned(zoned.epochNs, zoned.timeZone, zoned.calendar);
            }
            cal = TCal.calendarWithISODefault((JSObject) item);
            CalFields f = TCal.prepareFields(cal, (JSObject) item,
                    TCal.DATE_FIELDS | TCal.TIME_FIELDS | TCal.F_OFFSET | TCal.F_TZ, TCal.F_TZ);
            tz = f.timeZone;
            offsetString = f.offset;
            JSObject o = TOpt.optionsObject(options);
            disambiguation = TOpt.disambiguation(o);
            offsetOption = TOpt.offset(o, OffsetOption.REJECT);
            Overflow overflow = TOpt.overflow(o);
            IsoDateTime r = interpretDateTimeFields(cal, f, overflow);
            date = r.date;
            time = r.time;
        } else {
            if (!(item instanceof CharSequence)) {
                throw TemporalAbstractOps.tTypeErr("cannot convert " + Ops.typeOf(item) + " to a ZonedDateTime");
            }
            IsoParse r = TemporalParser.parseIsoDateTime(item.toString(), TemporalParser.G_ZONED);
            tz = TZ.toTimeZone(r.tzAnnotation);
            offsetString = r.offset;
            if (r.z) hasUTCDesignator = true;
            cal = TCal.canonicalize(r.calendar != null ? r.calendar : TCal.ISO);
            matchMinutes = true;
            if (offsetString != null && r.offsetHasSeconds) matchMinutes = false;
            JSObject o = TOpt.optionsObject(options);
            disambiguation = TOpt.disambiguation(o);
            offsetOption = TOpt.offset(o, OffsetOption.REJECT);
            TOpt.overflow(o);
            date = r.date();
            time = r.hasTime ? r.time() : null;
        }
        int behaviour = hasUTCDesignator ? OFFSET_EXACT : offsetString == null ? OFFSET_WALL : OFFSET_OPTION;
        long offsetNs = behaviour == OFFSET_OPTION
                ? requireNonNull(TemporalParser.parseUtcOffset(offsetString))
                : 0L;
        BigInteger ns = interpretOffset(date, time, behaviour, offsetNs, tz, disambiguation, offsetOption, matchMinutes);
        return TCreate.zoned(ns, tz, cal);
    }

    /** IsPartialTemporalObject */
    static boolean isPartialTemporalObject(Object v) {
        if (!(v instanceof JSObject)) return false;
        if (v instanceof JSTemporalPlainDate || v instanceof JSTemporalPlainDateTime
                || v instanceof JSTemporalPlainMonthDay || v instanceof JSTemporalPlainTime
                || v instanceof JSTemporalPlainYearMonth || v instanceof JSTemporalZonedDateTime) {
            return false;
        }
        JSObject object = (JSObject) v;
        if (object.get("calendar", object) != Undefined.INSTANCE) return false;
        if (object.get("timeZone", object) != Undefined.INSTANCE) return false;
        return true;
    }

    /** GetTemporalRelativeToOption: returns a PlainDate, a ZonedDateTime, or null. */
    static JSObject relativeTo(JSObject options) {
        Object value = options.get("relativeTo", options);
        if (value == Undefined.INSTANCE) return null;
        int behaviour = OFFSET_OPTION;
        boolean matchMinutes = false;
        String cal;
        TimeZone tz;
        String offsetString;
        IsoDate date;
        TimeRec time;
        if (value instanceof JSObject) {
            if (value instanceof JSTemporalZonedDateTime) return (JSTemporalZonedDateTime) value;
            if (value instanceof JSTemporalPlainDate) return (JSTemporalPlainDate) value;
            if (value instanceof JSTemporalPlainDateTime) {
                JSTemporalPlainDateTime dateTime = (JSTemporalPlainDateTime) value;
                return TCreate.plainDate(dateTime.dt.date, dateTime.calendar);
            }
            cal = TCal.calendarWithISODefault((JSObject) value);
            CalFields f = TCal.prepareFields(cal, (JSObject) value,
                    TCal.DATE_FIELDS | TCal.TIME_FIELDS | TCal.F_OFFSET | TCal.F_TZ, 0);
            IsoDateTime r = interpretDateTimeFields(cal, f, Overflow.CONSTRAIN);
            tz = f.timeZone;
            offsetString = f.offset;
            if (offsetString == null) behaviour = OFFSET_WALL;
            date = r.date;
            time = r.time;
        } else {
            if (!(value instanceof CharSequence)) {
                throw TemporalAbstractOps.tTypeErr("relativeTo must be a string or object");
            }
            IsoParse r = TemporalParser.parseIsoDateTime(value.toString(),
                    new int[]{TemporalParser.G_ZONED, TemporalParser.G_DATETIME});
            offsetString = r.offset;
            String ann = r.tzAnnotation;
            if (ann == null) {
                tz = null;
            } else {
                tz = TZ.toTimeZone(ann);
                if (r.z) {
                    behaviour = OFFSET_EXACT;
                } else if (offsetString == null) {
                    behaviour = OFFSET_WALL;
                }
                matchMinutes = true;
                if (offsetString != null && r.offsetHasSeconds) matchMinutes = false;
            }
            cal = TCal.canonicalize(r.calendar != null ? r.calendar : TCal.ISO);
            date = r.date();
            time = r.hasTime ? r.time() : null;
        }
        if (tz == null) return TCreate.plainDate(date, cal);
        long offsetNs = behaviour == OFFSET_OPTION
                ? requireNonNull(TemporalParser.parseUtcOffset(offsetString))
                : 0L;
        BigInteger ns = interpretOffset(date, time, behaviour, offsetNs, tz,
                Disambiguation.COMPATIBLE, OffsetOption.REJECT, matchMinutes);
        return TCreate.zoned(ns, tz, cal);
    }

    private static <T> T requireNonNull(T value) {
        if (value == null) throw new NullPointerException();
        return value;
    }
}
