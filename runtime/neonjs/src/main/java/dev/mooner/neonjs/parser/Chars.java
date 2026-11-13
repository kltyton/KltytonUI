/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public final class Chars {
    /** Kotlin-object style access point; the actual helpers are static. */
    public static final Chars INSTANCE = new Chars();

    private Chars() {
    }

    public static boolean isLineTerminator(int c) {
        return c == 0x0A || c == 0x0D || c == 0x2028 || c == 0x2029;
    }

    public static boolean isWhitespace(int c) {
        switch (c) {
            case 0x09:
            case 0x0B:
            case 0x0C:
            case 0x20:
            case 0xA0:
            case 0xFEFF:
                return true;
            default:
                return c > 0x7F && c != 0x180E
                        && Character.getType(c) == Character.SPACE_SEPARATOR;
        }
    }

    public static boolean isIdStart(int c) {
        if (c < 0x80) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '$' || c == '_';
        }
        return UnicodeId.INSTANCE.isIdStart(c);
    }

    public static boolean isIdPart(int c) {
        if (c < 0x80) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '$' || c == '_';
        }
        if (c == 0x200C || c == 0x200D) {
            return true;
        }
        return UnicodeId.INSTANCE.isIdContinue(c);
    }

    public static int hexVal(int c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
