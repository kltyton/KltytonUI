/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

/** Regular expression literal site. */
public final class RegExpSite {
    public final String pattern;
    public final String flags;
    public volatile Object compiled;

    public RegExpSite(String pattern, String flags) {
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
