/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

/**
 * Base class for every AST node. Positions are source offsets; {@link #line}/{@link #col} are the
 * 1-based line and 0-based column of the node start.
 */
public abstract class Node {
    public int start = 0;
    public int end = 0;
    public int line = 0;
    public int col = 0;
    /** True when the expression was wrapped in parentheses. */
    public boolean parenthesized = false;
    /** Compiler annotation: scope created by this node (set by the scope analyzer). */
    public Object scope = null;
    /** Compiler annotation: resolved reference for identifiers / this / super / new.target. */
    public Object ref = null;

    public int getStart() {
        return start;
    }

    public void setStart(int start) {
        this.start = start;
    }

    public int getEnd() {
        return end;
    }

    public void setEnd(int end) {
        this.end = end;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public int getCol() {
        return col;
    }

    public void setCol(int col) {
        this.col = col;
    }

    public boolean getParenthesized() {
        return parenthesized;
    }

    public void setParenthesized(boolean parenthesized) {
        this.parenthesized = parenthesized;
    }

    public void setScope(Object scope) {
        this.scope = scope;
    }

    public void setRef(Object ref) {
        this.ref = ref;
    }
}
