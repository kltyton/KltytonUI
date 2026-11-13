package dev.mooner.neonjs.parser;

import dev.mooner.neonjs.unicode.UnicodeTables;

/**
 * Classifies Unicode identifier characters using the engine's Unicode tables.
 * Derived from NeonJS and adapted to Java.
 */
public final class UnicodeId {
    public static final UnicodeId INSTANCE = new UnicodeId();

    private UnicodeId() {
    }

    public boolean isIdStart(int codePoint) {
        return UnicodeTables.isIdStart(codePoint);
    }

    public boolean isIdContinue(int codePoint) {
        return UnicodeTables.isIdContinue(codePoint);
    }
}
