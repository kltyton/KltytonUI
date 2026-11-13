/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs;

/** A syntax error reported by {@link NeonEngine#compile(String, String)}. */
public final class NeonSyntaxException extends NeonException {
    public final int line;
    public final int column;
    public final String sourceName;

    public NeonSyntaxException(String message, int line, int column, String sourceName) {
        super(message);
        this.line = line;
        this.column = column;
        this.sourceName = sourceName;
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

    @Override
    public boolean isSyntaxError() {
        return true;
    }
}
