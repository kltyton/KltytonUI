/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Agent;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.concurrent.LazyValue;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Time value arithmetic of ECMA-262 §21.4.1, the host local time zone and the string forms produced by
 * Date.prototype methods.
 */
public final class DateTime {
    public static final double MS_PER_SECOND = 1000.0;
    public static final double MS_PER_MINUTE = 60_000.0;
    public static final double MS_PER_HOUR = 3_600_000.0;
    public static final double MS_PER_DAY = 86_400_000.0;

    /** Largest magnitude of a time value: 100,000,000 days either side of the epoch. */
    public static final double MAX_TIME = 8.64e15;

    private static final double MAX_YEAR = 1_000_000.0;
    private static final double MAX_DAYS = 1e11;
    private static final double ZONE_LOOKUP_LIMIT = 1e16;

    public static final String INVALID_DATE = "Invalid Date";

    private static final String[] WEEKDAY_NAMES = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    static final String[] MONTH_NAMES = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private static final LazyValue<ZoneId> HOST_ZONE = new LazyValue<>(() -> {
        String configured = System.getProperty("neonjs.timezone");
        if (configured != null) {
            try {
                return ZoneId.of(configured);
            } catch (RuntimeException ignored) {
                // invalid property: use the JVM default
            }
        }
        return ZoneId.systemDefault();
    });

    private static final ConcurrentHashMap<ZoneId, String[]> ZONE_NAME_CACHE = new ConcurrentHashMap<>();
    public static final DateTime INSTANCE = new DateTime();

    private DateTime() {
    }

    public static DateTime instance() {
        return INSTANCE;
    }

    /** Current time as a time value. */
    public static double now() {
        Agent agent = Agent.current.get();
        return agent != null ? agent.currentTimeMillis() : (double) System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ decomposition of time values

    public static double day(double t) {
        return Math.floor(t / MS_PER_DAY);
    }

    public static double timeWithinDay(double t) {
        return posMod(t, MS_PER_DAY);
    }

    public static double weekDay(double t) {
        return posMod(day(t) + 4, 7.0);
    }

    public static double yearFromTime(double t) {
        return civilYear(civilOf(t));
    }

    public static double monthFromTime(double t) {
        return civilMonth(civilOf(t));
    }

    public static double dateFromTime(double t) {
        return civilDate(civilOf(t));
    }

    public static double hourFromTime(double t) {
        return Math.floor(timeWithinDay(t) / MS_PER_HOUR);
    }

    public static double minFromTime(double t) {
        return Math.floor(timeWithinDay(t) / MS_PER_MINUTE) % 60;
    }

    public static double secFromTime(double t) {
        return Math.floor(timeWithinDay(t) / MS_PER_SECOND) % 60;
    }

    public static double msFromTime(double t) {
        return timeWithinDay(t) % MS_PER_SECOND;
    }

    private static double posMod(double a, double n) {
        double r = a % n;
        return r < 0 ? r + n : r + 0.0;
    }

    public static boolean isLeapYear(long year) {
        return year % 4 == 0L && (year % 100 != 0L || year % 400 == 0L);
    }

    public static int daysInMonth(long year, int month) {
        switch (month) {
            case 1:
                return isLeapYear(year) ? 29 : 28;
            case 3:
            case 5:
            case 8:
            case 10:
                return 30;
            default:
                return 31;
        }
    }

    private static long daysFromCivil(long year, int month, int date) {
        long y = month < 2 ? year - 1 : year;
        long era = Math.floorDiv(y, 400L);
        long yoe = y - era * 400;
        int mp = (month + 10) % 12;
        long doy = (153L * mp + 2) / 5 + date - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    private static long civil(long days) {
        long z = days + 719468;
        long era = Math.floorDiv(z, 146097L);
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long date = doy - (153 * mp + 2) / 5 + 1;
        long month = mp < 10 ? mp + 2 : mp - 10;
        long year = yoe + era * 400 + (month < 2 ? 1 : 0);
        return (year << 9) | (month << 5) | date;
    }

    private static long civilOf(double t) {
        long days = (long) Math.max(-MAX_DAYS, Math.min(MAX_DAYS, day(t)));
        return civil(days);
    }

    private static int civilYear(long c) {
        return (int) (c >> 9);
    }

    private static int civilMonth(long c) {
        return (int) ((c >> 5) & 15);
    }

    private static int civilDate(long c) {
        return (int) (c & 31);
    }

    // ------------------------------------------------------------------ composition

    public static double makeTime(double hour, double min, double sec, double ms) {
        if (!Double.isFinite(hour) || !Double.isFinite(min) || !Double.isFinite(sec) || !Double.isFinite(ms)) {
            return Double.NaN;
        }
        double h = Ops.integerPart(hour);
        double m = Ops.integerPart(min);
        double s = Ops.integerPart(sec);
        double milli = Ops.integerPart(ms);
        return ((h * MS_PER_HOUR + m * MS_PER_MINUTE) + s * MS_PER_SECOND) + milli;
    }

    public static double makeDay(double year, double month, double date) {
        if (!Double.isFinite(year) || !Double.isFinite(month) || !Double.isFinite(date)) {
            return Double.NaN;
        }
        double y = Ops.integerPart(year);
        double m = Ops.integerPart(month);
        double dt = Ops.integerPart(date);
        double ym = y + Math.floor(m / 12);
        if (!Double.isFinite(ym) || Math.abs(ym) > MAX_YEAR) {
            return Double.NaN;
        }
        int mn = (int) posMod(m, 12.0);
        return (double) daysFromCivil((long) ym, mn, 1) + dt - 1;
    }

    public static double makeDate(double day, double time) {
        if (!Double.isFinite(day) || !Double.isFinite(time)) {
            return Double.NaN;
        }
        double tv = day * MS_PER_DAY + time;
        return Double.isFinite(tv) ? tv : Double.NaN;
    }

    public static double makeFullYear(double year) {
        if (Double.isNaN(year)) {
            return Double.NaN;
        }
        double truncated = Ops.integerPart(year);
        return truncated >= 0.0 && truncated <= 99.0 ? 1900 + truncated : truncated;
    }

    public static double timeClip(double time) {
        return !Double.isFinite(time) || Math.abs(time) > MAX_TIME ? Double.NaN : Ops.integerPart(time);
    }

    // ------------------------------------------------------------------ local time zone

    /** The host time zone: system property {@code neonjs.timezone} when valid, else the JVM default. */
    public static ZoneId hostZone() {
        return HOST_ZONE.get();
    }

    public static ZoneId getHostZone() {
        return hostZone();
    }

    /** The local time zone of the running context, else {@link #hostZone()}. */
    public static ZoneId zone() {
        Agent agent = Agent.current.get();
        ZoneId configured = agent != null ? agent.config.timeZone : null;
        return configured != null ? configured : hostZone();
    }

    public static ZoneId getZone() {
        return zone();
    }

    private static ZoneRules rules() {
        return zone().getRules();
    }

    private static String[] zoneNames() {
        return ZONE_NAME_CACHE.computeIfAbsent(zone(), z -> {
            TimeZone tz = TimeZone.getTimeZone(z);
            return new String[]{
                    tz.getDisplayName(false, TimeZone.LONG, Locale.US),
                    tz.getDisplayName(true, TimeZone.LONG, Locale.US)
            };
        });
    }

    private static long lookupMs(double t) {
        return (long) Math.max(-ZONE_LOOKUP_LIMIT, Math.min(ZONE_LOOKUP_LIMIT, t));
    }

    public static double offsetAt(double t) {
        ZoneRules r = rules();
        java.time.ZoneOffset offset = r.isFixedOffset()
                ? r.getOffset(Instant.EPOCH) : r.getOffset(Instant.ofEpochMilli(lookupMs(t)));
        return offset.getTotalSeconds() * MS_PER_SECOND;
    }

    public static double localTime(double t) {
        return Double.isFinite(t) ? t + offsetAt(t) : Double.NaN;
    }

    public static double utc(double t) {
        if (!Double.isFinite(t)) {
            return Double.NaN;
        }
        ZoneRules r = rules();
        java.time.ZoneOffset offset;
        if (r.isFixedOffset()) {
            offset = r.getOffset(Instant.EPOCH);
        } else {
            long ms = lookupMs(t);
            LocalDateTime local = LocalDateTime.ofEpochSecond(
                    Math.floorDiv(ms, 1000L), (int) Math.floorMod(ms, 1000L) * 1_000_000, ZoneOffset.UTC);
            offset = r.getOffset(local);
        }
        return t - offset.getTotalSeconds() * MS_PER_SECOND;
    }

    // ------------------------------------------------------------------ string forms

    private static String pad(int v, int width) {
        return pad((long) v, width);
    }

    private static String pad(long v, int width) {
        String text = Long.toString(v);
        if (text.length() >= width) return text;
        StringBuilder out = new StringBuilder(width);
        for (int i = text.length(); i < width; i++) out.append('0');
        return out.append(text).toString();
    }

    private static String yearString(int year) {
        return year < 0 ? "-" + pad(-(long) year, 4) : pad(year, 4);
    }

    public static String dateString(double t) {
        long c = civilOf(t);
        return WEEKDAY_NAMES[(int) weekDay(t)] + " " + MONTH_NAMES[civilMonth(c)] + " "
                + pad(civilDate(c), 2) + " " + yearString(civilYear(c));
    }

    public static String timeString(double t) {
        return pad((int) hourFromTime(t), 2) + ":" + pad((int) minFromTime(t), 2) + ":"
                + pad((int) secFromTime(t), 2) + " GMT";
    }

    public static String timeZoneString(double tv) {
        double offset = offsetAt(tv);
        double a = Math.abs(offset);
        String sign = offset >= 0 ? "+" : "-";
        ZoneRules r = rules();
        boolean dst = !r.isFixedOffset() && r.isDaylightSavings(Instant.ofEpochMilli(lookupMs(tv)));
        String[] names = zoneNames();
        return sign + pad((int) hourFromTime(a), 2) + pad((int) minFromTime(a), 2)
                + " (" + names[dst ? 1 : 0] + ")";
    }

    public static String toDateString(double tv) {
        if (Double.isNaN(tv)) return INVALID_DATE;
        double t = localTime(tv);
        return dateString(t) + " " + timeString(t) + timeZoneString(tv);
    }

    public static String toUTCString(double tv) {
        if (Double.isNaN(tv)) return INVALID_DATE;
        long c = civilOf(tv);
        return WEEKDAY_NAMES[(int) weekDay(tv)] + ", " + pad(civilDate(c), 2) + " "
                + MONTH_NAMES[civilMonth(c)] + " " + yearString(civilYear(c)) + " " + timeString(tv);
    }

    public static String toISOString(double tv) {
        long c = civilOf(tv);
        int y = civilYear(c);
        String year = y >= 0 && y <= 9999 ? pad(y, 4)
                : (y < 0 ? "-" : "+") + pad(Math.abs((long) y), 6);
        return year + "-" + pad(civilMonth(c) + 1, 2) + "-" + pad(civilDate(c), 2) + "T"
                + pad((int) hourFromTime(tv), 2) + ":" + pad((int) minFromTime(tv), 2) + ":"
                + pad((int) secFromTime(tv), 2) + "." + pad((int) msFromTime(tv), 3) + "Z";
    }

    public static String toLocaleString(double tv) {
        if (Double.isNaN(tv)) return INVALID_DATE;
        double t = localTime(tv);
        return localeDate(t) + ", " + localeTime(t);
    }

    public static String toLocaleDateString(double tv) {
        return Double.isNaN(tv) ? INVALID_DATE : localeDate(localTime(tv));
    }

    public static String toLocaleTimeString(double tv) {
        return Double.isNaN(tv) ? INVALID_DATE : localeTime(localTime(tv));
    }

    private static String localeDate(double t) {
        long c = civilOf(t);
        return (civilMonth(c) + 1) + "/" + civilDate(c) + "/" + civilYear(c);
    }

    private static String localeTime(double t) {
        int h = (int) hourFromTime(t);
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return h12 + ":" + pad((int) minFromTime(t), 2) + ":" + pad((int) secFromTime(t), 2)
                + " " + (h < 12 ? "AM" : "PM");
    }
}
