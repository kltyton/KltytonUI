/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

/**
 * Syntax error raised by the lexer/parser. Positions are 0-based offsets, 1-based lines/columns.
 */
public class JSSyntaxError extends RuntimeException {
    public final int pos;
    public final int line;
    public final int column;
    public final String sourceName;

    public JSSyntaxError(String message, int pos, int line, int column, String sourceName) {
        super(message, null, false, false);
        this.pos = pos;
        this.line = line;
        this.column = column;
        this.sourceName = sourceName;
    }

    public JSSyntaxError(String message, int pos, int line, int column) {
        this(message, pos, line, column, null);
    }

    public String getRawMessage() {
        String message = super.getMessage();
        return message == null ? "" : message;
    }

    @Override
    public String getMessage() {
        return String.valueOf(super.getMessage()) + " ("
                + (sourceName == null ? "<input>" : sourceName) + ":" + line + ":" + column + ")";
    }

    public int getPos() {
        return pos;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public String getSourceName() {
        return sourceName;
    }
}
