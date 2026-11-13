/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.regexp;

/** A compiled regular expression. Immutable and safe to share between threads and realms. */
public final class RegExpProgram {
    static final int START_ANY = 0;
    static final int START_ANCHOR = 1;
    static final int START_CHAR = 2;
    static final int START_CHAR_I = 3;
    static final int START_RANGE = 4;
    static final int START_RANGE_I = 5;

    public static final int FLAG_GLOBAL = 1;
    public static final int FLAG_IGNORECASE = 2;
    public static final int FLAG_MULTILINE = 4;
    public static final int FLAG_DOTALL = 8;
    public static final int FLAG_UNICODE = 16;
    public static final int FLAG_STICKY = 32;
    public static final int FLAG_INDICES = 64;
    public static final int FLAG_UNICODE_SETS = 128;

    final int[] code;
    /** Number of capture groups including the implicit group 0. */
    public final int captureCount;
    final int registerCount;
    /** Group name of each capture index (index 0 is always null), or null if the pattern has no named groups. */
    public final String[] groupNames;
    public final int flags;
    final StringGroup[] stringGroups;

    /** How a non-sticky search can skip start positions: one of the START_ constants. */
    final int startKind;
    /** START_CHAR(_I): the character; START_RANGE(_I): the pc of the RANGE instruction. */
    final int startArg;

    RegExpProgram(int[] code, int captureCount, int registerCount, String[] groupNames,
                  int flags, StringGroup[] stringGroups) {
        this.code = code;
        this.captureCount = captureCount;
        this.registerCount = registerCount;
        this.groupNames = groupNames;
        this.flags = flags;
        this.stringGroups = stringGroups;

        // find the first instruction that consumes input or asserts, skipping capture/register bookkeeping
        int pc = 0;
        while (code[pc] == Op.SAVE_START || code[pc] == Op.SAVE_END
                || code[pc] == Op.SET_I32 || code[pc] == Op.SET_CHAR_POS) {
            pc += Op.size(code, pc);
        }
        int kind = START_ANY;
        int arg = 0;
        switch (code[pc]) {
            case Op.LINE_START:
                kind = START_ANCHOR;
                break;
            case Op.CHAR:
                int c = code[pc + 1];
                // a lone surrogate could be found inside a surrogate pair in unicode mode
                if (c >= 0 && (!isUnicode() || c < 0xD800 || c > 0xDFFF)) {
                    kind = START_CHAR;
                    arg = c;
                }
                break;
            case Op.CHAR_I:
                kind = START_CHAR_I;
                arg = code[pc + 1];
                break;
            case Op.RANGE:
            case Op.RANGE_I:
                kind = code[pc] == Op.RANGE ? START_RANGE : START_RANGE_I;
                arg = pc;
                break;
            default:
                break;
        }
        startKind = kind;
        startArg = arg;
    }

    public boolean isUnicode() {
        return (flags & (FLAG_UNICODE | FLAG_UNICODE_SETS)) != 0;
    }

    /** Creates a matcher for {@code input}; {@code interrupt} is called periodically during long matches. */
    public RegExpMatcher matcher(String input) {
        return new RegExpMatcher(this, input, null);
    }

    /** Creates a matcher for {@code input}; {@code interrupt} is called periodically during long matches. */
    public RegExpMatcher matcher(String input, Runnable interrupt) {
        return new RegExpMatcher(this, input, interrupt);
    }

    /** Parses a flags string; returns -1 if it contains an unknown or repeated flag or both {@code u} and {@code v}. */
    public static int parseFlags(String flags) {
        int mask = 0;
        for (int i = 0; i < flags.length(); i++) {
            char c = flags.charAt(i);
            int f;
            switch (c) {
                case 'd':
                    f = FLAG_INDICES;
                    break;
                case 'g':
                    f = FLAG_GLOBAL;
                    break;
                case 'i':
                    f = FLAG_IGNORECASE;
                    break;
                case 'm':
                    f = FLAG_MULTILINE;
                    break;
                case 's':
                    f = FLAG_DOTALL;
                    break;
                case 'u':
                    f = FLAG_UNICODE;
                    break;
                case 'v':
                    f = FLAG_UNICODE_SETS;
                    break;
                case 'y':
                    f = FLAG_STICKY;
                    break;
                default:
                    return -1;
            }
            if ((mask & f) != 0) return -1;
            mask |= f;
        }
        if ((mask & FLAG_UNICODE) != 0 && (mask & FLAG_UNICODE_SETS) != 0) return -1;
        return mask;
    }
}
