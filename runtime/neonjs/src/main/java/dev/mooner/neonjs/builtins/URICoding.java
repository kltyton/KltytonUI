/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSException;
import java.nio.charset.StandardCharsets;

/** URI percent-encoding used by encodeURI/decodeURI and escape/unescape; adapted to Java. */
final class URICoding {
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String HEX = "0123456789ABCDEF";

    private URICoding() {
    }

    static String encode(String s, String extra) {
        StringBuilder out = new StringBuilder();
        int k = 0;
        while (k < s.length()) {
            char c = s.charAt(k);
            if (ALNUM.indexOf(c) >= 0 || extra.indexOf(c) >= 0) {
                out.append(c);
                k++;
                continue;
            }
            int codePoint;
            if (Character.isLowSurrogate(c)) throw JSException.uriError("URI malformed");
            if (Character.isHighSurrogate(c)) {
                if (k + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(k + 1))) {
                    throw JSException.uriError("URI malformed");
                }
                codePoint = Character.toCodePoint(c, s.charAt(k + 1));
                k += 2;
            } else {
                codePoint = c;
                k++;
            }
            byte[] bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
            for (byte b : bytes) {
                int value = b & 0xFF;
                out.append('%').append(HEX.charAt(value >>> 4)).append(HEX.charAt(value & 15));
            }
        }
        return out.toString();
    }

    /** ASCII hex digit value or -1 (Character.digit would also accept non-ASCII digits such as U+0661). */
    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static boolean allHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (hexVal(value.charAt(i)) < 0) return false;
        }
        return true;
    }

    static String decode(String s, String reserved) {
        StringBuilder out = new StringBuilder();
        int k = 0;
        int n = s.length();
        while (k < n) {
            char c = s.charAt(k);
            if (c != '%') {
                out.append(c);
                k++;
                continue;
            }
            int start = k;
            if (k + 2 >= n) throw JSException.uriError("URI malformed");
            int h1 = hexVal(s.charAt(k + 1));
            int h2 = hexVal(s.charAt(k + 2));
            if (h1 < 0 || h2 < 0) throw JSException.uriError("URI malformed");
            int b = (h1 << 4) | h2;
            k += 3;
            if ((b & 0x80) == 0) {
                char ch = (char) b;
                if (reserved.indexOf(ch) >= 0) out.append(s, start, k);
                else out.append(ch);
                continue;
            }
            int byteCount;
            if ((b & 0xE0) == 0xC0) byteCount = 2;
            else if ((b & 0xF0) == 0xE0) byteCount = 3;
            else if ((b & 0xF8) == 0xF0) byteCount = 4;
            else throw JSException.uriError("URI malformed");
            int[] octets = new int[byteCount];
            octets[0] = b;
            for (int j = 1; j < byteCount; j++) {
                if (k + 2 >= n || s.charAt(k) != '%') throw JSException.uriError("URI malformed");
                int a1 = hexVal(s.charAt(k + 1));
                int a2 = hexVal(s.charAt(k + 2));
                if (a1 < 0 || a2 < 0) throw JSException.uriError("URI malformed");
                int next = (a1 << 4) | a2;
                if ((next & 0xC0) != 0x80) throw JSException.uriError("URI malformed");
                octets[j] = next;
                k += 3;
            }
            int codePoint;
            switch (byteCount) {
                case 2 -> codePoint = ((octets[0] & 0x1F) << 6) | (octets[1] & 0x3F);
                case 3 -> codePoint = ((octets[0] & 0x0F) << 12) | ((octets[1] & 0x3F) << 6)
                        | (octets[2] & 0x3F);
                default -> codePoint = ((octets[0] & 0x07) << 18) | ((octets[1] & 0x3F) << 12)
                        | ((octets[2] & 0x3F) << 6) | (octets[3] & 0x3F);
            }
            int minimum = byteCount == 2 ? 0x80 : byteCount == 3 ? 0x800 : 0x10000;
            if (codePoint < minimum || codePoint > 0x10FFFF
                    || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                throw JSException.uriError("URI malformed");
            }
            out.appendCodePoint(codePoint);
        }
        return out.toString();
    }

    static String escape(String s) {
        String ok = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789@*_+-./";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (ok.indexOf(c) >= 0) {
                out.append(c);
            } else if (c < 256) {
                out.append('%').append(HEX.charAt(c >>> 4)).append(HEX.charAt(c & 15));
            } else {
                out.append("%u").append(HEX.charAt(c >>> 12)).append(HEX.charAt((c >>> 8) & 15))
                        .append(HEX.charAt((c >>> 4) & 15)).append(HEX.charAt(c & 15));
            }
        }
        return out.toString();
    }

    static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        int k = 0;
        int n = s.length();
        while (k < n) {
            char c = s.charAt(k);
            if (c == '%') {
                if (k + 6 <= n && s.charAt(k + 1) == 'u') {
                    String value = s.substring(k + 2, k + 6);
                    if (allHex(value)) {
                        out.append((char) Integer.parseInt(value, 16));
                        k += 6;
                        continue;
                    }
                }
                if (k + 3 <= n) {
                    String value = s.substring(k + 1, k + 3);
                    if (allHex(value)) {
                        out.append((char) Integer.parseInt(value, 16));
                        k += 3;
                        continue;
                    }
                }
            }
            out.append(c);
            k++;
        }
        return out.toString();
    }
}
