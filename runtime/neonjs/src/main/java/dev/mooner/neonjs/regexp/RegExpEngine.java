/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.regexp;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Entry points of the regular expression engine: compilation (with a shared cache) and early-error validation. */
public final class RegExpEngine {
    private static final class Key {
        final String pattern;
        final int flags;

        Key(String pattern, int flags) {
            this.pattern = pattern;
            this.flags = flags;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.flags == flags && Objects.equals(key.pattern, pattern);
        }

        @Override
        public int hashCode() {
            return pattern.hashCode() * 31 + flags;
        }
    }

    private static final int CACHE_LIMIT = 1024;
    private static final ConcurrentHashMap<Key, RegExpProgram> cache = new ConcurrentHashMap<>();

    private RegExpEngine() {
    }

    /** Compiles {@code pattern} with flag bits {@code flags}; throws {@link RegExpSyntaxError}. */
    public static RegExpProgram compile(String pattern, int flags) {
        if (pattern.length() > 4096) return RegExpCompiler.compile(pattern, flags);
        Key key = new Key(pattern, flags);
        RegExpProgram cached = cache.get(key);
        if (cached != null) return cached;
        RegExpProgram program = RegExpCompiler.compile(pattern, flags);
        if (cache.size() >= CACHE_LIMIT) cache.clear();
        cache.put(key, program);
        return program;
    }

    /** Compiles a pattern with a flags string; throws {@link RegExpSyntaxError} for invalid flags or pattern. */
    public static RegExpProgram compile(String pattern, String flags) {
        int parsed = RegExpProgram.parseFlags(flags);
        if (parsed < 0) throw new RegExpSyntaxError("invalid regular expression flags");
        return compile(pattern, parsed);
    }

    /** Returns an error message if the literal {@code /pattern/flags} is not a valid regular expression, else null. */
    public static String validate(String pattern, String flags) {
        int parsed = RegExpProgram.parseFlags(flags);
        if (parsed < 0) return "Invalid regular expression flags";
        try {
            compile(pattern, parsed);
            return null;
        } catch (RegExpSyntaxError error) {
            return error.getMessage();
        }
    }
}
