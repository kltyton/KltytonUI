/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class RegExpLiteral extends Node {
    public final String pattern;
    public final String flags;

    public RegExpLiteral(String pattern, String flags) {
        this.pattern = pattern;
        this.flags = flags;
    }

    public String getPattern() {
        return pattern;
    }

    public String getFlags() {
        return flags;
    }
}
