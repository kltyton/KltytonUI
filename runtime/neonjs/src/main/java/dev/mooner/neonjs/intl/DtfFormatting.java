/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.text.ConstrainedFieldPosition;
import com.ibm.icu.text.DateFormat;
import com.ibm.icu.text.DateIntervalFormat;
import com.ibm.icu.text.DateTimePatternGenerator;
import com.ibm.icu.text.FormattedValue;
import com.ibm.icu.text.SimpleDateFormat;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;
import dev.mooner.neonjs.builtins.temporal.IsoDate;
import dev.mooner.neonjs.builtins.temporal.IsoDateTime;
import dev.mooner.neonjs.builtins.temporal.JSTemporalInstant;
import dev.mooner.neonjs.builtins.temporal.JSTemporalPlainDate;
import dev.mooner.neonjs.builtins.temporal.JSTemporalPlainDateTime;
import dev.mooner.neonjs.builtins.temporal.JSTemporalPlainMonthDay;
import dev.mooner.neonjs.builtins.temporal.JSTemporalPlainTime;
import dev.mooner.neonjs.builtins.temporal.JSTemporalPlainYearMonth;
import dev.mooner.neonjs.builtins.temporal.JSTemporalZonedDateTime;
import dev.mooner.neonjs.builtins.temporal.TimeRec;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.TerminationException;

import java.math.BigInteger;
import java.text.AttributedCharacterIterator;
import java.text.CharacterIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** FormatDateTime, FormatDateTimeToParts, FormatDateTimeRange(ToParts). */
final class DtfFormatting {
    private DtfFormatting() {
    }

    /** U+202F NARROW NO-BREAK SPACE: replaced by U+0020 in output, like other engines, for web compatibility. */
    static final char NNBSP = '\u202F';

    private static final long MS_PER_DAY = 86_400_000L;
    private static final BigInteger NS_PER_MS = BigInteger.valueOf(1_000_000L);

    /** ToDateTimeFormattable */
    static Object toFormattable(Object value) {
        return isTemporal(value) ? value : Ops.toNumber(value);
    }

    static boolean isTemporal(Object value) {
        return value instanceof JSTemporalPlainDate || value instanceof JSTemporalPlainDateTime
                || value instanceof JSTemporalPlainTime || value instanceof JSTemporalPlainYearMonth
                || value instanceof JSTemporalPlainMonthDay || value instanceof JSTemporalInstant
                || value instanceof JSTemporalZonedDateTime;
    }

    private static long epochMs(IsoDate date, TimeRec time) {
        long days = java.time.LocalDate.of(date.year, date.month, date.day).toEpochDay();
        return days * MS_PER_DAY + ((time.hour * 60L + time.minute) * 60L + time.second) * 1000L
                + time.millisecond;
    }

    private static void checkCalendar(JSIntlDateTimeFormat dtf, String calendar, boolean isoAllowed) {
        if (!calendar.equals(dtf.calendar) && !(isoAllowed && "iso8601".equals(calendar))) {
            throw Dsl.rangeErr("Calendar " + calendar + " does not match the DateTimeFormat calendar "
                    + dtf.calendar);
        }
    }

    private static DtfFormat temporal(JSIntlDateTimeFormat dtf, String kind) {
        DtfFormat format = DtfCreate.temporalFormat(dtf, kind);
        if (format == null) {
            throw Dsl.typeErr("The DateTimeFormat options have no fields in common with the Temporal value");
        }
        return format;
    }

    /** HandleDateTimeValue */
    static DtfValue handle(JSIntlDateTimeFormat dtf, Object value) {
        if (value instanceof Double) {
            double time = timeClip((Double) value);
            if (Double.isNaN(time)) {
                throw Dsl.rangeErr("Invalid time value");
            }
            return new DtfValue(dtf.format, (long) time, false);
        }
        if (value instanceof JSTemporalPlainDate) {
            JSTemporalPlainDate date = (JSTemporalPlainDate) value;
            checkCalendar(dtf, date.calendar, true);
            return new DtfValue(temporal(dtf, "date"), epochMs(date.date, TimeRec.NOON), true);
        }
        if (value instanceof JSTemporalPlainDateTime) {
            JSTemporalPlainDateTime dateTime = (JSTemporalPlainDateTime) value;
            checkCalendar(dtf, dateTime.calendar, true);
            return new DtfValue(temporal(dtf, "datetime"),
                    epochMs(dateTime.dt.date, dateTime.dt.time), true);
        }
        if (value instanceof JSTemporalPlainYearMonth) {
            JSTemporalPlainYearMonth yearMonth = (JSTemporalPlainYearMonth) value;
            checkCalendar(dtf, yearMonth.calendar, false);
            return new DtfValue(temporal(dtf, "year-month"), epochMs(yearMonth.date, TimeRec.NOON), true);
        }
        if (value instanceof JSTemporalPlainMonthDay) {
            JSTemporalPlainMonthDay monthDay = (JSTemporalPlainMonthDay) value;
            checkCalendar(dtf, monthDay.calendar, false);
            return new DtfValue(temporal(dtf, "month-day"), epochMs(monthDay.date, TimeRec.NOON), true);
        }
        if (value instanceof JSTemporalPlainTime) {
            JSTemporalPlainTime time = (JSTemporalPlainTime) value;
            return new DtfValue(temporal(dtf, "time"), epochMs(new IsoDate(1970, 1, 1), time.time), true);
        }
        if (value instanceof JSTemporalInstant) {
            BigInteger ms = floorDiv(((JSTemporalInstant) value).epochNs, NS_PER_MS);
            return new DtfValue(temporal(dtf, "instant"), ms.longValue(), false);
        }
        if (value instanceof JSTemporalZonedDateTime) {
            throw Dsl.typeErr("Temporal.ZonedDateTime is not supported by DateTimeFormat format methods; "
                    + "use toLocaleString");
        }
        throw Dsl.typeErr("Invalid date value");
    }

    private static BigInteger floorDiv(BigInteger a, BigInteger b) {
        BigInteger[] quotientAndRemainder = a.divideAndRemainder(b);
        return quotientAndRemainder[1].signum() < 0
                ? quotientAndRemainder[0].subtract(BigInteger.ONE)
                : quotientAndRemainder[0];
    }

    /** TimeClip */
    static double timeClip(double time) {
        if (!Double.isFinite(time) || Math.abs(time) > 8.64e15) {
            return Double.NaN;
        }
        return Ops.integerPart(time) + 0.0;
    }

    private static <T> T icuCall(Supplier<T> block) {
        try {
            return block.get();
        } catch (JSException e) {
            throw e;
        } catch (TerminationException e) {
            throw e;
        } catch (RuntimeException invalid) {
            throw Dsl.rangeErr("Unable to format the date");
        }
    }

    static String format(Realm realm, JSIntlDateTimeFormat dtf, Object value) {
        DtfValue formatted = handle(dtf, value);
        String text = icuCall(() -> DtfIcu.of(dtf).formatter(formatted.format, formatted.utc)
                .format(new java.util.Date(formatted.ms)));
        return Dsl.checkedString(realm, text);
    }

    /** A formatted part: type, value and (for ranges) source index (-1 shared, 0 start, 1 end). */
    static final class Part {
        final String type;
        final String value;
        final int source;

        Part(String type, String value) {
            this(type, value, -1);
        }

        Part(String type, String value, int source) {
            this.type = type;
            this.value = value;
            this.source = source;
        }
    }

    static List<Part> partsOf(JSIntlDateTimeFormat dtf, DtfValue value) {
        return icuCall(() -> {
            SimpleDateFormat formatter = DtfIcu.of(dtf).formatter(value.format, value.utc);
            AttributedCharacterIterator iterator = formatter.formatToCharacterIterator(
                    new java.util.Date(value.ms));
            StringBuilder text = new StringBuilder();
            char c = iterator.first();
            while (c != CharacterIterator.DONE) {
                text.append(c);
                c = iterator.next();
            }
            boolean usesYearName = patternHas(value.format.pattern, 'U');
            List<Part> out = new ArrayList<>();
            int i = iterator.getBeginIndex();
            while (i < iterator.getEndIndex()) {
                iterator.setIndex(i);
                int limit = iterator.getRunLimit();
                String type = "literal";
                for (Object key : iterator.getAttributes().keySet()) {
                    if (key instanceof DateFormat.Field) {
                        type = typeOf((DateFormat.Field) key, usesYearName);
                    }
                }
                addPart(out, type, text.substring(i - iterator.getBeginIndex(),
                        limit - iterator.getBeginIndex()), -1);
                i = limit;
            }
            return out;
        });
    }

    private static void addPart(List<Part> out, String type, String value, int source) {
        if (value.isEmpty()) {
            return;
        }
        Part last = out.isEmpty() ? null : out.get(out.size() - 1);
        if ("literal".equals(type) && last != null && "literal".equals(last.type) && last.source == source) {
            out.set(out.size() - 1, new Part("literal", last.value + value, source));
        } else {
            out.add(new Part(type, value, source));
        }
    }

    private static boolean patternHas(String pattern, char ch) {
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == ch) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation") // ICU marks RELATED_YEAR internal, but still reports related-year fields with it
    private static String typeOf(DateFormat.Field field, boolean usesYearName) {
        if (field == DateFormat.Field.ERA) {
            return "era";
        }
        if (field == DateFormat.Field.YEAR || field == DateFormat.Field.EXTENDED_YEAR
                || field == DateFormat.Field.YEAR_WOY) {
            return usesYearName ? "yearName" : "year";
        }
        if (field == DateFormat.Field.RELATED_YEAR) {
            return "relatedYear";
        }
        if (field == DateFormat.Field.MONTH) {
            return "month";
        }
        if (field == DateFormat.Field.DAY_OF_MONTH) {
            return "day";
        }
        if (field == DateFormat.Field.DAY_OF_WEEK || field == DateFormat.Field.DOW_LOCAL) {
            return "weekday";
        }
        if (field == DateFormat.Field.AM_PM || field == DateFormat.Field.AM_PM_MIDNIGHT_NOON
                || field == DateFormat.Field.FLEXIBLE_DAY_PERIOD) {
            return "dayPeriod";
        }
        if (field == DateFormat.Field.HOUR0 || field == DateFormat.Field.HOUR1
                || field == DateFormat.Field.HOUR_OF_DAY0 || field == DateFormat.Field.HOUR_OF_DAY1) {
            return "hour";
        }
        if (field == DateFormat.Field.MINUTE) {
            return "minute";
        }
        if (field == DateFormat.Field.SECOND) {
            return "second";
        }
        if (field == DateFormat.Field.MILLISECOND) {
            return "fractionalSecond";
        }
        if (field == DateFormat.Field.TIME_ZONE) {
            return "timeZoneName";
        }
        return "literal";
    }

    static JSArray partsArray(Realm realm, List<Part> parts, boolean withSource) {
        List<Object> out = new ArrayList<>(parts.size());
        long total = 0;
        for (int index = 0; index < parts.size(); index++) {
            Dsl.tick(realm, index);
            Part part = parts.get(index);
            JSObject object = Dsl.partObject(realm, part.type, part.value);
            if (withSource) {
                object.createDataPropertyOrThrow("source",
                        part.source == 0 ? "startRange" : part.source == 1 ? "endRange" : "shared");
            }
            total += part.value.length();
            out.add(object);
        }
        realm.agent.checkStringLength(total);
        return Dsl.jsArray(realm, out);
    }

    // ------------------------------------------------------------------ ranges

    private static boolean sameKind(Object x, Object y) {
        if (x instanceof Double) {
            return y instanceof Double;
        }
        return x.getClass() == y.getClass();
    }

    /** PartitionDateTimeRangePattern */
    static List<Part> rangeParts(JSIntlDateTimeFormat dtf, Object x, Object y) {
        if ((isTemporal(x) || isTemporal(y)) && !sameKind(x, y)) {
            throw Dsl.typeErr("Temporal values of different types cannot form a range");
        }
        DtfValue vx = handle(dtf, x);
        DtfValue vy = handle(dtf, y);
        return icuCall(() -> {
            DtfIcu icu = DtfIcu.of(dtf);
            DateIntervalFormat interval = icu.interval(vx.format, vx.utc);
            FormattedValue formatted = interval.formatToValue(icu.calendarAt(vx.ms, vx.utc),
                    icu.calendarAt(vy.ms, vy.utc));
            String text = formatted.toString().replace(NNBSP, ' ');
            int length = text.length();
            String[] fieldType = new String[length];
            int[] fieldId = new int[length];
            int[] source = new int[length];
            for (int i = 0; i < length; i++) {
                fieldId[i] = -1;
                source[i] = -1;
            }
            boolean hasSpan = false;
            boolean usesYearName = patternHas(vx.format.pattern, 'U');
            ConstrainedFieldPosition position = new ConstrainedFieldPosition();
            int id = 0;
            while (formatted.nextPosition(position)) {
                java.text.Format.Field field = position.getField();
                if (field instanceof DateIntervalFormat.SpanField) {
                    hasSpan = true;
                    int spanValue = position.getFieldValue() instanceof Number
                            ? ((Number) position.getFieldValue()).intValue() : -1;
                    for (int k = position.getStart(); k < position.getLimit(); k++) {
                        source[k] = spanValue;
                    }
                } else if (field instanceof DateFormat.Field) {
                    String type = typeOf((DateFormat.Field) field, usesYearName);
                    id++;
                    for (int k = position.getStart(); k < position.getLimit(); k++) {
                        fieldType[k] = type;
                        fieldId[k] = id;
                    }
                }
            }
            if (!hasSpan) {
                return partsOf(dtf, vx);
            }
            List<Part> out = new ArrayList<>();
            int i = 0;
            while (i < length) {
                int j = i + 1;
                while (j < length && fieldId[j] == fieldId[i] && source[j] == source[i]) {
                    j++;
                }
                String value = text.substring(i, j);
                String type = fieldType[i] == null ? "literal" : fieldType[i];
                if ("hour".equals(type)) {
                    value = fixHour(value, dtf.hourCycle, "2-digit".equals(vx.format.fields.get("hour")));
                }
                addPart(out, type, value, source[i]);
                i = j;
            }
            return out;
        });
    }

    /** DateIntervalFormat ignores K/k skeleton letters: re-map hour values for h11 / h24. */
    private static String fixHour(String value, String hourCycle, boolean twoDigit) {
        if (!"h11".equals(hourCycle) && !"h24".equals(hourCycle)) {
            return value;
        }
        if (value.isEmpty()) {
            return value;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return value;
            }
        }
        int hour = Integer.parseInt(value);
        int newHour;
        if ("h11".equals(hourCycle) && hour == 12) {
            newHour = 0;
        } else if ("h24".equals(hourCycle) && hour == 0) {
            newHour = 24;
        } else {
            return value;
        }
        String text = Integer.toString(newHour);
        if (twoDigit && text.length() < 2) {
            text = "0" + text;
        }
        return text;
    }
}
