/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.runtime.Agent;
import dev.mooner.neonjs.runtime.NumberConv;

import java.math.BigInteger;

/** ISO Date-Time Parse Record (plus parser bookkeeping). */
final class IsoParse {
    public int year = 0;
    public boolean yearAbsent = false;
    public int month = 1;
    public int day = 1;
    public boolean hasDay = true;
    public boolean hasTime = false;
    public int hour = 0;
    public int minute = 0;
    public int second = 0;
    public int millisecond = 0;
    public int microsecond = 0;
    public int nanosecond = 0;
    public boolean z = false;
    public String offset = null;
    public boolean offsetHasSeconds = false;
    public String tzAnnotation = null;
    public String calendar = null;

    // annotation processing state (applied while parsing, so nothing proportional to the input is retained)
    public boolean haveCalendar = false;
    public boolean calendarCritical = false;
    public String annotationError = null;

    TimeRec time() {
        return new TimeRec(hour, minute, second, millisecond, microsecond, nanosecond);
    }

    IsoDate date() {
        return new IsoDate(year, month, day);
    }
}

/** Hand-written parser for the RFC 9557 / ISO 8601 grammar used by Temporal. */
final class TemporalParser {
    private static final int INTERRUPT_STRIDE = 1 << 16;

    static final int G_DATETIME = 0; // TemporalDateTimeString[~Zoned]
    static final int G_ZONED = 1; // TemporalDateTimeString[+Zoned]
    static final int G_INSTANT = 2;
    static final int G_TIME = 3;
    static final int G_MONTHDAY = 4;
    static final int G_YEARMONTH = 5;

    static final int[] ALL_GOALS = {G_ZONED, G_DATETIME, G_INSTANT, G_TIME, G_MONTHDAY, G_YEARMONTH};

    private TemporalParser() {
    }

    private static final class Cur {
        final String s;
        int i = 0;
        int nextCheck = INTERRUPT_STRIDE;

        Cur(String s) {
            this.s = s;
        }

        /** Keeps long scans over huge inputs interruptible. */
        void tick() {
            if (i >= nextCheck) {
                nextCheck = i + INTERRUPT_STRIDE;
                Agent agent = Agent.current.get();
                if (agent != null) agent.checkInterrupt();
            }
        }

        boolean eof() {
            return i >= s.length();
        }

        char peek() {
            return peek(0);
        }

        char peek(int off) {
            return i + off < s.length() ? s.charAt(i + off) : '\u0000';
        }

        boolean isDigit(int off) {
            char c = peek(off);
            return c >= '0' && c <= '9';
        }

        int dig(int off) {
            return peek(off) - '0';
        }
    }

    // ------------------------------------------------------------------ public entry points

    /** ParseISODateTime(isoString, allowedFormats) */
    static IsoParse parseIsoDateTime(String s, int[] goals) {
        IsoParse result = parseImpl(s, goals, true);
        if (result == null) throw TemporalAbstractOps.tRangeErr("invalid ISO 8601 string: " + take(s, 60));
        return result;
    }

    /** ParseISODateTime returning null instead of throwing. */
    static IsoParse tryParseIsoDateTime(String s, int[] goals) {
        return parseImpl(s, goals, false);
    }

    private static IsoParse parseImpl(String s, int[] goals, boolean throwing) {
        for (int goal : goals) {
            IsoParse r = tryGoal(s, goal);
            if (r == null) continue;
            String err = processAnnotations(r);
            if (err == null) {
                if (goal == G_YEARMONTH && !r.hasDay && !isoOrAbsent(r.calendar)) {
                    err = "calendar annotation not allowed";
                } else if (goal == G_MONTHDAY && r.yearAbsent && !isoOrAbsent(r.calendar)) {
                    err = "calendar annotation not allowed";
                }
            }
            if (err != null) {
                if (throwing) throw TemporalAbstractOps.tRangeErr(err);
                return null;
            }
            return r;
        }
        return null;
    }

    private static boolean isoOrAbsent(String c) {
        return c == null || TemporalAbstractOps.asciiEqualsIgnoreCase(c, "iso8601");
    }

    static IsoParse parseIsoDateTime(String s, int goal) {
        return parseIsoDateTime(s, new int[]{goal});
    }

    /**
     * ParseTimeZoneIdentifier: returns null when the identifier is syntactically invalid. The result is either a name
     * (String) or offset minutes (Integer).
     */
    static Object parseTimeZoneIdentifier(String s) {
        Cur c = new Cur(s);
        Object r = parseTzIdentifier(c);
        if (r == null) return null;
        if (!c.eof()) return null;
        return r;
    }

    /** ParseDateTimeUTCOffset: offset in nanoseconds, or null if {@code s} is not a UTCOffset[+SubMinutePrecision]. */
    static Long parseUtcOffset(String s) {
        Cur c = new Cur(s);
        Long r = parseOffset(c, true, null);
        if (r == null) return null;
        if (!c.eof()) return null;
        return r;
    }

    /** True if {@code s} matches AnnotationValue (used for bare calendar ids). */
    static boolean isAnnotationValue(String s) {
        if (s.isEmpty()) return false;
        int compLen = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '-') {
                if (compLen == 0) return false;
                compLen = 0;
            } else if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) {
                compLen++;
            } else {
                return false;
            }
        }
        return compLen > 0;
    }

    // ------------------------------------------------------------------ annotations

    /** The first annotation rule violation found while parsing, or null. */
    private static String processAnnotations(IsoParse r) {
        return r.annotationError;
    }

    /** Applies the annotation rules of ParseISODateTime to one annotation. */
    private static void applyAnnotation(IsoParse r, String s, boolean critical, int keyStart, int keyEnd,
                                        int valueStart, int valueEnd) {
        if (r.annotationError != null) return;
        if (keyEnd - keyStart == 4 && s.regionMatches(keyStart, "u-ca", 0, 4)) {
            if (!r.haveCalendar) {
                r.haveCalendar = true;
                r.calendar = s.substring(valueStart, valueEnd);
                if (critical) r.calendarCritical = true;
            } else if (critical || r.calendarCritical) {
                r.annotationError = "multiple calendar annotations with critical flag";
            }
        } else if (critical) {
            r.annotationError = "unknown critical annotation";
        }
    }

    // ------------------------------------------------------------------ goals

    private static IsoParse tryGoal(String s, int goal) {
        switch (goal) {
            case G_DATETIME:
                return annotatedDateTime(s, false, false);
            case G_ZONED:
                return annotatedDateTime(s, true, false);
            case G_INSTANT:
                return instant(s);
            case G_TIME: {
                IsoParse r = annotatedTime(s);
                return r != null ? r : annotatedDateTime(s, false, true);
            }
            case G_MONTHDAY: {
                IsoParse r = annotatedMonthDay(s);
                return r != null ? r : annotatedDateTime(s, false, false);
            }
            case G_YEARMONTH: {
                IsoParse r = annotatedYearMonth(s);
                return r != null ? r : annotatedDateTime(s, false, false);
            }
            default:
                return null;
        }
    }

    private static IsoParse annotatedDateTime(String s, boolean zoned, boolean timeRequired) {
        Cur c = new Cur(s);
        IsoParse r = new IsoParse();
        if (!parseDate(c, r)) return null;
        char sep = c.peek();
        if (sep == 'T' || sep == 't' || sep == ' ') {
            c.i++;
            if (!parseTime(c, r)) return null;
            if (!parseDateTimeOffset(c, r, zoned)) return null;
        } else if (timeRequired) {
            return null;
        }
        if (!parseAnnotations(c, r, zoned)) return null;
        return r;
    }

    private static IsoParse instant(String s) {
        Cur c = new Cur(s);
        IsoParse r = new IsoParse();
        if (!parseDate(c, r)) return null;
        char sep = c.peek();
        if (sep != 'T' && sep != 't' && sep != ' ') return null;
        c.i++;
        if (!parseTime(c, r)) return null;
        if (!parseDateTimeOffset(c, r, true)) return null;
        if (!r.z && r.offset == null) return null;
        if (!parseAnnotations(c, r, false)) return null;
        return r;
    }

    private static IsoParse annotatedTime(String s) {
        Cur c = new Cur(s);
        IsoParse r = new IsoParse();
        boolean designator = c.peek() == 'T' || c.peek() == 't';
        if (designator) c.i++;
        if (!parseTime(c, r)) return null;
        if (!parseDateTimeOffset(c, r, false)) return null;
        int endOfTime = c.i;
        if (!parseAnnotations(c, r, false)) return null;
        if (!designator) {
            String text = s.substring(0, endOfTime);
            if (isDateSpecMonthDay(text) || isDateSpecYearMonth(text)) return null;
        }
        r.yearAbsent = false;
        r.year = 1970;
        return r;
    }

    private static IsoParse annotatedMonthDay(String s) {
        Cur c = new Cur(s);
        IsoParse r = new IsoParse();
        if (!parseMonthDaySpec(c, r)) return null;
        if (!parseAnnotations(c, r, false)) return null;
        r.yearAbsent = true;
        r.year = 1972;
        return r;
    }

    private static IsoParse annotatedYearMonth(String s) {
        Cur c = new Cur(s);
        IsoParse r = new IsoParse();
        if (!parseYearMonthSpec(c, r)) return null;
        if (!parseAnnotations(c, r, false)) return null;
        r.hasDay = false;
        r.day = 1;
        return r;
    }

    private static boolean isDateSpecMonthDay(String text) {
        Cur c = new Cur(text);
        return parseMonthDaySpec(c, new IsoParse()) && c.eof();
    }

    private static boolean isDateSpecYearMonth(String text) {
        Cur c = new Cur(text);
        return parseYearMonthSpec(c, new IsoParse()) && c.eof();
    }

    // ------------------------------------------------------------------ date parts

    private static boolean parseYear(Cur c, IsoParse r) {
        char ch = c.peek();
        if (ch == '+' || ch == '-') {
            for (int k = 1; k <= 6; k++) if (!c.isDigit(k)) return false;
            int v = 0;
            for (int k = 1; k <= 6; k++) v = v * 10 + c.dig(k);
            if (ch == '-' && v == 0) return false;
            r.year = ch == '-' ? -v : v;
            c.i += 7;
            return true;
        }
        for (int k = 0; k <= 3; k++) if (!c.isDigit(k)) return false;
        r.year = c.dig(0) * 1000 + c.dig(1) * 100 + c.dig(2) * 10 + c.dig(3);
        c.i += 4;
        return true;
    }

    private static int parseMonth(Cur c) {
        if (!c.isDigit(0) || !c.isDigit(1)) return -1;
        int m = c.dig(0) * 10 + c.dig(1);
        if (m < 1 || m > 12) return -1;
        c.i += 2;
        return m;
    }

    private static int parseDay(Cur c) {
        if (!c.isDigit(0) || !c.isDigit(1)) return -1;
        int d = c.dig(0) * 10 + c.dig(1);
        if (d < 1 || d > 31) return -1;
        c.i += 2;
        return d;
    }

    private static boolean validMonthDay(int m, int d) {
        if (d == 31 && (m == 2 || m == 4 || m == 6 || m == 9 || m == 11)) return false;
        if (m == 2 && d == 30) return false;
        return true;
    }

    /** {@code DateSpec[Extended]} with IsValidDate. */
    private static boolean parseDate(Cur c, IsoParse r) {
        int start = c.i;
        if (!parseYear(c, r)) {
            c.i = start;
            return false;
        }
        boolean extended = c.peek() == '-';
        if (extended) c.i++;
        int m = parseMonth(c);
        if (m < 0) {
            c.i = start;
            return false;
        }
        if (extended) {
            if (c.peek() != '-') {
                c.i = start;
                return false;
            }
            c.i++;
        }
        int d = parseDay(c);
        if (d < 0) {
            c.i = start;
            return false;
        }
        if (!validMonthDay(m, d)) {
            c.i = start;
            return false;
        }
        if (m == 2 && d == 29 && !TM.isLeap(r.year)) {
            c.i = start;
            return false;
        }
        r.month = m;
        r.day = d;
        return true;
    }

    private static boolean parseMonthDaySpec(Cur c, IsoParse r) {
        int start = c.i;
        if (c.peek() == '-' && c.peek(1) == '-') c.i += 2;
        int m = parseMonth(c);
        if (m < 0) {
            c.i = start;
            return false;
        }
        if (c.peek() == '-') c.i++;
        int d = parseDay(c);
        if (d < 0 || !validMonthDay(m, d)) {
            c.i = start;
            return false;
        }
        r.month = m;
        r.day = d;
        return true;
    }

    private static boolean parseYearMonthSpec(Cur c, IsoParse r) {
        int start = c.i;
        if (!parseYear(c, r)) {
            c.i = start;
            return false;
        }
        if (c.peek() == '-') c.i++;
        int m = parseMonth(c);
        if (m < 0) {
            c.i = start;
            return false;
        }
        r.month = m;
        return true;
    }

    // ------------------------------------------------------------------ time parts

    private static int parseHour(Cur c) {
        if (!c.isDigit(0) || !c.isDigit(1)) return -1;
        int h = c.dig(0) * 10 + c.dig(1);
        if (h > 23) return -1;
        c.i += 2;
        return h;
    }

    private static int parseMinuteSecond(Cur c) {
        if (!c.isDigit(0) || !c.isDigit(1)) return -1;
        int v = c.dig(0) * 10 + c.dig(1);
        if (v > 59) return -1;
        c.i += 2;
        return v;
    }

    /** TemporalDecimalFraction: returns the nanoseconds (0..999999999) or -1 if absent/invalid; {@code c} advanced. */
    private static int parseFraction(Cur c) {
        char sep = c.peek();
        if (sep != '.' && sep != ',') return -1;
        int k = 1;
        int v = 0;
        while (c.isDigit(k)) {
            if (k > 9) return -2;
            v = v * 10 + c.dig(k);
            k++;
        }
        int n = k - 1;
        if (n == 0) return -2;
        for (int i = 0; i < 9 - n; i++) v *= 10;
        c.i += k;
        return v;
    }

    /** TimeSpec */
    private static boolean parseTime(Cur c, IsoParse r) {
        int start = c.i;
        int h = parseHour(c);
        if (h < 0) {
            c.i = start;
            return false;
        }
        r.hour = h;
        r.hasTime = true;
        r.minute = 0;
        r.second = 0;
        r.millisecond = 0;
        r.microsecond = 0;
        r.nanosecond = 0;
        boolean extended = c.peek() == ':';
        if (extended) {
            c.i++;
            int mi = parseMinuteSecond(c);
            if (mi < 0) {
                c.i = start;
                return false;
            }
            r.minute = mi;
            if (c.peek() == ':') {
                c.i++;
                if (!parseTimeSecond(c, r)) {
                    c.i = start;
                    return false;
                }
            }
        } else if (c.isDigit(0) && c.isDigit(1)) {
            int mi = parseMinuteSecond(c);
            if (mi < 0) {
                c.i = start;
                return false;
            }
            r.minute = mi;
            if (c.isDigit(0) && c.isDigit(1)) {
                if (!parseTimeSecond(c, r)) {
                    c.i = start;
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean parseTimeSecond(Cur c, IsoParse r) {
        if (!c.isDigit(0) || !c.isDigit(1)) return false;
        int v = c.dig(0) * 10 + c.dig(1);
        if (v > 60) return false;
        c.i += 2;
        r.second = v == 60 ? 59 : v;
        int f = parseFraction(c);
        if (f == -2) return false;
        if (f >= 0) {
            r.millisecond = f / 1_000_000;
            r.microsecond = (f / 1000) % 1000;
            r.nanosecond = f % 1000;
        }
        return true;
    }

    // ------------------------------------------------------------------ offsets

    /**
     * {@code UTCOffset[SubMinutePrecision]}: returns offset nanoseconds or null, advancing {@code c} past the match.
     * {@code hadSecondsOut} (if given) receives whether a seconds component was present.
     */
    private static Long parseOffset(Cur c, boolean subMinute, boolean[] hadSecondsOut) {
        int start = c.i;
        char sign = c.peek();
        if (sign != '+' && sign != '-') return null;
        c.i++;
        int h = parseHour(c);
        if (h < 0) {
            c.i = start;
            return null;
        }
        int minutes = 0;
        int seconds = 0;
        int nanos = 0;
        boolean hadSeconds = false;
        if (c.peek() == ':') {
            c.i++;
            int m = parseMinuteSecond(c);
            if (m < 0) {
                c.i = start;
                return null;
            }
            minutes = m;
            if (subMinute && c.peek() == ':') {
                int save = c.i;
                c.i++;
                int sec = parseMinuteSecond(c);
                if (sec < 0) {
                    c.i = save;
                } else {
                    seconds = sec;
                    hadSeconds = true;
                    int f = parseFraction(c);
                    if (f == -2) {
                        c.i = start;
                        return null;
                    }
                    if (f >= 0) nanos = f;
                }
            }
        } else if (c.isDigit(0) && c.isDigit(1)) {
            int m = parseMinuteSecond(c);
            if (m < 0) {
                c.i = start;
                return null;
            }
            minutes = m;
            if (subMinute && c.isDigit(0) && c.isDigit(1)) {
                int sec = parseMinuteSecond(c);
                if (sec < 0) {
                    c.i = start;
                    return null;
                }
                seconds = sec;
                hadSeconds = true;
                int f = parseFraction(c);
                if (f == -2) {
                    c.i = start;
                    return null;
                }
                if (f >= 0) nanos = f;
            }
        }
        if (hadSecondsOut != null) hadSecondsOut[0] = hadSeconds;
        long total = ((h * 60L + minutes) * 60L + seconds) * 1_000_000_000L + nanos;
        return sign == '-' ? -total : total;
    }

    /** {@code DateTimeUTCOffset[Z]?} (optional); returns false on a malformed offset. */
    private static boolean parseDateTimeOffset(Cur c, IsoParse r, boolean allowZ) {
        char ch = c.peek();
        if (ch == 'Z' || ch == 'z') {
            if (!allowZ) return false;
            c.i++;
            r.z = true;
            return true;
        }
        if (ch == '+' || ch == '-') {
            int start = c.i;
            boolean[] h = new boolean[1];
            if (parseOffset(c, true, h) == null) return false;
            r.offset = c.s.substring(start, c.i);
            r.offsetHasSeconds = h[0];
        }
        return true;
    }

    // ------------------------------------------------------------------ time zone identifiers and annotations

    /** TimeZoneIdentifier: returns offset minutes (Integer) or the IANA-like name (String); null on failure. */
    private static Object parseTzIdentifier(Cur c) {
        int start = c.i;
        char ch = c.peek();
        if (ch == '+' || ch == '-') {
            Long ns = parseOffset(c, false, null);
            if (ns == null) return null;
            return (int) (ns / 60_000_000_000L);
        }
        // TimeZoneIANAName
        while (true) {
            char l = c.peek();
            if (!((l >= 'a' && l <= 'z') || (l >= 'A' && l <= 'Z') || l == '.' || l == '_')) {
                c.i = start;
                return null;
            }
            c.i++;
            while (true) {
                char t = c.peek();
                if ((t >= 'a' && t <= 'z') || (t >= 'A' && t <= 'Z') || t == '.' || t == '_'
                        || (t >= '0' && t <= '9') || t == '-' || t == '+') {
                    c.i++;
                } else {
                    break;
                }
                c.tick();
            }
            c.tick();
            if (c.peek() == '/') {
                c.i++;
                continue;
            }
            break;
        }
        return c.s.substring(start, c.i);
    }

    private static boolean parseTzAnnotation(Cur c, IsoParse r) {
        int start = c.i;
        if (c.peek() != '[') return false;
        c.i++;
        if (c.peek() == '!') c.i++;
        int idStart = c.i;
        if (parseTzIdentifier(c) == null || c.peek() != ']') {
            c.i = start;
            return false;
        }
        r.tzAnnotation = c.s.substring(idStart, c.i);
        c.i++;
        return true;
    }

    private static boolean parseAnnotation(Cur c, IsoParse r) {
        int start = c.i;
        if (c.peek() != '[') return false;
        c.i++;
        boolean critical = false;
        if (c.peek() == '!') {
            critical = true;
            c.i++;
        }
        int keyStart = c.i;
        char k0 = c.peek();
        if (!((k0 >= 'a' && k0 <= 'z') || k0 == '_')) {
            c.i = start;
            return false;
        }
        c.i++;
        while (true) {
            char k = c.peek();
            if ((k >= 'a' && k <= 'z') || k == '_' || (k >= '0' && k <= '9') || k == '-') {
                c.i++;
            } else {
                break;
            }
            c.tick();
        }
        int keyEnd = c.i;
        if (c.peek() != '=') {
            c.i = start;
            return false;
        }
        c.i++;
        int valueStart = c.i;
        int compLen = 0;
        while (true) {
            char v = c.peek();
            if ((v >= 'a' && v <= 'z') || (v >= 'A' && v <= 'Z') || (v >= '0' && v <= '9')) {
                compLen++;
                c.i++;
            } else if (v == '-' && compLen > 0) {
                compLen = 0;
                c.i++;
            } else {
                break;
            }
            c.tick();
        }
        if (compLen == 0 || c.peek() != ']') {
            c.i = start;
            return false;
        }
        applyAnnotation(r, c.s, critical, keyStart, keyEnd, valueStart, c.i);
        c.i++;
        return true;
    }

    /** TimeZoneAnnotation? Annotations? followed by end of input; {@code tzRequired} for the Zoned form. */
    private static boolean parseAnnotations(Cur c, IsoParse r, boolean tzRequired) {
        if (c.peek() == '[') parseTzAnnotation(c, r);
        if (tzRequired && r.tzAnnotation == null) return false;
        while (c.peek() == '[') {
            if (!parseAnnotation(c, r)) return false;
            c.tick();
        }
        return c.eof();
    }

    // ------------------------------------------------------------------ durations

    /** ParseTemporalDurationString: returns the ten fields, or throws RangeError. */
    static double[] parseDuration(String s) {
        int i = 0;
        int n = s.length();
        boolean negative = false;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            negative = s.charAt(i) == '-';
            i++;
        }
        if (i >= n || (s.charAt(i) != 'P' && s.charAt(i) != 'p')) {
            throw TemporalAbstractOps.tRangeErr("invalid duration string: " + take(s, 60));
        }
        i++;
        double[] out = new double[10];
        // date part: Y M W D in order
        int dateIdx = 0; // next allowed date unit index (0 Y,1 M,2 W,3 D)
        boolean any = false;
        while (i < n && s.charAt(i) != 'T' && s.charAt(i) != 't') {
            int ds = i;
            while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            if (i == ds || i >= n) throw TemporalAbstractOps.tRangeErr("invalid duration string: " + take(s, 60));
            int unit;
            switch (s.charAt(i)) {
                case 'Y':
                case 'y':
                    unit = 0;
                    break;
                case 'M':
                case 'm':
                    unit = 1;
                    break;
                case 'W':
                case 'w':
                    unit = 2;
                    break;
                case 'D':
                case 'd':
                    unit = 3;
                    break;
                default:
                    unit = -1;
                    break;
            }
            if (unit < dateIdx) throw TemporalAbstractOps.tRangeErr("invalid duration string: " + take(s, 60));
            out[unit] = digitsToNumber(s, ds, i);
            dateIdx = unit + 1;
            any = true;
            i++;
        }
        BigInteger fracNs = null;
        int fracUnit = -1;
        if (i < n) {
            // time designator
            i++;
            int timeIdx = 0; // 0 H, 1 M, 2 S
            boolean anyTime = false;
            while (i < n) {
                if (fracUnit >= 0) throw TemporalAbstractOps.tRangeErr("invalid duration string: " + take(s, 60));
                int ds = i;
                while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                if (i == ds || i >= n) throw TemporalAbstractOps.tRangeErr("invalid duration string: " + take(s, 60));
                int digitsEnd = i;
                String fDigits = null;
                if (s.charAt(i) == '.' || s.charAt(i) == ',') {
                    int fs = i + 1;
                    i = fs;
                    while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                    int fl = i - fs;
                    if (fl < 1 || fl > 9 || i >= n) {
                        throw TemporalAbstractOps.tRangeErr("invalid duration string: " + take(s, 60));
                    }
                    fDigits = s.substring(fs, i);
                }
                int unit;
                switch (s.charAt(i)) {
                    case 'H':
                    case 'h':
                        unit = 0;
                        break;
                    case 'M':
                    case 'm':
                        unit = 1;
                        break;
                    case 'S':
                    case 's':
                        unit = 2;
                        break;
                    default:
                        unit = -1;
                        break;
                }
                if (unit < timeIdx) throw TemporalAbstractOps.tRangeErr("invalid duration string: " + take(s, 60));
                out[4 + unit] = digitsToNumber(s, ds, digitsEnd);
                if (fDigits != null) {
                    fracUnit = unit;
                    long unitNs = unit == 0 ? 3_600_000_000_000L : unit == 1 ? 60_000_000_000L : 1_000_000_000L;
                    BigInteger scale = BigInteger.ONE;
                    for (int k = 0; k < fDigits.length(); k++) scale = scale.multiply(BigInteger.TEN);
                    fracNs = new BigInteger(fDigits).multiply(BigInteger.valueOf(unitNs)).divide(scale);
                }
                timeIdx = unit + 1;
                anyTime = true;
                i++;
            }
            if (!anyTime) throw TemporalAbstractOps.tRangeErr("invalid duration string: " + take(s, 60));
            any = true;
        }
        if (!any) throw TemporalAbstractOps.tRangeErr("invalid duration string: " + take(s, 60));
        if (fracNs != null) {
            // distribute the fractional part into smaller units (exact integer nanoseconds)
            long f = fracNs.longValue();
            if (fracUnit == 0) {
                out[5] = (double) (f / 60_000_000_000L);
                f %= 60_000_000_000L;
                out[6] = (double) (f / 1_000_000_000L);
                f %= 1_000_000_000L;
            } else if (fracUnit == 1) {
                out[6] = (double) (f / 1_000_000_000L);
                f %= 1_000_000_000L;
            }
            out[7] = (double) (f / 1_000_000L);
            out[8] = (double) ((f / 1000L) % 1000L);
            out[9] = (double) (f % 1000L);
        }
        if (negative) for (int k = 0; k < 10; k++) out[k] = TNum.neg(out[k]);
        return out;
    }

    /** ToIntegerWithTruncation of a digit string. */
    private static double digitsToNumber(String s, int from0, int to) {
        int from = from0;
        while (from < to - 1 && s.charAt(from) == '0') from++;
        // more than 309 significant digits cannot be a finite Number
        if (to - from > 309) throw TemporalAbstractOps.tRangeErr("duration component too large");
        double d = NumberConv.stringToNumber(s.substring(from, to));
        if (Double.isInfinite(d) || Double.isNaN(d)) {
            throw TemporalAbstractOps.tRangeErr("duration component too large");
        }
        return d;
    }

    // ------------------------------------------------------------------ month codes

    /** MonthCode grammar: returns month number shl 1 | leap, or -1. */
    static int parseMonthCodeString(String s) {
        if (s.length() != 3 && s.length() != 4) return -1;
        if (s.charAt(0) != 'M') return -1;
        if (s.charAt(1) < '0' || s.charAt(1) > '9' || s.charAt(2) < '0' || s.charAt(2) > '9') return -1;
        int num = (s.charAt(1) - '0') * 10 + (s.charAt(2) - '0');
        boolean leap = s.length() == 4;
        if (leap && s.charAt(3) != 'L') return -1;
        if (num == 0 && !leap) return -1;
        return (num << 1) | (leap ? 1 : 0);
    }

    private static String take(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
