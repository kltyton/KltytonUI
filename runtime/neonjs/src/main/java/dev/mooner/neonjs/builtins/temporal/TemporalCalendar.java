/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Undefined;

/** Calendar Fields Record; NaN / null mean ~unset~. */
final class CalFields {
    public String era = null;
    public double eraYear = Double.NaN;
    public double year = Double.NaN;
    public double month = Double.NaN;
    public String monthCode = null;
    public double day = Double.NaN;
    public double hour = Double.NaN;
    public double minute = Double.NaN;
    public double second = Double.NaN;
    public double millisecond = Double.NaN;
    public double microsecond = Double.NaN;
    public double nanosecond = Double.NaN;
    public String offset = null;
    public TimeZone timeZone = null;

    public int presentMask() {
        int m = 0;
        if (era != null) m = m | TCal.F_ERA;
        if (!Double.isNaN(eraYear)) m = m | TCal.F_ERAYEAR;
        if (!Double.isNaN(year)) m = m | TCal.F_YEAR;
        if (!Double.isNaN(month)) m = m | TCal.F_MONTH;
        if (monthCode != null) m = m | TCal.F_MONTHCODE;
        if (!Double.isNaN(day)) m = m | TCal.F_DAY;
        if (!Double.isNaN(hour)) m = m | TCal.F_HOUR;
        if (!Double.isNaN(minute)) m = m | TCal.F_MINUTE;
        if (!Double.isNaN(second)) m = m | TCal.F_SECOND;
        if (!Double.isNaN(millisecond)) m = m | TCal.F_MS;
        if (!Double.isNaN(microsecond)) m = m | TCal.F_US;
        if (!Double.isNaN(nanosecond)) m = m | TCal.F_NS;
        if (offset != null) m = m | TCal.F_OFFSET;
        if (timeZone != null) m = m | TCal.F_TZ;
        return m;
    }

    public void setTime(TimeRec t) {
        hour = (double) t.hour;
        minute = (double) t.minute;
        second = (double) t.second;
        millisecond = (double) t.millisecond;
        microsecond = (double) t.microsecond;
        nanosecond = (double) t.nanosecond;
    }

    public String getEra() {
        return era;
    }

    public double getEraYear() {
        return eraYear;
    }

    public double getYear() {
        return year;
    }

    public double getMonth() {
        return month;
    }

    public String getMonthCode() {
        return monthCode;
    }

    public double getDay() {
        return day;
    }

    public double getHour() {
        return hour;
    }

    public double getMinute() {
        return minute;
    }

    public double getSecond() {
        return second;
    }

    public double getMillisecond() {
        return millisecond;
    }

    public double getMicrosecond() {
        return microsecond;
    }

    public double getNanosecond() {
        return nanosecond;
    }

    public String getOffset() {
        return offset;
    }

    public TimeZone getTimeZone() {
        return timeZone;
    }
}

/** Calendar operations, dispatching between the ISO 8601 calendar and {@link NonIsoCalendar}. */
final class TCal {
    static final String ISO = "iso8601";

    static final int F_YEAR = 1;
    static final int F_MONTH = 2;
    static final int F_MONTHCODE = 4;
    static final int F_DAY = 8;
    static final int F_HOUR = 16;
    static final int F_MINUTE = 32;
    static final int F_SECOND = 64;
    static final int F_MS = 128;
    static final int F_US = 256;
    static final int F_NS = 512;
    static final int F_OFFSET = 1024;
    static final int F_TZ = 2048;
    static final int F_ERA = 4096;
    static final int F_ERAYEAR = 8192;
    static final int DATE_FIELDS = F_YEAR | F_MONTH | F_MONTHCODE | F_DAY;
    static final int TIME_FIELDS = F_HOUR | F_MINUTE | F_SECOND | F_MS | F_US | F_NS;
    static final int PARTIAL = -1;

    static final int TYPE_DATE = 0;
    static final int TYPE_YEAR_MONTH = 1;
    static final int TYPE_MONTH_DAY = 2;

    // property names sorted by code unit order, with their field flags
    private static final String[] SORTED_NAMES = {
            "day", "era", "eraYear", "hour", "microsecond", "millisecond", "minute", "month", "monthCode", "nanosecond",
            "offset", "second", "timeZone", "year",
    };
    private static final int[] SORTED_FLAGS = {
            F_DAY, F_ERA, F_ERAYEAR, F_HOUR, F_US, F_MS, F_MINUTE, F_MONTH, F_MONTHCODE, F_NS, F_OFFSET, F_SECOND, F_TZ, F_YEAR,
    };

    private TCal() {
    }

    private static JSException tRangeErr(String msg) {
        return JSException.rangeError(msg);
    }

    private static JSException tTypeErr(String msg) {
        return JSException.typeError(msg);
    }

    private static String take(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** The non-ISO calendar for a canonical calendar id, or null for iso8601. */
    static NonIsoCalendar nonIso(String cal) {
        if (ISO.equals(cal)) return null;
        NonIsoCalendar c = NonIsoCalendar.of(cal);
        if (c == null) throw tRangeErr("unsupported calendar " + take(cal, 60));
        return c;
    }

    private static String asciiLower(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return sb.toString();
    }

    private static boolean asciiEqualsIgnoreCase(String s, String lower) {
        if (s.length() != lower.length()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
            if (c != lower.charAt(i)) return false;
        }
        return true;
    }

    /** CanonicalizeCalendar */
    static String canonicalize(String id) {
        if (asciiEqualsIgnoreCase(id, ISO)) return ISO;
        if (id.length() <= 40) {
            String lower = asciiLower(id);
            String c;
            switch (lower) {
                case "islamicc":
                    c = "islamic-civil";
                    break;
                case "ethiopic-amete-alem":
                    c = "ethioaa";
                    break;
                default:
                    c = lower;
                    break;
            }
            if (NonIsoCalendar.supportedIds().contains(c)) return c;
        }
        throw tRangeErr("unsupported calendar " + take(id, 60));
    }

    /** ParseTemporalCalendarString */
    static String parseCalendarString(String s) {
        var r = TemporalParser.tryParseIsoDateTime(s, TemporalParser.ALL_GOALS);
        if (r != null) return r.calendar != null ? r.calendar : ISO;
        if (!TemporalParser.isAnnotationValue(s)) throw tRangeErr("invalid calendar " + take(s, 60));
        return s;
    }

    /** ToTemporalCalendarIdentifier */
    static String toCalendar(Object v) {
        if (v instanceof JSTemporalPlainDate) return ((JSTemporalPlainDate) v).calendar;
        if (v instanceof JSTemporalPlainDateTime) return ((JSTemporalPlainDateTime) v).calendar;
        if (v instanceof JSTemporalPlainMonthDay) return ((JSTemporalPlainMonthDay) v).calendar;
        if (v instanceof JSTemporalPlainYearMonth) return ((JSTemporalPlainYearMonth) v).calendar;
        if (v instanceof JSTemporalZonedDateTime) return ((JSTemporalZonedDateTime) v).calendar;
        if (!(v instanceof CharSequence)) throw tTypeErr("calendar must be a string");
        return canonicalize(parseCalendarString(v.toString()));
    }

    /** Calendar argument of a constructor: undefined → iso8601, String → canonicalized, else TypeError. */
    static String ctorCalendar(Object v) {
        if (v == Undefined.INSTANCE) return ISO;
        if (!(v instanceof CharSequence)) throw tTypeErr("calendar must be a string");
        return canonicalize(v.toString());
    }

    /** GetTemporalCalendarIdentifierWithISODefault */
    static String calendarWithISODefault(JSObject item) {
        if (item instanceof JSTemporalPlainDate) return ((JSTemporalPlainDate) item).calendar;
        if (item instanceof JSTemporalPlainDateTime) return ((JSTemporalPlainDateTime) item).calendar;
        if (item instanceof JSTemporalPlainMonthDay) return ((JSTemporalPlainMonthDay) item).calendar;
        if (item instanceof JSTemporalPlainYearMonth) return ((JSTemporalPlainYearMonth) item).calendar;
        if (item instanceof JSTemporalZonedDateTime) return ((JSTemporalZonedDateTime) item).calendar;
        Object c = item.get("calendar", item);
        if (c == Undefined.INSTANCE) return ISO;
        return toCalendar(c);
    }

    /** ParseMonthCode → normalized month code string (CreateMonthCode). */
    static String toMonthCode(Object v) {
        Object p = Ops.toPrimitive(v, Ops.HINT_STRING);
        if (!(p instanceof CharSequence)) throw tTypeErr("monthCode must be a string");
        String s = p.toString();
        int code = TemporalParser.parseMonthCodeString(s);
        if (code < 0) throw tRangeErr("invalid monthCode " + take(s, 20));
        return s;
    }

    /** ToOffsetString */
    static String toOffsetString(Object v) {
        Object p = Ops.toPrimitive(v, Ops.HINT_STRING);
        if (!(p instanceof CharSequence)) throw tTypeErr("offset must be a string");
        String s = p.toString();
        if (TemporalParser.parseUtcOffset(s) == null) throw tRangeErr("invalid offset " + take(s, 40));
        return s;
    }

    /** CalendarSupportsEra */
    static boolean supportsEra(String cal) {
        return !ISO.equals(cal) && nonIso(cal).supportsEra();
    }

    private static double toIntegerWithTruncation(Object v) {
        double n = Ops.toNumber(v);
        if (Double.isNaN(n) || Double.isInfinite(n)) throw tRangeErr("value must be a finite number");
        double t = n < 0 ? Math.ceil(n) : Math.floor(n);
        return t + 0.0;
    }

    private static double toPositiveIntegerWithTruncation(Object v) {
        double i = toIntegerWithTruncation(v);
        if (i <= 0) throw tRangeErr("value must be positive");
        return i;
    }

    /** PrepareCalendarFields; [required] is a field mask or PARTIAL. CalendarExtraFields adds era / eraYear. */
    static CalFields prepareFields(String cal, JSObject obj, int fieldMask0, int required) {
        int fieldMask = fieldMask0;
        if ((fieldMask & F_YEAR) != 0 && supportsEra(cal)) fieldMask = fieldMask | F_ERA | F_ERAYEAR;
        CalFields r = new CalFields();
        boolean any = false;
        for (int k = 0; k < SORTED_NAMES.length; k++) {
            int flag = SORTED_FLAGS[k];
            if ((fieldMask & flag) == 0) continue;
            Object v = obj.get(SORTED_NAMES[k], obj);
            if (v != Undefined.INSTANCE) {
                any = true;
                switch (flag) {
                    case F_ERA:
                        r.era = Ops.toString(v);
                        break;
                    case F_ERAYEAR:
                        r.eraYear = toIntegerWithTruncation(v);
                        break;
                    case F_YEAR:
                        r.year = toIntegerWithTruncation(v);
                        break;
                    case F_MONTH:
                        r.month = toPositiveIntegerWithTruncation(v);
                        break;
                    case F_MONTHCODE:
                        r.monthCode = toMonthCode(v);
                        break;
                    case F_DAY:
                        r.day = toPositiveIntegerWithTruncation(v);
                        break;
                    case F_HOUR:
                        r.hour = toIntegerWithTruncation(v);
                        break;
                    case F_MINUTE:
                        r.minute = toIntegerWithTruncation(v);
                        break;
                    case F_SECOND:
                        r.second = toIntegerWithTruncation(v);
                        break;
                    case F_MS:
                        r.millisecond = toIntegerWithTruncation(v);
                        break;
                    case F_US:
                        r.microsecond = toIntegerWithTruncation(v);
                        break;
                    case F_NS:
                        r.nanosecond = toIntegerWithTruncation(v);
                        break;
                    case F_OFFSET:
                        r.offset = toOffsetString(v);
                        break;
                    case F_TZ:
                        r.timeZone = TZ.toTimeZone(v);
                        break;
                    default:
                        break;
                }
            } else if (required != PARTIAL) {
                if ((required & flag) != 0) throw tTypeErr(SORTED_NAMES[k] + " is required");
                switch (flag) {
                    case F_HOUR:
                        r.hour = 0.0;
                        break;
                    case F_MINUTE:
                        r.minute = 0.0;
                        break;
                    case F_SECOND:
                        r.second = 0.0;
                        break;
                    case F_MS:
                        r.millisecond = 0.0;
                        break;
                    case F_US:
                        r.microsecond = 0.0;
                        break;
                    case F_NS:
                        r.nanosecond = 0.0;
                        break;
                    default:
                        break;
                }
            }
        }
        if (required == PARTIAL && !any) throw tTypeErr("at least one recognized property is required");
        return r;
    }

    static String monthCodeOf(int month) {
        return month < 10 ? "M0" + month : "M" + month;
    }

    /** CalendarISOToDate */
    static CalDate isoToDate(String cal, IsoDate d) {
        NonIsoCalendar c = nonIso(cal);
        if (c == null) {
            return new CalDate(
                    d.year, d.month, MonthCodes.of(d.month, false), d.day, TM.dayOfYear(d),
                    TM.daysInMonth((long) d.year, d.month), TM.daysInYear((long) d.year), 12, TM.isLeap((long) d.year), null, 0);
        }
        return c.isoToDate(d);
    }

    /** ISODateToFields */
    static CalFields isoDateToFields(String cal, IsoDate d, int type) {
        CalFields f = new CalFields();
        NonIsoCalendar c = nonIso(cal);
        if (c == null) {
            f.monthCode = monthCodeOf(d.month);
            if (type == TYPE_MONTH_DAY || type == TYPE_DATE) f.day = (double) d.day;
            if (type == TYPE_YEAR_MONTH || type == TYPE_DATE) f.year = (double) d.year;
            return f;
        }
        CalDate cd = c.isoToDate(d);
        f.monthCode = MonthCodes.toString(cd.monthCode);
        if (type == TYPE_MONTH_DAY || type == TYPE_DATE) f.day = (double) cd.day;
        if (type == TYPE_YEAR_MONTH || type == TYPE_DATE) f.year = (double) cd.year;
        return f;
    }

    /** CalendarFieldKeysToIgnore on a field mask. */
    private static int fieldKeysToIgnore(String cal, int keys) {
        NonIsoCalendar c = nonIso(cal);
        if (c != null) return c.fieldKeysToIgnore(keys);
        int ignored = keys;
        if ((keys & F_MONTH) != 0) ignored = ignored | F_MONTHCODE;
        if ((keys & F_MONTHCODE) != 0) ignored = ignored | F_MONTH;
        return ignored;
    }

    private static int take(int flag, int addKeys, int fk, int ignored) {
        if ((addKeys & flag) != 0) return 2;
        if ((fk & flag) != 0 && (ignored & flag) == 0) return 1;
        return 0;
    }

    /** CalendarMergeFields */
    static CalFields mergeFields(String cal, CalFields fields, CalFields add) {
        int addKeys = add.presentMask();
        int ignored = fieldKeysToIgnore(cal, addKeys);
        int fk = fields.presentMask();
        CalFields m = new CalFields();
        int t;
        t = take(F_ERA, addKeys, fk, ignored);
        if (t == 2) m.era = add.era;
        else if (t == 1) m.era = fields.era;
        t = take(F_ERAYEAR, addKeys, fk, ignored);
        if (t == 2) m.eraYear = add.eraYear;
        else if (t == 1) m.eraYear = fields.eraYear;
        t = take(F_YEAR, addKeys, fk, ignored);
        if (t == 2) m.year = add.year;
        else if (t == 1) m.year = fields.year;
        t = take(F_MONTH, addKeys, fk, ignored);
        if (t == 2) m.month = add.month;
        else if (t == 1) m.month = fields.month;
        t = take(F_MONTHCODE, addKeys, fk, ignored);
        if (t == 2) m.monthCode = add.monthCode;
        else if (t == 1) m.monthCode = fields.monthCode;
        t = take(F_DAY, addKeys, fk, ignored);
        if (t == 2) m.day = add.day;
        else if (t == 1) m.day = fields.day;
        t = take(F_HOUR, addKeys, fk, ignored);
        if (t == 2) m.hour = add.hour;
        else if (t == 1) m.hour = fields.hour;
        t = take(F_MINUTE, addKeys, fk, ignored);
        if (t == 2) m.minute = add.minute;
        else if (t == 1) m.minute = fields.minute;
        t = take(F_SECOND, addKeys, fk, ignored);
        if (t == 2) m.second = add.second;
        else if (t == 1) m.second = fields.second;
        t = take(F_MS, addKeys, fk, ignored);
        if (t == 2) m.millisecond = add.millisecond;
        else if (t == 1) m.millisecond = fields.millisecond;
        t = take(F_US, addKeys, fk, ignored);
        if (t == 2) m.microsecond = add.microsecond;
        else if (t == 1) m.microsecond = fields.microsecond;
        t = take(F_NS, addKeys, fk, ignored);
        if (t == 2) m.nanosecond = add.nanosecond;
        else if (t == 1) m.nanosecond = fields.nanosecond;
        t = take(F_OFFSET, addKeys, fk, ignored);
        if (t == 2) m.offset = add.offset;
        else if (t == 1) m.offset = fields.offset;
        t = take(F_TZ, addKeys, fk, ignored);
        if (t == 2) m.timeZone = add.timeZone;
        else if (t == 1) m.timeZone = fields.timeZone;
        return m;
    }

    /** CalendarResolveFields */
    static void resolveFields(String cal, CalFields f, int type) {
        NonIsoCalendar c = nonIso(cal);
        if (c != null) {
            c.resolveFields(f, type);
            return;
        }
        boolean needsYear = type == TYPE_DATE || type == TYPE_YEAR_MONTH;
        boolean needsDay = type == TYPE_DATE || type == TYPE_MONTH_DAY;
        if (needsYear && Double.isNaN(f.year)) throw tTypeErr("year is required");
        if (needsDay && Double.isNaN(f.day)) throw tTypeErr("day is required");
        String mc = f.monthCode;
        if (Double.isNaN(f.month) && mc == null) throw tTypeErr("month or monthCode is required");
        if (mc != null) {
            int code = TemporalParser.parseMonthCodeString(mc);
            if ((code & 1) != 0) throw tRangeErr("leap months are not supported in the ISO 8601 calendar");
            int month = code >> 1;
            if (month > 12) throw tRangeErr("invalid monthCode " + mc);
            if (!Double.isNaN(f.month) && f.month != (double) month) throw tRangeErr("month and monthCode do not agree");
            f.month = (double) month;
        }
    }

    /** CalendarDateToISO (fields already resolved). */
    private static IsoDate dateToIso(String cal, CalFields f, Overflow overflow) {
        NonIsoCalendar c = nonIso(cal);
        if (c == null) return TM.regulateIsoDate(f.year, f.month, f.day, overflow);
        return c.dateToIso(f, overflow);
    }

    /** CalendarDateFromFields */
    static IsoDate dateFromFields(String cal, CalFields f, Overflow overflow) {
        resolveFields(cal, f, TYPE_DATE);
        IsoDate r = dateToIso(cal, f, overflow);
        if (!TM.isoDateWithinLimits(r)) throw tRangeErr("date outside of supported range");
        return r;
    }

    /** CalendarYearMonthFromFields */
    static IsoDate yearMonthFromFields(String cal, CalFields f, Overflow overflow) {
        f.day = 1.0;
        resolveFields(cal, f, TYPE_YEAR_MONTH);
        IsoDate r = dateToIso(cal, f, overflow);
        if (!TM.isoYearMonthWithinLimits(r.year, r.month)) throw tRangeErr("year-month outside of supported range");
        return r;
    }

    /** CalendarMonthDayFromFields */
    static IsoDate monthDayFromFields(String cal, CalFields f, Overflow overflow) {
        resolveFields(cal, f, TYPE_MONTH_DAY);
        NonIsoCalendar c = nonIso(cal);
        if (c != null) {
            IsoDate r = c.monthDayToIso(f, overflow);
            if (!TM.isoDateWithinLimits(r)) throw tRangeErr("month-day outside of supported range");
            return r;
        }
        double y = Double.isNaN(f.year) ? 1972.0 : f.year;
        double m = f.month;
        double d = f.day;
        if (overflow == Overflow.CONSTRAIN) {
            int mm = (int) Math.max(1.0, Math.min(12.0, m));
            int dd = (int) Math.max(1.0, Math.min((double) TM.daysInMonthD(y, mm), d));
            return new IsoDate(1972, mm, dd);
        }
        if (!TM.isValidIsoDate(y, m, d)) throw tRangeErr("month-day is out of range");
        return new IsoDate(1972, (int) m, (int) d);
    }

    /** CalendarDateAdd */
    static IsoDate dateAdd(String cal, IsoDate d, DateDuration dur, Overflow overflow) {
        NonIsoCalendar c = nonIso(cal);
        if (c != null) {
            IsoDate r = c.dateAdd(d, dur, overflow);
            if (!TM.isoDateWithinLimits(r)) throw tRangeErr("date outside of supported range");
            return r;
        }
        long y = (long) d.year + dur.years;
        long m = (long) d.month + dur.months;
        long by = TM.balanceYear(y, m);
        int bm = TM.balanceMonth(m);
        IsoDate intermediate = TM.regulateIsoDate((double) by, (double) bm, (double) d.day, overflow);
        long days = dur.days + 7 * dur.weeks;
        IsoDate result = TM.addDays(intermediate, days);
        if (!TM.isoDateWithinLimits(result)) throw tRangeErr("date outside of supported range");
        return result;
    }

    private static boolean compareSurpasses(int sign, long year, int month, int day, IsoDate target) {
        if (year != (long) target.year) return sign * (year - target.year) > 0;
        if (month != target.month) return sign * (month - target.month) > 0;
        if (day != target.day) return sign * (day - target.day) > 0;
        return false;
    }

    private static boolean isoDateSurpasses(int sign, IsoDate base, IsoDate two, long years, long months) {
        long y0 = (long) base.year + years;
        if (compareSurpasses(sign, y0, base.month, base.day, two)) return true;
        if (months == 0L) return false;
        long m0 = (long) base.month + months;
        return compareSurpasses(sign, TM.balanceYear(y0, m0), TM.balanceMonth(m0), base.day, two);
    }

    /** CalendarDateUntil, in closed form (no loops proportional to the size of the difference). */
    static DateDuration dateUntil(String cal, IsoDate one, IsoDate two, TUnit largest) {
        NonIsoCalendar c = nonIso(cal);
        if (c != null) return c.dateUntil(one, two, largest);
        int sign = -TM.compareDate(one, two);
        if (sign == 0) return DateDuration.ZERO;
        long years = 0L;
        long months = 0L;
        if (largest == TUnit.YEAR || largest == TUnit.MONTH) {
            long candidateYears = (long) (two.year - one.year);
            if (candidateYears != 0L) candidateYears -= sign;
            while (!isoDateSurpasses(sign, one, two, candidateYears, 0)) {
                years = candidateYears;
                candidateYears += sign;
            }
            long candidateMonths = (long) sign;
            while (!isoDateSurpasses(sign, one, two, years, candidateMonths)) {
                months = candidateMonths;
                candidateMonths += sign;
            }
            if (largest == TUnit.MONTH) {
                months += years * 12;
                years = 0;
            }
        }
        long iy = TM.balanceYear((long) one.year + years, (long) one.month + months);
        int im = TM.balanceMonth((long) one.month + months);
        int iday = Math.min(one.day, TM.daysInMonth(iy, im));
        long days = TM.epochDays(two) - TM.epochDays(iy, im, iday);
        long weeks = 0L;
        if (largest == TUnit.WEEK) {
            weeks = days / 7;
            days %= 7;
        }
        return new DateDuration(years, months, weeks, days);
    }
}
