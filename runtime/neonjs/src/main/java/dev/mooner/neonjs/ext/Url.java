/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.ext;

import dev.mooner.neonjs.builtins.Builtins;
import dev.mooner.neonjs.runtime.Agent;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.unicode.Idna;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import static dev.mooner.neonjs.ext.Idl.typeErr;
import static dev.mooner.neonjs.runtime.Values.arg;

/** {@code URL} and {@code URLSearchParams} of the WHATWG URL Standard. */
final class Url {
    private Url() {
    }

    static abstract class Host {
        abstract String serialize();
    }

    static final class Domain extends Host {
        final String name;

        Domain(String name) {
            this.name = name;
        }

        @Override
        String serialize() {
            return name;
        }
    }

    static final class Ipv4 extends Host {
        final long address;

        Ipv4(long address) {
            this.address = address;
        }

        @Override
        String serialize() {
            return serializeIpv4(address);
        }
    }

    static final class Ipv6 extends Host {
        final int[] pieces;

        Ipv6(int[] pieces) {
            this.pieces = pieces;
        }

        @Override
        String serialize() {
            return "[" + serializeIpv6(pieces) + "]";
        }
    }

    static final class Opaque extends Host {
        final String value;

        Opaque(String value) {
            this.value = value;
        }

        @Override
        String serialize() {
            return value;
        }
    }

    static final class EmptyHost extends Host {
        static final EmptyHost INSTANCE = new EmptyHost();

        private EmptyHost() {
        }

        @Override
        String serialize() {
            return "";
        }
    }

    static final class Record {
        String scheme = "";
        String username = "";
        String password = "";
        Host host;
        /** The port, or -1 for none. */
        int port = -1;
        ArrayList<String> path = new ArrayList<>();
        String opaquePath;
        String query;
        String fragment;

        boolean isSpecial() {
            return SPECIAL.containsKey(scheme);
        }

        boolean includesCredentials() {
            return !username.isEmpty() || !password.isEmpty();
        }

        boolean cannotHaveCredentialsOrPort() {
            return host == null || host == EmptyHost.INSTANCE || scheme.equals("file");
        }

        Record copy() {
            Record result = new Record();
            result.scheme = scheme;
            result.username = username;
            result.password = password;
            result.host = host;
            result.port = port;
            result.path = new ArrayList<>(path);
            result.opaquePath = opaquePath;
            result.query = query;
            result.fragment = fragment;
            return result;
        }

        void assign(Record other) {
            scheme = other.scheme;
            username = other.username;
            password = other.password;
            host = other.host;
            port = other.port;
            path = other.path;
            opaquePath = other.opaquePath;
            query = other.query;
            fragment = other.fragment;
        }

        void shortenPath() {
            if (scheme.equals("file") && path.size() == 1 && isNormalizedDriveLetter(path.get(0))) return;
            if (!path.isEmpty()) path.remove(path.size() - 1);
        }

        String pathname() {
            if (opaquePath != null) return opaquePath;
            StringBuilder builder = new StringBuilder();
            for (String segment : path) builder.append('/').append(segment);
            return builder.toString();
        }

        String serialize() {
            return serialize(false);
        }

        String serialize(boolean excludeFragment) {
            StringBuilder builder = new StringBuilder(scheme).append(':');
            Host currentHost = host;
            if (currentHost != null) {
                builder.append("//");
                if (includesCredentials()) {
                    builder.append(username);
                    if (!password.isEmpty()) builder.append(':').append(password);
                    builder.append('@');
                }
                builder.append(currentHost.serialize());
                if (port >= 0) builder.append(':').append(port);
            } else if (opaquePath == null && path.size() > 1 && path.get(0).isEmpty()) {
                builder.append("/.");
            }
            builder.append(pathname());
            if (query != null) builder.append('?').append(query);
            if (!excludeFragment && fragment != null) builder.append('#').append(fragment);
            return builder.toString();
        }

        String origin() {
            switch (scheme) {
                case "blob": {
                    Record inner = parse(pathname(), null);
                    return inner != null && (inner.scheme.equals("http") || inner.scheme.equals("https"))
                            ? inner.origin() : "null";
                }
                case "ftp":
                case "http":
                case "https":
                case "ws":
                case "wss":
                    return scheme + "://" + host.serialize() + (port >= 0 ? ":" + port : "");
                default:
                    return "null";
            }
        }

        void stripTrailingSpaces() {
            if (opaquePath == null || fragment != null || query != null) return;
            int end = opaquePath.length();
            while (end > 0 && opaquePath.charAt(end - 1) == ' ') end--;
            opaquePath = opaquePath.substring(0, end);
        }
    }

    private static final Map<String, Integer> SPECIAL = Map.of(
            "ftp", 21, "file", -1, "http", 80, "https", 443, "ws", 80, "wss", 443);

    private static int defaultPort(String scheme) {
        Integer port = SPECIAL.get(scheme);
        return port == null ? -1 : port;
    }

    // ------------------------------------------------------------------ percent-encoding

    private static boolean c0Control(int c) {
        return c <= 0x1F || c > 0x7E;
    }

    private static boolean fragmentSet(int c) {
        return c0Control(c) || c == 0x20 || c == '"' || c == '<' || c == '>' || c == '`';
    }

    private static boolean querySet(int c) {
        return c0Control(c) || c == 0x20 || c == '"' || c == '#' || c == '<' || c == '>';
    }

    private static boolean specialQuerySet(int c) {
        return querySet(c) || c == '\'';
    }

    private static boolean pathSet(int c) {
        return querySet(c) || c == '?' || c == '^' || c == '`' || c == '{' || c == '}';
    }

    private static boolean userinfoSet(int c) {
        return pathSet(c) || c == '/' || c == ':' || c == ';' || c == '=' || c == '@'
                || c >= '[' && c <= '^' || c == '|';
    }

    private static boolean componentSet(int c) {
        return userinfoSet(c) || c >= '$' && c <= '&' || c == '+' || c == ',';
    }

    private static boolean formSet(int c) {
        return componentSet(c) || c == '!' || c >= '\'' && c <= ')' || c == '~';
    }

    private static final String HEX = "0123456789ABCDEF";

    private static void percentEncode(StringBuilder builder, int codePoint, IntPredicate set,
                                      boolean spaceAsPlus) {
        if (codePoint < 0x80) {
            if (spaceAsPlus && codePoint == 0x20) builder.append('+');
            else if (set.test(codePoint)) {
                builder.append('%').append(HEX.charAt(codePoint >>> 4))
                        .append(HEX.charAt(codePoint & 0xF));
            } else builder.append((char) codePoint);
            return;
        }
        byte[] bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
        for (byte raw : bytes) {
            int value = raw & 0xFF;
            builder.append('%').append(HEX.charAt(value >>> 4)).append(HEX.charAt(value & 0xF));
        }
    }

    private static String percentEncodeString(String text, IntPredicate set, boolean spaceAsPlus) {
        StringBuilder builder = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            percentEncode(builder, codePoint, set, spaceAsPlus);
        }
        return builder.toString();
    }

    private static String percentEncodeString(String text, IntPredicate set) {
        return percentEncodeString(text, set, false);
    }

    private static int hexValue(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static byte[] percentDecode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xFF;
            if (b == '%' && i + 2 < bytes.length) {
                int high = hexValue(bytes[i + 1] & 0xFF);
                int low = hexValue(bytes[i + 2] & 0xFF);
                if (high >= 0 && low >= 0) {
                    out.write(high * 16 + low);
                    i += 3;
                    continue;
                }
            }
            out.write(b);
            i++;
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ hosts

    private static boolean forbiddenHost(int c) {
        return c == 0 || c == 9 || c == 0xA || c == 0xD || c == 0x20 || c == '#'
                || c == '/' || c == ':' || c == '<' || c == '>' || c == '?' || c == '@'
                || c == '[' || c == '\\' || c == ']' || c == '^' || c == '|';
    }

    private static boolean forbiddenDomain(int c) {
        return forbiddenHost(c) || c <= 0x1F || c == '%' || c == 0x7F;
    }

    /** The host parser; null on failure. */
    static Host parseHost(String input, boolean isOpaque) {
        if (input.startsWith("[")) {
            if (!input.endsWith("]")) return null;
            int[] pieces = parseIpv6(input.substring(1, input.length() - 1));
            return pieces == null ? null : new Ipv6(pieces);
        }
        if (isOpaque) {
            for (int i = 0; i < input.length(); i++) {
                if (forbiddenHost(input.charAt(i))) return null;
            }
            return input.isEmpty() ? EmptyHost.INSTANCE
                    : new Opaque(percentEncodeString(input, Url::c0Control));
        }
        String domain = WhatwgUtf8.decode(percentDecode(input));
        String ascii = Idna.domainToAscii(domain);
        if (ascii == null) return null;
        for (int i = 0; i < ascii.length(); i++) {
            if (forbiddenDomain(ascii.charAt(i))) return null;
        }
        if (endsInNumber(ascii)) {
            Long ipv4 = parseIpv4(ascii);
            return ipv4 == null ? null : new Ipv4(ipv4);
        }
        return new Domain(ascii);
    }

    private static boolean endsInNumber(String input) {
        ArrayList<String> parts = new ArrayList<>(List.of(input.split("\\.", -1)));
        if (parts.get(parts.size() - 1).isEmpty()) {
            if (parts.size() == 1) return false;
            parts.remove(parts.size() - 1);
        }
        String last = parts.get(parts.size() - 1);
        if (!last.isEmpty()) {
            boolean digits = true;
            for (int i = 0; i < last.length(); i++) {
                if (last.charAt(i) < '0' || last.charAt(i) > '9') {
                    digits = false;
                    break;
                }
            }
            if (digits) return true;
        }
        return parseIpv4Number(last) != null;
    }

    private static Long parseIpv4Number(String original) {
        String input = original;
        if (input.isEmpty()) return null;
        int radix = 10;
        if (input.length() >= 2 && (input.startsWith("0x") || input.startsWith("0X"))) {
            input = input.substring(2);
            radix = 16;
        } else if (input.length() >= 2 && input.startsWith("0")) {
            input = input.substring(1);
            radix = 8;
        }
        if (input.isEmpty()) return 0L;
        long value = 0L;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int digit = Character.digit(c, radix);
            if (digit < 0 || c > 0x7F) return null;
            value = value > (Long.MAX_VALUE - digit) / radix ? Long.MAX_VALUE : value * radix + digit;
        }
        return value;
    }

    private static Long parseIpv4(String input) {
        ArrayList<String> parts = new ArrayList<>(List.of(input.split("\\.", -1)));
        if (parts.get(parts.size() - 1).isEmpty() && parts.size() > 1) {
            parts.remove(parts.size() - 1);
        }
        if (parts.size() > 4) return null;
        ArrayList<Long> numbers = new ArrayList<>();
        for (String part : parts) {
            Long value = parseIpv4Number(part);
            if (value == null) return null;
            numbers.add(value);
        }
        for (int k = 0; k < numbers.size() - 1; k++) if (numbers.get(k) > 255) return null;
        long last = numbers.get(numbers.size() - 1);
        if (last >= Math.pow(256.0, 5 - numbers.size())) return null;
        long ipv4 = last;
        for (int k = 0; k < numbers.size() - 1; k++) {
            ipv4 += numbers.get(k) * (long) Math.pow(256.0, 3 - k);
        }
        return ipv4;
    }

    private static String serializeIpv4(long address) {
        return ((address >> 24) & 0xFF) + "." + ((address >> 16) & 0xFF) + "."
                + ((address >> 8) & 0xFF) + "." + (address & 0xFF);
    }

    private static int ch(String input, int index) {
        return index < input.length() ? input.charAt(index) : -1;
    }

    private static int[] parseIpv6(String input) {
        int[] address = new int[8];
        int pieceIndex = 0;
        int compress = -1;
        int pointer = 0;
        int n = input.length();
        if (ch(input, pointer) == ':') {
            if (ch(input, pointer + 1) != ':') return null;
            pointer += 2;
            pieceIndex++;
            compress = pieceIndex;
        }
        while (ch(input, pointer) != -1) {
            if (pieceIndex == 8) return null;
            if (ch(input, pointer) == ':') {
                if (compress != -1) return null;
                pointer++;
                pieceIndex++;
                compress = pieceIndex;
                continue;
            }
            int value = 0;
            int length = 0;
            while (length < 4 && ch(input, pointer) != -1 && hexValue(ch(input, pointer)) >= 0) {
                value = value * 0x10 + hexValue(ch(input, pointer));
                pointer++;
                length++;
            }
            if (ch(input, pointer) == '.') {
                if (length == 0) return null;
                pointer -= length;
                if (pieceIndex > 6) return null;
                int numbersSeen = 0;
                while (ch(input, pointer) != -1) {
                    int ipv4Piece = -1;
                    if (numbersSeen > 0) {
                        if (ch(input, pointer) == '.' && numbersSeen < 4) pointer++;
                        else return null;
                    }
                    int c = ch(input, pointer);
                    if (!(c >= '0' && c <= '9')) return null;
                    while (ch(input, pointer) >= '0' && ch(input, pointer) <= '9') {
                        int number = ch(input, pointer) - '0';
                        if (ipv4Piece == -1) ipv4Piece = number;
                        else if (ipv4Piece == 0) return null;
                        else ipv4Piece = ipv4Piece * 10 + number;
                        if (ipv4Piece > 255) return null;
                        pointer++;
                    }
                    address[pieceIndex] = address[pieceIndex] * 0x100 + ipv4Piece;
                    numbersSeen++;
                    if (numbersSeen == 2 || numbersSeen == 4) pieceIndex++;
                }
                if (numbersSeen != 4) return null;
                break;
            } else if (ch(input, pointer) == ':') {
                pointer++;
                if (ch(input, pointer) == -1) return null;
            } else if (ch(input, pointer) != -1) return null;
            address[pieceIndex] = value;
            pieceIndex++;
        }
        if (compress != -1) {
            int swaps = pieceIndex - compress;
            pieceIndex = 7;
            while (pieceIndex != 0 && swaps > 0) {
                int temporary = address[pieceIndex];
                address[pieceIndex] = address[compress + swaps - 1];
                address[compress + swaps - 1] = temporary;
                pieceIndex--;
                swaps--;
            }
        } else if (pieceIndex != 8) return null;
        return address;
    }

    private static String serializeIpv6(int[] address) {
        int compress = -1;
        int best = 1;
        int i = 0;
        while (i < 8) {
            if (address[i] == 0) {
                int j = i;
                while (j < 8 && address[j] == 0) j++;
                if (j - i > best) {
                    best = j - i;
                    compress = i;
                }
                i = j;
            } else i++;
        }
        StringBuilder builder = new StringBuilder();
        boolean ignoreZero = false;
        for (int k = 0; k < 8; k++) {
            if (ignoreZero && address[k] == 0) continue;
            ignoreZero = false;
            if (compress == k) {
                builder.append(k == 0 ? "::" : ":");
                ignoreZero = true;
                continue;
            }
            builder.append(Integer.toHexString(address[k]));
            if (k != 7) builder.append(':');
        }
        return builder.toString();
    }

    enum State {
        SCHEME_START, SCHEME, NO_SCHEME, SPECIAL_RELATIVE_OR_AUTHORITY, PATH_OR_AUTHORITY, RELATIVE,
        RELATIVE_SLASH, SPECIAL_AUTHORITY_SLASHES, SPECIAL_AUTHORITY_IGNORE_SLASHES, AUTHORITY, HOST,
        HOSTNAME, PORT, FILE, FILE_SLASH, FILE_HOST, PATH_START, PATH, OPAQUE_PATH, QUERY, FRAGMENT
    }

    private static boolean isC0OrSpace(char c) {
        return c <= 0x20;
    }

    private static boolean isAsciiAlpha(int c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static boolean isWindowsDriveLetter(String text) {
        return text.length() == 2 && isAsciiAlpha(text.charAt(0))
                && (text.charAt(1) == ':' || text.charAt(1) == '|');
    }

    private static boolean isNormalizedDriveLetter(String text) {
        return text.length() == 2 && isAsciiAlpha(text.charAt(0)) && text.charAt(1) == ':';
    }

    private static boolean startsWithDriveLetter(int[] codePoints, int from) {
        if (codePoints.length - from < 2) return false;
        if (!isAsciiAlpha(codePoints[from])
                || codePoints[from + 1] != ':' && codePoints[from + 1] != '|') return false;
        if (codePoints.length - from == 2) return true;
        int c = codePoints[from + 2];
        return c == '/' || c == '\\' || c == '?' || c == '#';
    }

    private static boolean isSingleDot(String text) {
        return text.equals(".") || text.equalsIgnoreCase("%2e");
    }

    private static boolean isDoubleDot(String text) {
        return text.equals("..") || text.equalsIgnoreCase(".%2e")
                || text.equalsIgnoreCase("%2e.") || text.equalsIgnoreCase("%2e%2e");
    }

    private static String trimC0(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isC0OrSpace(text.charAt(start))) start++;
        while (end > start && isC0OrSpace(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    private static String removeTabsNewlines(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\t' && c != '\n' && c != '\r') builder.append(c);
        }
        return builder.toString();
    }

    private static boolean remainingStartsWith(int[] codePoints, int pointer, String text) {
        for (int k = 0; k < text.length(); k++) {
            int index = pointer + 1 + k;
            if (index >= codePoints.length || codePoints[index] != text.charAt(k)) return false;
        }
        return true;
    }

    /** Parses {@code input} against {@code base} (no state override); null on failure. */
    static Record parse(String input, Record base) {
        Record url = new Record();
        return basicParse(input, base, url, null) ? url : null;
    }

    /** The basic URL parser; with a state override it changes {@code url} in place. */
    static boolean basicParse(String input0, Record base, Record url, State override) {
        String input = override == null ? trimC0(input0) : input0;
        input = removeTabsNewlines(input);
        int[] codePoints = input.codePoints().toArray();
        State state = override == null ? State.SCHEME_START : override;
        StringBuilder buffer = new StringBuilder();
        StringBuilder opaque = new StringBuilder();
        StringBuilder fragment = new StringBuilder();
        Agent agent = Agent.current.get();
        int steps = 0;
        boolean atSignSeen = false;
        boolean insideBrackets = false;
        boolean passwordTokenSeen = false;
        int pointer = 0;
        int EOF = -1;

        while (true) {
            int c = pointer < codePoints.length ? codePoints[pointer] : EOF;
            switch (state) {
                case SCHEME_START:
                    if (c != EOF && isAsciiAlpha(c)) {
                        buffer.append(Character.toLowerCase((char) c));
                        state = State.SCHEME;
                    } else if (override == null) {
                        state = State.NO_SCHEME;
                        pointer--;
                    } else return false;
                    break;
                case SCHEME:
                    if (c != EOF && (isAsciiAlpha(c) || c >= '0' && c <= '9'
                            || c == '+' || c == '-' || c == '.')) {
                        buffer.append(Character.toLowerCase((char) c));
                    } else if (c == ':') {
                        String scheme = buffer.toString();
                        if (override != null) {
                            if (url.isSpecial() != SPECIAL.containsKey(scheme)) return true;
                            if ((url.includesCredentials() || url.port >= 0) && scheme.equals("file")) return true;
                            if (url.scheme.equals("file") && url.host == EmptyHost.INSTANCE) return true;
                        }
                        url.scheme = scheme;
                        if (override != null) {
                            if (url.port == defaultPort(url.scheme)) url.port = -1;
                            return true;
                        }
                        buffer.setLength(0);
                        if (url.scheme.equals("file")) state = State.FILE;
                        else if (url.isSpecial() && base != null && base.scheme.equals(url.scheme)) {
                            state = State.SPECIAL_RELATIVE_OR_AUTHORITY;
                        } else if (url.isSpecial()) {
                            state = State.SPECIAL_AUTHORITY_SLASHES;
                        } else if (remainingStartsWith(codePoints, pointer, "/")) {
                            state = State.PATH_OR_AUTHORITY;
                            pointer++;
                        } else {
                            url.opaquePath = "";
                            state = State.OPAQUE_PATH;
                        }
                    } else if (override == null) {
                        buffer.setLength(0);
                        state = State.NO_SCHEME;
                        pointer = -1;
                    } else return false;
                    break;
                case NO_SCHEME:
                    if (base == null || (base.opaquePath != null && c != '#')) return false;
                    if (base.opaquePath != null && c == '#') {
                        url.scheme = base.scheme;
                        url.opaquePath = base.opaquePath;
                        url.query = base.query;
                        url.fragment = "";
                        state = State.FRAGMENT;
                    } else if (!base.scheme.equals("file")) {
                        state = State.RELATIVE;
                        pointer--;
                    } else {
                        state = State.FILE;
                        pointer--;
                    }
                    break;
                case SPECIAL_RELATIVE_OR_AUTHORITY:
                    if (c == '/' && remainingStartsWith(codePoints, pointer, "/")) {
                        state = State.SPECIAL_AUTHORITY_IGNORE_SLASHES;
                        pointer++;
                    } else {
                        state = State.RELATIVE;
                        pointer--;
                    }
                    break;
                case PATH_OR_AUTHORITY:
                    if (c == '/') state = State.AUTHORITY;
                    else {
                        state = State.PATH;
                        pointer--;
                    }
                    break;
                case RELATIVE:
                    url.scheme = base.scheme;
                    if (c == '/') state = State.RELATIVE_SLASH;
                    else if (url.isSpecial() && c == '\\') state = State.RELATIVE_SLASH;
                    else {
                        url.username = base.username;
                        url.password = base.password;
                        url.host = base.host;
                        url.port = base.port;
                        url.path = new ArrayList<>(base.path);
                        url.query = base.query;
                        if (c == '?') {
                            url.query = "";
                            state = State.QUERY;
                        } else if (c == '#') {
                            url.fragment = "";
                            state = State.FRAGMENT;
                        } else if (c != EOF) {
                            url.query = null;
                            url.shortenPath();
                            state = State.PATH;
                            pointer--;
                        }
                    }
                    break;
                case RELATIVE_SLASH:
                    if (url.isSpecial() && (c == '/' || c == '\\')) {
                        state = State.SPECIAL_AUTHORITY_IGNORE_SLASHES;
                    } else if (c == '/') {
                        state = State.AUTHORITY;
                    } else {
                        url.username = base.username;
                        url.password = base.password;
                        url.host = base.host;
                        url.port = base.port;
                        state = State.PATH;
                        pointer--;
                    }
                    break;
                case SPECIAL_AUTHORITY_SLASHES:
                    if (c == '/' && remainingStartsWith(codePoints, pointer, "/")) {
                        state = State.SPECIAL_AUTHORITY_IGNORE_SLASHES;
                        pointer++;
                    } else {
                        state = State.SPECIAL_AUTHORITY_IGNORE_SLASHES;
                        pointer--;
                    }
                    break;
                case SPECIAL_AUTHORITY_IGNORE_SLASHES:
                    if (c != '/' && c != '\\') {
                        state = State.AUTHORITY;
                        pointer--;
                    }
                    break;
                case AUTHORITY:
                    if (c == '@') {
                        if (atSignSeen) buffer.insert(0, "%40");
                        atSignSeen = true;
                        String text = buffer.toString();
                        StringBuilder username = new StringBuilder(url.username);
                        StringBuilder password = new StringBuilder(url.password);
                        int k = 0;
                        while (k < text.length()) {
                            int cp = text.codePointAt(k);
                            k += Character.charCount(cp);
                            if (cp == ':' && !passwordTokenSeen) {
                                passwordTokenSeen = true;
                                continue;
                            }
                            percentEncode(passwordTokenSeen ? password : username, cp, Url::userinfoSet, false);
                        }
                        url.username = username.toString();
                        url.password = password.toString();
                        buffer.setLength(0);
                    } else if (c == EOF || c == '/' || c == '?' || c == '#'
                            || (url.isSpecial() && c == '\\')) {
                        if (atSignSeen && buffer.length() == 0) return false;
                        pointer -= buffer.codePointCount(0, buffer.length()) + 1;
                        buffer.setLength(0);
                        state = State.HOST;
                    } else buffer.appendCodePoint(c);
                    break;
                case HOST:
                case HOSTNAME:
                    if (override != null && url.scheme.equals("file")) {
                        pointer--;
                        state = State.FILE_HOST;
                    } else if (c == ':' && !insideBrackets) {
                        if (buffer.length() == 0) return false;
                        if (override == State.HOSTNAME) return false;
                        Host host = parseHost(buffer.toString(), !url.isSpecial());
                        if (host == null) return false;
                        url.host = host;
                        buffer.setLength(0);
                        state = State.PORT;
                    } else if (c == EOF || c == '/' || c == '?' || c == '#'
                            || (url.isSpecial() && c == '\\')) {
                        pointer--;
                        if (url.isSpecial() && buffer.length() == 0) return false;
                        if (override != null && buffer.length() == 0
                                && (url.includesCredentials() || url.port >= 0)) return false;
                        Host host = parseHost(buffer.toString(), !url.isSpecial());
                        if (host == null) return false;
                        url.host = host;
                        buffer.setLength(0);
                        state = State.PATH_START;
                        if (override != null) return true;
                    } else {
                        if (c == '[') insideBrackets = true;
                        if (c == ']') insideBrackets = false;
                        buffer.appendCodePoint(c);
                    }
                    break;
                case PORT:
                    if (c >= '0' && c <= '9') {
                        buffer.appendCodePoint(c);
                    } else if (c == EOF || c == '/' || c == '?' || c == '#'
                            || (url.isSpecial() && c == '\\') || override != null) {
                        if (buffer.length() != 0) {
                            long port = 0;
                            for (int i = 0; i < buffer.length(); i++) {
                                port = port * 10 + (buffer.charAt(i) - '0');
                                if (port > 65535) return false;
                            }
                            url.port = (int) port == defaultPort(url.scheme) ? -1 : (int) port;
                            buffer.setLength(0);
                            if (override != null) return true;
                        }
                        if (override != null) return false;
                        state = State.PATH_START;
                        pointer--;
                    } else return false;
                    break;
                case FILE:
                    url.scheme = "file";
                    url.host = EmptyHost.INSTANCE;
                    if (c == '/' || c == '\\') state = State.FILE_SLASH;
                    else if (base != null && base.scheme.equals("file")) {
                        url.host = base.host;
                        url.path = new ArrayList<>(base.path);
                        url.query = base.query;
                        if (c == '?') {
                            url.query = "";
                            state = State.QUERY;
                        } else if (c == '#') {
                            url.fragment = "";
                            state = State.FRAGMENT;
                        } else if (c != EOF) {
                            url.query = null;
                            if (!startsWithDriveLetter(codePoints, pointer)) url.shortenPath();
                            else url.path = new ArrayList<>();
                            state = State.PATH;
                            pointer--;
                        }
                    } else {
                        state = State.PATH;
                        pointer--;
                    }
                    break;
                case FILE_SLASH:
                    if (c == '/' || c == '\\') state = State.FILE_HOST;
                    else {
                        if (base != null && base.scheme.equals("file")) {
                            url.host = base.host;
                            if (!startsWithDriveLetter(codePoints, pointer) && !base.path.isEmpty()
                                    && isNormalizedDriveLetter(base.path.get(0))) {
                                url.path.add(base.path.get(0));
                            }
                        }
                        state = State.PATH;
                        pointer--;
                    }
                    break;
                case FILE_HOST:
                    if (c == EOF || c == '/' || c == '\\' || c == '?' || c == '#') {
                        pointer--;
                        if (override == null && isWindowsDriveLetter(buffer.toString())) {
                            state = State.PATH;
                        } else if (buffer.length() == 0) {
                            url.host = EmptyHost.INSTANCE;
                            if (override != null) return true;
                            state = State.PATH_START;
                        } else {
                            Host host = parseHost(buffer.toString(), !url.isSpecial());
                            if (host == null) return false;
                            if (host instanceof Domain domain && domain.name.equals("localhost")) {
                                host = EmptyHost.INSTANCE;
                            }
                            url.host = host;
                            if (override != null) return true;
                            buffer.setLength(0);
                            state = State.PATH_START;
                        }
                    } else buffer.appendCodePoint(c);
                    break;
                case PATH_START:
                    if (url.isSpecial()) {
                        state = State.PATH;
                        if (c != '/' && c != '\\') pointer--;
                    } else if (override == null && c == '?') {
                        url.query = "";
                        state = State.QUERY;
                    } else if (override == null && c == '#') {
                        url.fragment = "";
                        state = State.FRAGMENT;
                    } else if (c != EOF) {
                        state = State.PATH;
                        if (c != '/') pointer--;
                    } else if (override != null && url.host == null) {
                        url.path.add("");
                    }
                    break;
                case PATH: {
                    boolean slash = c == '/' || (url.isSpecial() && c == '\\');
                    if (c == EOF || slash || (override == null && (c == '?' || c == '#'))) {
                        String text = buffer.toString();
                        if (isDoubleDot(text)) {
                            url.shortenPath();
                            if (!slash) url.path.add("");
                        } else if (isSingleDot(text) && !slash) {
                            url.path.add("");
                        } else if (!isSingleDot(text)) {
                            if (url.scheme.equals("file") && url.path.isEmpty()
                                    && isWindowsDriveLetter(text)) {
                                url.path.add(text.substring(0, 1) + ":");
                            } else url.path.add(text);
                        }
                        buffer.setLength(0);
                        if (c == '?') {
                            url.query = "";
                            state = State.QUERY;
                        }
                        if (c == '#') {
                            url.fragment = "";
                            state = State.FRAGMENT;
                        }
                    } else {
                        percentEncode(buffer, c, Url::pathSet, false);
                    }
                    break;
                }
                case OPAQUE_PATH:
                    if (c == '?') {
                        url.query = "";
                        state = State.QUERY;
                    } else if (c == '#') {
                        url.fragment = "";
                        state = State.FRAGMENT;
                    } else if (c == ' ') {
                        int next = pointer + 1 < codePoints.length ? codePoints[pointer + 1] : EOF;
                        opaque.append(next == '?' || next == '#' ? "%20" : " ");
                    } else if (c != EOF) percentEncode(opaque, c, Url::c0Control, false);
                    break;
                case QUERY:
                    if ((override == null && c == '#') || c == EOF) {
                        IntPredicate set = url.isSpecial() ? Url::specialQuerySet : Url::querySet;
                        url.query = (url.query == null ? "" : url.query)
                                + percentEncodeString(buffer.toString(), set);
                        buffer.setLength(0);
                        if (c == '#') {
                            url.fragment = "";
                            state = State.FRAGMENT;
                        }
                    } else if (c != EOF) buffer.appendCodePoint(c);
                    break;
                case FRAGMENT:
                    if (c != EOF) percentEncode(fragment, c, Url::fragmentSet, false);
                    break;
                default:
                    break;
            }
            if (pointer >= codePoints.length) break;
            pointer++;
            if ((++steps & 0xFFF) == 0 && agent != null) agent.checkInterrupt();
        }
        if (opaque.length() != 0) {
            url.opaquePath = (url.opaquePath == null ? "" : url.opaquePath) + opaque;
        }
        if (fragment.length() != 0) {
            url.fragment = (url.fragment == null ? "" : url.fragment) + fragment;
        }
        return true;
    }

    static ArrayList<Map.Entry<String, String>> parseForm(String input) {
        ArrayList<Map.Entry<String, String>> out = new ArrayList<>();
        for (String sequence : input.split("&", -1)) {
            if (sequence.isEmpty()) continue;
            int equals = sequence.indexOf('=');
            String name = equals >= 0 ? sequence.substring(0, equals) : sequence;
            String value = equals >= 0 ? sequence.substring(equals + 1) : "";
            out.add(new AbstractMap.SimpleImmutableEntry<>(
                    WhatwgUtf8.decode(percentDecode(name.replace('+', ' '))),
                    WhatwgUtf8.decode(percentDecode(value.replace('+', ' ')))));
        }
        return out;
    }

    static String serializeForm(List<Map.Entry<String, String>> list) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : list) {
            if (builder.length() > 0) builder.append('&');
            builder.append(percentEncodeString(entry.getKey(), Url::formSet, true))
                    .append('=')
                    .append(percentEncodeString(entry.getValue(), Url::formSet, true));
        }
        return builder.toString();
    }

    static final class JSURL extends JSObject {
        final Record url;
        JSURLSearchParams searchParams;

        JSURL(JSObject prototype, Record url) {
            super(prototype);
            this.url = url;
        }

        @Override
        public String getClassName() {
            return "URL";
        }
    }

    static final class JSURLSearchParams extends JSObject {
        ArrayList<Map.Entry<String, String>> list = new ArrayList<>();
        JSURL url;

        JSURLSearchParams(JSObject prototype) {
            super(prototype);
        }

        @Override
        public String getClassName() {
            return "URLSearchParams";
        }

        void update() {
            if (url == null) return;
            String query = serializeForm(list);
            url.url.query = query.isEmpty() ? null : query;
            if (query.isEmpty()) url.url.stripTrailingSpaces();
        }
    }

    private static JSURLSearchParams newSearchParams(Realm realm, JSURL url) {
        JSURLSearchParams params = new JSURLSearchParams(realm.intrinsic("%URLSearchParams.prototype%"));
        params.url = url;
        if (url.url.query != null) params.list = parseForm(url.url.query);
        return params;
    }

    private static JSURL newURL(Realm realm, JSObject prototype, Record record) {
        JSURL url = new JSURL(prototype, record);
        url.searchParams = newSearchParams(realm, url);
        return url;
    }

    private static void setterParse(String value, Record url, State state) {
        basicParse(value, null, url, state);
    }

    private static Record apiParse(Object url, Object base) {
        Record parsedBase = base == Undefined.INSTANCE ? null : parse(Idl.usvString(base), null);
        if (base != Undefined.INSTANCE && parsedBase == null) return null;
        return parse(Idl.usvString(url), parsedBase);
    }

    private static JSURL urlOf(Object receiver, String member) {
        return Idl.self(JSURL.class, receiver, "URL", member);
    }

    private static String href(Realm realm, Object receiver, String member) {
        String text = urlOf(receiver, member).url.serialize();
        realm.agent.checkStringLength(text.length());
        return text;
    }

    static void install(Realm realm) {
        WebInterface urlInterface = WebInterface.define(realm, "URL", 1, null, null,
                (arguments, prototype) -> {
                    Idl.required(arguments, 1, "URL constructor");
                    String urlText = Idl.usvString(arguments[0]);
                    String baseText = arguments.length > 1 && arguments[1] != Undefined.INSTANCE
                            ? Idl.usvString(arguments[1]) : null;
                    Record base = baseText == null ? null : parse(baseText, null);
                    if (baseText != null && base == null) throw typeErr("Invalid base URL: " + baseText);
                    Record record = parse(urlText, base);
                    if (record == null) throw typeErr("Invalid URL: " + urlText);
                    return newURL(realm, prototype, record);
                });
        urlInterface.staticOperation("parse", 1, (function, receiver, arguments, newTarget) -> {
            Idl.required(arguments, 1, "URL.parse");
            Record record = apiParse(arguments[0], arg(arguments, 1));
            return record == null ? Null.INSTANCE
                    : newURL(function.realm, function.realm.intrinsic("%URL.prototype%"), record);
        });
        urlInterface.staticOperation("canParse", 1, (function, receiver, arguments, newTarget) -> {
            Idl.required(arguments, 1, "URL.canParse");
            return apiParse(arguments[0], arg(arguments, 1)) != null;
        });
        urlInterface.attribute("href", (function, receiver, arguments, newTarget) ->
                        href(function.realm, receiver, "href"),
                (function, receiver, arguments, newTarget) -> {
                    JSURL url = urlOf(receiver, "href");
                    String text = Idl.usvString(arg(arguments, 0));
                    Record record = parse(text, null);
                    if (record == null) throw typeErr("Invalid URL: " + text);
                    url.url.assign(record);
                    url.searchParams.list = record.query == null
                            ? new ArrayList<>() : parseForm(record.query);
                    return Undefined.INSTANCE;
                });
        urlInterface.attribute("origin", (function, receiver, arguments, newTarget) ->
                urlOf(receiver, "origin").url.origin());
        urlInterface.attribute("protocol", (function, receiver, arguments, newTarget) ->
                        urlOf(receiver, "protocol").url.scheme + ":",
                (function, receiver, arguments, newTarget) -> {
                    JSURL url = urlOf(receiver, "protocol");
                    setterParse(Idl.usvString(arg(arguments, 0)) + ":", url.url, State.SCHEME_START);
                    return Undefined.INSTANCE;
                });
        urlInterface.attribute("username", (function, receiver, arguments, newTarget) ->
                        urlOf(receiver, "username").url.username,
                (function, receiver, arguments, newTarget) -> {
                    JSURL url = urlOf(receiver, "username");
                    String text = Idl.usvString(arg(arguments, 0));
                    if (!url.url.cannotHaveCredentialsOrPort()) {
                        url.url.username = percentEncodeString(text, Url::userinfoSet);
                    }
                    return Undefined.INSTANCE;
                });
        urlInterface.attribute("password", (function, receiver, arguments, newTarget) ->
                        urlOf(receiver, "password").url.password,
                (function, receiver, arguments, newTarget) -> {
                    JSURL url = urlOf(receiver, "password");
                    String text = Idl.usvString(arg(arguments, 0));
                    if (!url.url.cannotHaveCredentialsOrPort()) {
                        url.url.password = percentEncodeString(text, Url::userinfoSet);
                    }
                    return Undefined.INSTANCE;
                });
        urlInterface.attribute("host", (function, receiver, arguments, newTarget) -> {
            Record record = urlOf(receiver, "host").url;
            Host host = record.host;
            if (host == null) return "";
            return record.port < 0 ? host.serialize() : host.serialize() + ":" + record.port;
        }, (function, receiver, arguments, newTarget) -> {
            JSURL url = urlOf(receiver, "host");
            String text = Idl.usvString(arg(arguments, 0));
            if (url.url.opaquePath == null) setterParse(text, url.url, State.HOST);
            return Undefined.INSTANCE;
        });
        urlInterface.attribute("hostname", (function, receiver, arguments, newTarget) ->
                        urlOf(receiver, "hostname").url.host == null ? ""
                                : urlOf(receiver, "hostname").url.host.serialize(),
                (function, receiver, arguments, newTarget) -> {
                    JSURL url = urlOf(receiver, "hostname");
                    String text = Idl.usvString(arg(arguments, 0));
                    if (url.url.opaquePath == null) setterParse(text, url.url, State.HOSTNAME);
                    return Undefined.INSTANCE;
                });
        urlInterface.attribute("port", (function, receiver, arguments, newTarget) -> {
            int port = urlOf(receiver, "port").url.port;
            return port < 0 ? "" : Integer.toString(port);
        }, (function, receiver, arguments, newTarget) -> {
            JSURL url = urlOf(receiver, "port");
            String text = Idl.usvString(arg(arguments, 0));
            if (!url.url.cannotHaveCredentialsOrPort()) {
                if (text.isEmpty()) url.url.port = -1;
                else setterParse(text, url.url, State.PORT);
            }
            return Undefined.INSTANCE;
        });
        urlInterface.attribute("pathname", (function, receiver, arguments, newTarget) ->
                        urlOf(receiver, "pathname").url.pathname(),
                (function, receiver, arguments, newTarget) -> {
                    JSURL url = urlOf(receiver, "pathname");
                    String text = Idl.usvString(arg(arguments, 0));
                    if (url.url.opaquePath == null) {
                        url.url.path = new ArrayList<>();
                        setterParse(text, url.url, State.PATH_START);
                    }
                    return Undefined.INSTANCE;
                });
        urlInterface.attribute("search", (function, receiver, arguments, newTarget) -> {
            String query = urlOf(receiver, "search").url.query;
            return query == null || query.isEmpty() ? "" : "?" + query;
        }, (function, receiver, arguments, newTarget) -> {
            JSURL url = urlOf(receiver, "search");
            String text = Idl.usvString(arg(arguments, 0));
            if (text.isEmpty()) {
                url.url.query = null;
                url.searchParams.list = new ArrayList<>();
                url.url.stripTrailingSpaces();
            } else {
                String input = text.startsWith("?") ? text.substring(1) : text;
                url.url.query = "";
                setterParse(input, url.url, State.QUERY);
                url.searchParams.list = parseForm(url.url.query == null ? "" : url.url.query);
            }
            return Undefined.INSTANCE;
        });
        urlInterface.attribute("searchParams", (function, receiver, arguments, newTarget) ->
                urlOf(receiver, "searchParams").searchParams);
        urlInterface.attribute("hash", (function, receiver, arguments, newTarget) -> {
            String fragment = urlOf(receiver, "hash").url.fragment;
            return fragment == null || fragment.isEmpty() ? "" : "#" + fragment;
        }, (function, receiver, arguments, newTarget) -> {
            JSURL url = urlOf(receiver, "hash");
            String text = Idl.usvString(arg(arguments, 0));
            if (text.isEmpty()) {
                url.url.fragment = null;
                url.url.stripTrailingSpaces();
            } else {
                url.url.fragment = "";
                setterParse(text.startsWith("#") ? text.substring(1) : text, url.url, State.FRAGMENT);
            }
            return Undefined.INSTANCE;
        });
        urlInterface.operation("toJSON", 0, (function, receiver, arguments, newTarget) ->
                href(function.realm, receiver, "toJSON"));
        urlInterface.operation("toString", 0, (function, receiver, arguments, newTarget) ->
                href(function.realm, receiver, "toString"));

        WebInterface paramsInterface = WebInterface.define(realm, "URLSearchParams", 0, null, null,
                (arguments, prototype) -> {
                    JSURLSearchParams params = new JSURLSearchParams(prototype);
                    Object init = arg(arguments, 0);
                    if (init == Undefined.INSTANCE) {
                        return params;
                    }
                    if (init instanceof JSObject object
                            && Ops.getMethod(object, JSSymbol.iterator) != Undefined.INSTANCE) {
                        List<List<String>> pairs = Idl.sequence(realm, init, "URLSearchParams init",
                                inner -> Idl.sequence(realm, inner, "URLSearchParams init",
                                        Idl::usvString));
                        for (List<String> pair : pairs) {
                            if (pair.size() != 2) {
                                throw typeErr("URLSearchParams: a pair must have exactly two items");
                            }
                            params.list.add(new AbstractMap.SimpleImmutableEntry<>(pair.get(0), pair.get(1)));
                        }
                    } else if (init instanceof JSObject object) {
                        for (Map.Entry<String, String> entry : Idl.record(object,
                                Idl::usvString, Idl::usvString)) {
                            params.list.add(entry);
                        }
                    } else {
                        String text = Idl.usvString(init);
                        params.list = parseForm(text.startsWith("?") ? text.substring(1) : text);
                    }
                    return params;
                });
        paramsInterface.attribute("size", (function, receiver, arguments, newTarget) ->
                (double) Idl.self(JSURLSearchParams.class, receiver, "URLSearchParams", "size").list.size());
        paramsInterface.operation("append", 2, (function, receiver, arguments, newTarget) -> {
            JSURLSearchParams params = Idl.self(JSURLSearchParams.class, receiver,
                    "URLSearchParams", "append");
            Idl.required(arguments, 2, "URLSearchParams.append");
            params.list.add(new AbstractMap.SimpleImmutableEntry<>(
                    Idl.usvString(arguments[0]), Idl.usvString(arguments[1])));
            params.update();
            return Undefined.INSTANCE;
        });
        paramsInterface.operation("delete", 1, (function, receiver, arguments, newTarget) -> {
            JSURLSearchParams params = Idl.self(JSURLSearchParams.class, receiver,
                    "URLSearchParams", "delete");
            Idl.required(arguments, 1, "URLSearchParams.delete");
            String name = Idl.usvString(arguments[0]);
            String value = arguments.length > 1 && arguments[1] != Undefined.INSTANCE
                    ? Idl.usvString(arguments[1]) : null;
            params.list.removeIf(entry -> entry.getKey().equals(name)
                    && (value == null || entry.getValue().equals(value)));
            params.update();
            return Undefined.INSTANCE;
        });
        paramsInterface.operation("get", 1, (function, receiver, arguments, newTarget) -> {
            JSURLSearchParams params = Idl.self(JSURLSearchParams.class, receiver,
                    "URLSearchParams", "get");
            Idl.required(arguments, 1, "URLSearchParams.get");
            String name = Idl.usvString(arguments[0]);
            for (Map.Entry<String, String> entry : params.list) {
                if (entry.getKey().equals(name)) return entry.getValue();
            }
            return Null.INSTANCE;
        });
        paramsInterface.operation("getAll", 1, (function, receiver, arguments, newTarget) -> {
            JSURLSearchParams params = Idl.self(JSURLSearchParams.class, receiver,
                    "URLSearchParams", "getAll");
            Idl.required(arguments, 1, "URLSearchParams.getAll");
            String name = Idl.usvString(arguments[0]);
            ArrayList<Object> values = new ArrayList<>();
            for (Map.Entry<String, String> entry : params.list) {
                if (entry.getKey().equals(name)) values.add(entry.getValue());
            }
            return Builtins.arrayOf(function.realm, values);
        });
        paramsInterface.operation("has", 1, (function, receiver, arguments, newTarget) -> {
            JSURLSearchParams params = Idl.self(JSURLSearchParams.class, receiver,
                    "URLSearchParams", "has");
            Idl.required(arguments, 1, "URLSearchParams.has");
            String name = Idl.usvString(arguments[0]);
            String value = arguments.length > 1 && arguments[1] != Undefined.INSTANCE
                    ? Idl.usvString(arguments[1]) : null;
            for (Map.Entry<String, String> entry : params.list) {
                if (entry.getKey().equals(name) && (value == null || entry.getValue().equals(value))) {
                    return true;
                }
            }
            return false;
        });
        paramsInterface.operation("set", 2, (function, receiver, arguments, newTarget) -> {
            JSURLSearchParams params = Idl.self(JSURLSearchParams.class, receiver,
                    "URLSearchParams", "set");
            Idl.required(arguments, 2, "URLSearchParams.set");
            String name = Idl.usvString(arguments[0]);
            String value = Idl.usvString(arguments[1]);
            int first = -1;
            for (int i = 0; i < params.list.size(); i++) {
                if (params.list.get(i).getKey().equals(name)) {
                    first = i;
                    break;
                }
            }
            if (first < 0) {
                params.list.add(new AbstractMap.SimpleImmutableEntry<>(name, value));
            } else {
                params.list.set(first, new AbstractMap.SimpleImmutableEntry<>(name, value));
                final int keep = first;
                final int[] index = {-1};
                params.list.removeIf(entry -> {
                    index[0]++;
                    return index[0] > keep && entry.getKey().equals(name);
                });
            }
            params.update();
            return Undefined.INSTANCE;
        });
        paramsInterface.operation("sort", 0, (function, receiver, arguments, newTarget) -> {
            JSURLSearchParams params = Idl.self(JSURLSearchParams.class, receiver,
                    "URLSearchParams", "sort");
            params.list.sort((a, b) -> a.getKey().compareTo(b.getKey()));
            params.update();
            return Undefined.INSTANCE;
        });
        paramsInterface.pairIterable(JSURLSearchParams.class, params -> params.list);
        paramsInterface.operation("toString", 0, (function, receiver, arguments, newTarget) -> {
            JSURLSearchParams params = Idl.self(JSURLSearchParams.class, receiver,
                    "URLSearchParams", "toString");
            String text = serializeForm(params.list);
            function.realm.agent.checkStringLength(text.length());
            return text;
        });
    }
}

/** The UTF-8 decoder of the Encoding Standard (invalid sequences become U+FFFD, a leading BOM is kept). */
final class WhatwgUtf8 {
    private WhatwgUtf8() {
    }

    static String decode(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length);
        int codePoint = 0;
        int needed = 0;
        int seen = 0;
        int lower = 0x80;
        int upper = 0xBF;
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xFF;
            if (needed == 0) {
                if (b >= 0x00 && b <= 0x7F) out.append((char) b);
                else if (b >= 0xC2 && b <= 0xDF) {
                    needed = 1;
                    codePoint = b & 0x1F;
                } else if (b >= 0xE0 && b <= 0xEF) {
                    if (b == 0xE0) lower = 0xA0;
                    if (b == 0xED) upper = 0x9F;
                    needed = 2;
                    codePoint = b & 0xF;
                } else if (b >= 0xF0 && b <= 0xF4) {
                    if (b == 0xF0) lower = 0x90;
                    if (b == 0xF4) upper = 0x8F;
                    needed = 3;
                    codePoint = b & 0x7;
                } else out.append('\uFFFD');
                i++;
                continue;
            }
            if (b < lower || b > upper) {
                codePoint = 0;
                needed = 0;
                seen = 0;
                lower = 0x80;
                upper = 0xBF;
                out.append('\uFFFD');
                continue;
            }
            lower = 0x80;
            upper = 0xBF;
            codePoint = (codePoint << 6) | (b & 0x3F);
            seen++;
            i++;
            if (seen == needed) {
                out.appendCodePoint(codePoint);
                codePoint = 0;
                needed = 0;
                seen = 0;
            }
        }
        if (needed != 0) out.append('\uFFFD');
        return out.toString();
    }
}
