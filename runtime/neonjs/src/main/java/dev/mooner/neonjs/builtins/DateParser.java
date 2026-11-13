/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.NumberConv;

/** Date.parse: strict Date Time String Format plus the permissive legacy fallback. */
final class DateParser {
    private DateParser() {
    }

    /** Returns the clipped time value denoted by {@code s}, or NaN. */
    static double parse(String s) {
        Double iso = parseIso(s);
        return iso != null ? iso : new Legacy(s).parse();
    }

    private static final class IsoScanner {
        final String s;
        final int n;
        int p;

        IsoScanner(String s) {
            this.s = s;
            this.n = s.length();
        }

        int digits(int count) {
            if (p + count > n) return -1;
            int v = 0;
            for (int i = p; i < p + count; i++) {
                char c = s.charAt(i);
                if (c < '0' || c > '9') return -1;
                v = v * 10 + (c - '0');
            }
            p += count;
            return v;
        }

        boolean at(char c) {
            return p < n && s.charAt(p) == c;
        }
    }

    /** Returns null when not Date Time String Format syntax, NaN when a field is out of range. */
    private static Double parseIso(String s) {
        IsoScanner in = new IsoScanner(s);
        int year;
        if (in.at('+') || in.at('-')) {
            boolean negative = s.charAt(in.p++) == '-';
            int y = in.digits(6);
            if (y < 0) return null;
            if (negative && y == 0) return Double.NaN;
            year = negative ? -y : y;
        } else {
            year = in.digits(4);
            if (year < 0) return null;
        }
        int month = 1;
        int day = 1;
        if (in.at('-')) {
            in.p++;
            month = in.digits(2);
            if (month < 0) return null;
            if (in.at('-')) {
                in.p++;
                day = in.digits(2);
                if (day < 0) return null;
            }
        }
        int hour = 0;
        int minute = 0;
        int second = 0;
        int ms = 0;
        boolean hasTime = false;
        int offsetMinutes = 0;
        boolean hasOffset = false;
        if (in.at('T')) {
            in.p++;
            hasTime = true;
            hour = in.digits(2);
            if (hour < 0 || !in.at(':')) return null;
            in.p++;
            minute = in.digits(2);
            if (minute < 0) return null;
            if (in.at(':')) {
                in.p++;
                second = in.digits(2);
                if (second < 0) return null;
                if (in.at('.') || in.at(',')) {
                    int start = ++in.p;
                    while (in.p < in.n && s.charAt(in.p) >= '0' && s.charAt(in.p) <= '9') {
                        if (in.p - start < 3) ms = ms * 10 + (s.charAt(in.p) - '0');
                        in.p++;
                    }
                    int count = in.p - start;
                    if (count == 0) return null;
                    for (int i = 0; i < 3 - count; i++) ms *= 10;
                }
            }
            if (in.at('Z')) {
                in.p++;
                hasOffset = true;
            } else if (in.at('+') || in.at('-')) {
                boolean negative = s.charAt(in.p++) == '-';
                int hh = in.digits(2);
                if (hh < 0) return null;
                if (in.at(':')) in.p++;
                int mm = in.digits(2);
                if (mm < 0) return null;
                if (hh > 23 || mm > 59) return Double.NaN;
                offsetMinutes = negative ? -(hh * 60 + mm) : hh * 60 + mm;
                hasOffset = true;
            }
        }
        if (in.p != in.n) return null;

        if (month < 1 || month > 12 || day < 1 || day > DateTime.daysInMonth(year, month - 1)) {
            return Double.NaN;
        }
        if (hour > 24 || minute > 59 || second > 59
                || (hour == 24 && (minute != 0 || second != 0 || ms != 0))) {
            return Double.NaN;
        }
        double date = DateTime.makeDate(
                DateTime.makeDay(year, month - 1, day),
                DateTime.makeTime(hour, minute, second, ms));
        double tv;
        if (hasOffset) tv = date - offsetMinutes * DateTime.MS_PER_MINUTE;
        else if (hasTime) tv = DateTime.utc(date);
        else tv = date;
        return DateTime.timeClip(tv);
    }

    private record Abbreviation(String name, int minutes) {
    }

    private static final Abbreviation[] TZ_ABBREVIATIONS = {
            new Abbreviation("GMT", 0), new Abbreviation("UTC", 0), new Abbreviation("UT", 0),
            new Abbreviation("Z", 0), new Abbreviation("EDT", -4 * 60), new Abbreviation("EST", -5 * 60),
            new Abbreviation("CDT", -5 * 60), new Abbreviation("CST", -6 * 60),
            new Abbreviation("MDT", -6 * 60), new Abbreviation("MST", -7 * 60),
            new Abbreviation("PDT", -7 * 60), new Abbreviation("PST", -8 * 60),
            new Abbreviation("WEST", 60), new Abbreviation("WET", 0), new Abbreviation("CEST", 120),
            new Abbreviation("CET", 60), new Abbreviation("EEST", 180), new Abbreviation("EET", 120),
    };

    private static final char END = '\u0000';

    private static final class Legacy {
        private final String s;
        private int p = 0;
        private int year = 2001;
        private int month = 1;
        private int day = 1;
        private int hour = 0;
        private int minute = 0;
        private int second = 0;
        private int ms = 0;
        private int offsetMinutes = 0;
        private boolean isLocal = true;
        private boolean hasYear = false;
        private boolean hasMonth = false;
        private boolean hasTime = false;
        private final int[] nums = new int[3];
        private int numCount = 0;

        Legacy(String src) {
            StringBuilder b = new StringBuilder(src.length());
            for (int i = 0; i < src.length(); i++) {
                char c = src.charAt(i);
                if (c == '\u2028' || c == '\u2029') {
                    b.append('x');
                } else if (NumberConv.isJSWhitespace(c)) {
                    b.append(' ');
                } else if (c == '\u2212') {
                    b.append('-');
                } else if (c == END || c > '\u00ff') {
                    b.append('x');
                } else {
                    b.append(c);
                }
            }
            this.s = b.toString();
        }

        private char ch() {
            return p < s.length() ? s.charAt(p) : END;
        }

        private boolean skipChar(char c) {
            if (ch() != c) return false;
            p++;
            return true;
        }

        private char skipSpaces() {
            while (ch() == ' ') p++;
            return ch();
        }

        private void skipSeparators() {
            while (ch() == '-' || ch() == '/' || ch() == '.' || ch() == ',') p++;
        }

        private void skipUntil(String stop) {
            while (ch() != END && stop.indexOf(ch()) < 0) p++;
        }

        /** Reads min..max digits (max 0: unbounded, at most 9); null without consuming on failure. */
        private Integer digits(int min, int max) {
            int start = p;
            int q = p;
            int v = 0;
            while (q < s.length() && s.charAt(q) >= '0' && s.charAt(q) <= '9') {
                if (q - start == 9) return null;
                v = v * 10 + (s.charAt(q) - '0');
                q++;
                if (max != 0 && q - start == max) break;
            }
            if (q - start < min) return null;
            p = q;
            return v;
        }

        private void fraction() {
            if (ch() != '.' && ch() != ',') return;
            int q = p + 1;
            int mul = 100;
            int v = 0;
            while (q < s.length() && s.charAt(q) >= '0' && s.charAt(q) <= '9') {
                v += (s.charAt(q) - '0') * mul;
                mul /= 10;
                q++;
            }
            if (q > p + 1) {
                ms = v;
                p = q;
            }
        }

        private boolean match(String word) {
            if (!s.regionMatches(true, p, word, 0, word.length())) return false;
            p += word.length();
            return true;
        }

        private boolean offset() {
            int start = p;
            boolean negative = s.charAt(p++) == '-';
            int digitStart = p;
            Integer hhBox = digits(1, 0);
            if (hhBox == null) {
                p = start;
                return false;
            }
            int hh = hhBox;
            int count = p - digitStart;
            while (count > 4) {
                count -= 2;
                hh /= 100;
            }
            int mm = 0;
            if (count > 2) {
                mm = hh % 100;
                hh /= 100;
            } else if (skipChar(':')) {
                Integer mmBox = digits(2, 2);
                if (mmBox == null) {
                    p = start;
                    return false;
                }
                mm = mmBox;
            }
            if (hh > 23 || mm > 59) {
                p = start;
                return false;
            }
            offsetMinutes = negative ? -(hh * 60 + mm) : hh * 60 + mm;
            return true;
        }

        private boolean monthName() {
            String[] names = DateTime.MONTH_NAMES;
            for (int i = 0; i < names.length; i++) {
                if (s.regionMatches(true, p, names[i], 0, 3)) {
                    month = i + 1;
                    p += 3;
                    return true;
                }
            }
            return false;
        }

        private boolean zoneAbbreviation() {
            for (Abbreviation abbreviation : TZ_ABBREVIATIONS) {
                if (match(abbreviation.name())) {
                    offsetMinutes = abbreviation.minutes();
                    return true;
                }
            }
            return false;
        }

        private static int twoDigitYear(int v) {
            return v + (v < 100 ? 1900 : 0) + (v < 50 ? 100 : 0);
        }

        private boolean token(char c) {
            int start = p;
            if (c == '+' || c == '-') {
                if (hasTime && offset()) {
                    isLocal = false;
                } else {
                    p++;
                    Integer vBox = digits(1, 0);
                    if (vBox != null) {
                        int v = vBox;
                        if (c == '-' && v == 0) return false;
                        year = c == '-' ? -v : v;
                        hasYear = true;
                    }
                }
            } else if (c >= '0' && c <= '9') {
                Integer vBox = digits(1, 0);
                if (vBox == null) return false;
                int v = vBox;
                if (skipChar(':')) {
                    hour = v;
                    Integer minuteBox = digits(1, 2);
                    if (minuteBox == null) return false;
                    minute = minuteBox;
                    if (skipChar(':')) {
                        Integer secondBox = digits(1, 2);
                        if (secondBox == null) return false;
                        second = secondBox;
                        fraction();
                    } else if (ch() != END && ch() != ' ') {
                        return false;
                    }
                    hasTime = true;
                } else if (p - start > 2) {
                    year = v;
                    hasYear = true;
                } else if (v < 1 || v > 31) {
                    year = twoDigitYear(v);
                    hasYear = true;
                } else {
                    if (numCount == 3) return false;
                    nums[numCount++] = v;
                }
            } else if (monthName()) {
                hasMonth = true;
                skipUntil("0123456789 -/(");
            } else if (hasTime && match("PM")) {
                if (hour != 12) hour += 12;
                return true;
            } else if (hasTime && match("AM")) {
                if (hour > 12) return false;
                if (hour == 12) hour = 0;
                return true;
            } else if (zoneAbbreviation()) {
                isLocal = false;
                return true;
            } else if (c == '(') {
                int level = 0;
                while (ch() != END) {
                    char d = s.charAt(p++);
                    if (d == '(') level++;
                    else if (d == ')') level--;
                    if (level == 0) break;
                }
                if (level > 0) return false;
            } else if (c == ')') {
                return false;
            } else {
                if (hasYear || hasMonth || hasTime || numCount > 0) return false;
                skipUntil(" -/(");
            }
            skipSeparators();
            return true;
        }

        double parse() {
            while (true) {
                char c = skipSpaces();
                if (c == END) break;
                if (!token(c)) return Double.NaN;
            }
            if (numCount + (hasYear ? 1 : 0) + (hasMonth ? 1 : 0) > 3) return Double.NaN;
            switch (numCount) {
                case 0:
                    if (!hasYear) return Double.NaN;
                    break;
                case 1:
                    if (hasMonth) day = nums[0];
                    else month = nums[0];
                    break;
                case 2:
                    if (hasYear) {
                        month = nums[0];
                        day = nums[1];
                    } else if (hasMonth) {
                        year = twoDigitYear(nums[1]);
                        day = nums[0];
                    } else {
                        month = nums[0];
                        day = nums[1];
                    }
                    break;
                default:
                    year = twoDigitYear(nums[2]);
                    month = nums[0];
                    day = nums[1];
                    break;
            }
            if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 24 || minute > 59 || second > 59) {
                return Double.NaN;
            }
            if (hour == 24 && (minute != 0 || second != 0 || ms != 0)) return Double.NaN;
            double tv = DateTime.makeDate(
                    DateTime.makeDay(year, month - 1, day),
                    DateTime.makeTime(hour, minute, second, ms));
            if (isLocal) tv = DateTime.utc(tv);
            return DateTime.timeClip(tv - offsetMinutes * DateTime.MS_PER_MINUTE);
        }
    }
}
