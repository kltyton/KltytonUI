/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

/** Snapshot of the lexer state used for arbitrary lookahead. */
public class LexState {
    public final int pos;
    public final int line;
    public final int lineStart;
    public final T type;
    public final int start;
    public final int end;
    public final int tokLine;
    public final int tokCol;
    public final boolean nlBefore;
    public final Object value;
    public final boolean escaped;
    public final int legacyOctalPos;
    public final String raw;
    public final String cooked;
    public final boolean templateTail;
    public final String regexFlags;
    public final int prevEnd;
    public final int prevLine;
    public final int prevCol;
    public final int invalidEscapePos;

    public LexState(int pos, int line, int lineStart, T type, int start, int end,
                    int tokLine, int tokCol, boolean nlBefore, Object value, boolean escaped,
                    int legacyOctalPos, String raw, String cooked, boolean templateTail,
                    String regexFlags, int prevEnd, int prevLine, int prevCol,
                    int invalidEscapePos) {
        this.pos = pos;
        this.line = line;
        this.lineStart = lineStart;
        this.type = type;
        this.start = start;
        this.end = end;
        this.tokLine = tokLine;
        this.tokCol = tokCol;
        this.nlBefore = nlBefore;
        this.value = value;
        this.escaped = escaped;
        this.legacyOctalPos = legacyOctalPos;
        this.raw = raw;
        this.cooked = cooked;
        this.templateTail = templateTail;
        this.regexFlags = regexFlags;
        this.prevEnd = prevEnd;
        this.prevLine = prevLine;
        this.prevCol = prevCol;
        this.invalidEscapePos = invalidEscapePos;
    }

    public int getPos() {
        return pos;
    }

    public int getLine() {
        return line;
    }

    public int getLineStart() {
        return lineStart;
    }

    public T getType() {
        return type;
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }

    public int getTokLine() {
        return tokLine;
    }

    public int getTokCol() {
        return tokCol;
    }

    public boolean getNlBefore() {
        return nlBefore;
    }

    public Object getValue() {
        return value;
    }

    public boolean getEscaped() {
        return escaped;
    }

    public int getLegacyOctalPos() {
        return legacyOctalPos;
    }

    public String getRaw() {
        return raw;
    }

    public String getCooked() {
        return cooked;
    }

    public boolean getTemplateTail() {
        return templateTail;
    }

    public String getRegexFlags() {
        return regexFlags;
    }

    public int getPrevEnd() {
        return prevEnd;
    }

    public int getPrevLine() {
        return prevLine;
    }

    public int getPrevCol() {
        return prevCol;
    }

    public int getInvalidEscapePos() {
        return invalidEscapePos;
    }
}
