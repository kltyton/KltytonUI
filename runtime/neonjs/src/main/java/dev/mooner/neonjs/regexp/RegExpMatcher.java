/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.regexp;

import dev.mooner.neonjs.unicode.CharRanges;
import dev.mooner.neonjs.unicode.UnicodeTables;
import java.util.Arrays;

/**
 * Backtracking interpreter for {@link RegExpProgram} bytecode (design after QuickJS lre_exec_backtrack). It never
 * recurses: choice points and capture undo records live on an explicit IntArray stack whose size is bounded by
 * {@link #MAX_STACK}.
 *
 * <p>Positions are UTF-16 code unit indices into {@code input}; in unicode mode surrogate pairs are read as one
 * character. Not thread-safe: create one matcher per match operation.
 */
public final class RegExpMatcher {
    private static final int STATE_SPLIT = 0;
    private static final int STATE_LOOKAHEAD = 1;
    private static final int STATE_NEGATIVE_LOOKAHEAD = 2;
    private static final int INTERRUPT_INTERVAL = 4096;

    /** Maximum backtracking stack size in ints (256 MB). */
    public static final int MAX_STACK = 1 << 26;

    private final RegExpProgram prog;
    private final String input;
    private final Runnable interrupt;
    private final int[] code;
    private final boolean unicode;
    private final int captureSlots;

    /** Capture positions (start, end per group; -1 = undefined) followed by the loop registers. */
    final int[] cap;
    private int[] stack = new int[48];
    private int interruptCounter = INTERRUPT_INTERVAL;
    private StringBuilder sb;
    private UnicodeTables.CaseMap caseMap;

    RegExpMatcher(RegExpProgram prog, String input, Runnable interrupt) {
        this.prog = prog;
        this.input = input;
        this.interrupt = interrupt;
        this.code = prog.code;
        this.unicode = prog.isUnicode();
        this.captureSlots = prog.captureCount * 2;
        this.cap = new int[captureSlots + prog.registerCount];
    }

    /** Start index of capture group {@code index} after a successful match, or -1. */
    public int start(int index) {
        return cap[2 * index];
    }

    /** End index of capture group {@code index} after a successful match, or -1. */
    public int end(int index) {
        return cap[2 * index + 1];
    }

    /**
     * Runs the pattern starting at code unit {@code index} (0 &lt;= index &lt;= input.length). Non-sticky programs
     * search forward from {@code index}. Returns true on success; the captures are then available through
     * {@link #start(int)}/{@link #end(int)}.
     */
    public boolean match(int index) {
        if ((prog.flags & RegExpProgram.FLAG_STICKY) == 0) return search(index);
        int p = index;
        String s = input;
        if (unicode && p > 0 && p < s.length()
                && Character.isLowSurrogate(s.charAt(p)) && Character.isHighSurrogate(s.charAt(p - 1))) {
            p--;
        }
        return attempt(p);
    }

    /** Like {@link #match(int)}, but always searches forward from {@code index} (ignores the sticky flag). */
    public boolean search(int index) {
        String s = input;
        int end = s.length();
        int p = index;
        if (unicode && p > 0 && p < end
                && Character.isLowSurrogate(s.charAt(p)) && Character.isHighSurrogate(s.charAt(p - 1))) {
            p--;
        }
        int kind = prog.startKind;
        if (kind == RegExpProgram.START_ANCHOR) return p == 0 && attempt(0);
        while (true) {
            if (kind != RegExpProgram.START_ANY) {
                p = nextCandidate(p, kind);
                if (p < 0) return false;
            }
            if (attempt(p)) return true;
            if (p >= end) return false;
            p += unicode && Character.isHighSurrogate(s.charAt(p))
                    && p + 1 < end && Character.isLowSurrogate(s.charAt(p + 1)) ? 2 : 1;
            if (--interruptCounter <= 0) poll();
        }
    }

    private boolean attempt(int p) {
        Arrays.fill(cap, 0, captureSlots, -1);
        return run(p);
    }

    /** Finds the next position &gt;= {@code from} where the first character of the pattern can match, or -1. */
    private int nextCandidate(int from, int kind) {
        String s = input;
        int end = s.length();
        int arg = prog.startArg;
        if (kind == RegExpProgram.START_CHAR) {
            if (arg < 0x10000) return s.indexOf((char) arg, from);
            return s.indexOf(new String(Character.toChars(arg)), from);
        }
        int p = from;
        while (p < end) {
            int c = s.charAt(p);
            int n = 1;
            if (unicode && c >= 0xD800 && c <= 0xDBFF && p + 1 < end
                    && Character.isLowSurrogate(s.charAt(p + 1))) {
                c = Character.toCodePoint((char) c, s.charAt(p + 1));
                n = 2;
            }
            boolean ok;
            if (kind == RegExpProgram.START_CHAR_I) {
                ok = canon(c) == arg;
            } else if (kind == RegExpProgram.START_RANGE) {
                ok = inRangeSet(code, arg, c);
            } else {
                ok = inRangeSet(code, arg, canon(c));
            }
            if (ok) return p;
            p += n;
            if (--interruptCounter <= 0) poll();
        }
        return -1;
    }

    private void poll() {
        interruptCounter = INTERRUPT_INTERVAL;
        if (interrupt != null) interrupt.run();
    }

    private int[] grow(int[] st, int needed) {
        if (needed > MAX_STACK) throw new RegExpStackOverflowError();
        int n = Math.min(Math.max(needed, st.length * 2), MAX_STACK);
        int[] result = Arrays.copyOf(st, n);
        stack = result;
        return result;
    }

    /** Canonicalize(rer, c) for case-insensitive matching. */
    private int canon(int c) {
        if (c < 128) {
            return unicode ? (c >= 'A' && c <= 'Z' ? c + 32 : c)
                    : (c >= 'a' && c <= 'z' ? c - 32 : c);
        }
        if (!unicode && c > 0xFFFF) return c;
        UnicodeTables.CaseMap map = caseMap;
        if (map == null) {
            map = unicode ? UnicodeTables.getSimpleFolding() : UnicodeTables.getNonUnicodeCanonical();
            caseMap = map;
        }
        return map.map(c);
    }

    private boolean isWordChar(int c, boolean ignoreCaseUnicode) {
        if (c < 128) {
            return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_';
        }
        return ignoreCaseUnicode && (c == 0x017F || c == 0x212A);
    }

    private boolean isSpace(int c) {
        if (c < 128) return c == 32 || c >= 9 && c <= 13;
        return CharRanges.contains(RegExpCompiler.spaceSet, c);
    }

    /** Membership test for the RANGE instruction at {@code insn}: ASCII bitmap, then binary search of the ranges. */
    private boolean inRangeSet(int[] code, int insn, int c) {
        if (c < 128) return (code[insn + 2 + (c >>> 5)] & (1 << (c & 31))) != 0;
        int n = code[insn + 1];
        int base = insn + 6;
        if (c < code[base] || c > code[base + 2 * n - 1]) return false;
        int lo = 0;
        int hi = n - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int k = base + 2 * mid;
            if (c < code[k]) {
                hi = mid - 1;
            } else if (c > code[k + 1]) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /** Matches a string group at {@code pos}; returns the new position or -1. */
    private int matchStringGroup(StringGroup group, int pos) {
        String s = input;
        StringBuilder builder = sb;
        if (builder == null) {
            builder = new StringBuilder();
            sb = builder;
        }
        builder.setLength(0);
        int p = pos;
        if (!group.backward) {
            for (int i = 0; i < group.length; i++) {
                if (p >= s.length()) return -1;
                int c = s.charAt(p++);
                if (c >= 0xD800 && c <= 0xDBFF && p < s.length()
                        && Character.isLowSurrogate(s.charAt(p))) {
                    c = Character.toCodePoint((char) c, s.charAt(p++));
                }
                if (group.ignoreCase) c = canon(c);
                builder.appendCodePoint(c);
            }
        } else {
            for (int i = 0; i < group.length; i++) {
                if (p <= 0) return -1;
                int c = s.charAt(--p);
                if (c >= 0xDC00 && c <= 0xDFFF && p > 0
                        && Character.isHighSurrogate(s.charAt(p - 1))) {
                    c = Character.toCodePoint(s.charAt(--p), (char) c);
                }
                if (group.ignoreCase) c = canon(c);
                builder.insert(0, Character.toChars(c));
            }
        }
        return group.strings.contains(builder.toString()) ? p : -1;
    }

    private boolean run(int startPos) {
        int[] code = this.code;
        String s = input;
        int end = s.length();
        boolean unicode = this.unicode;
        int[] cap = this.cap;
        int regBase = captureSlots;
        int[] st = stack;
        int sp = 0;
        int bp = 0;
        int pc = 0;
        int cpos = startPos;

        while (true) {
            exec:
            while (true) {
                int op = code[pc++];
                switch (op) {
                    case Op.MATCH:
                        return true;
                    case Op.CHAR:
                    case Op.CHAR_I: {
                        int v = code[pc++];
                        if (cpos >= end) break exec;
                        int c = s.charAt(cpos++);
                        if (unicode && c >= 0xD800 && c <= 0xDBFF && cpos < end) {
                            int d = s.charAt(cpos);
                            if (d >= 0xDC00 && d <= 0xDFFF) {
                                c = Character.toCodePoint((char) c, (char) d);
                                cpos++;
                            }
                        }
                        if (op == Op.CHAR_I) c = canon(c);
                        if (c != v) break exec;
                        break;
                    }
                    case Op.DOT:
                    case Op.ANY:
                    case Op.SPACE:
                    case Op.NOT_SPACE: {
                        if (cpos >= end) break exec;
                        int c = s.charAt(cpos++);
                        if (unicode && c >= 0xD800 && c <= 0xDBFF && cpos < end) {
                            int d = s.charAt(cpos);
                            if (d >= 0xDC00 && d <= 0xDFFF) {
                                c = Character.toCodePoint((char) c, (char) d);
                                cpos++;
                            }
                        }
                        if (op == Op.DOT) {
                            if (c == 10 || c == 13 || c == 0x2028 || c == 0x2029) break exec;
                        } else if (op == Op.SPACE) {
                            if (!isSpace(c)) break exec;
                        } else if (op == Op.NOT_SPACE) {
                            if (isSpace(c)) break exec;
                        }
                        break;
                    }
                    case Op.RANGE:
                    case Op.RANGE_I: {
                        int insn = pc - 1;
                        pc = insn + 6 + 2 * code[pc];
                        if (cpos >= end) break exec;
                        int c = s.charAt(cpos++);
                        if (unicode && c >= 0xD800 && c <= 0xDBFF && cpos < end) {
                            int d = s.charAt(cpos);
                            if (d >= 0xDC00 && d <= 0xDFFF) {
                                c = Character.toCodePoint((char) c, (char) d);
                                cpos++;
                            }
                        }
                        if (op == Op.RANGE_I) c = canon(c);
                        if (!inRangeSet(code, insn, c)) break exec;
                        break;
                    }
                    case Op.SPLIT_GOTO_FIRST:
                    case Op.SPLIT_NEXT_FIRST: {
                        int off = code[pc++];
                        int alt;
                        if (op == Op.SPLIT_NEXT_FIRST) {
                            alt = pc + off;
                        } else {
                            alt = pc;
                            pc += off;
                        }
                        if (sp + 3 > st.length) st = grow(st, sp + 3);
                        st[sp] = alt;
                        st[sp + 1] = cpos;
                        st[sp + 2] = (bp << 2) | STATE_SPLIT;
                        sp += 3;
                        bp = sp;
                        break;
                    }
                    case Op.LOOKAHEAD:
                    case Op.NEGATIVE_LOOKAHEAD: {
                        int off = code[pc++];
                        if (sp + 3 > st.length) st = grow(st, sp + 3);
                        st[sp] = pc + off;
                        st[sp + 1] = cpos;
                        st[sp + 2] = (bp << 2)
                                | (op == Op.LOOKAHEAD ? STATE_LOOKAHEAD : STATE_NEGATIVE_LOOKAHEAD);
                        sp += 3;
                        bp = sp;
                        break;
                    }
                    case Op.GOTO: {
                        int off = code[pc++];
                        pc += off;
                        if (--interruptCounter <= 0) poll();
                        break;
                    }
                    case Op.LINE_START:
                    case Op.LINE_START_M: {
                        if (cpos == 0) continue exec;
                        if (op == Op.LINE_START) break exec;
                        int c = s.charAt(cpos - 1);
                        if (!(c == 10 || c == 13 || c == 0x2028 || c == 0x2029)) break exec;
                        break;
                    }
                    case Op.LINE_END:
                    case Op.LINE_END_M: {
                        if (cpos == end) continue exec;
                        if (op == Op.LINE_END) break exec;
                        int c = s.charAt(cpos);
                        if (!(c == 10 || c == 13 || c == 0x2028 || c == 0x2029)) break exec;
                        break;
                    }
                    case Op.SAVE_START:
                    case Op.SAVE_END: {
                        int idx = 2 * code[pc++] + (op - Op.SAVE_START);
                        if (sp + 2 > st.length) st = grow(st, sp + 2);
                        st[sp] = idx;
                        st[sp + 1] = cap[idx];
                        sp += 2;
                        cap[idx] = cpos;
                        break;
                    }
                    case Op.SAVE_RESET: {
                        int g = code[pc];
                        int last = code[pc + 1];
                        pc += 2;
                        int need = sp + 4 * (last - g + 1);
                        if (need > st.length) st = grow(st, need);
                        while (g <= last) {
                            int idx = 2 * g;
                            st[sp] = idx;
                            st[sp + 1] = cap[idx];
                            st[sp + 2] = idx + 1;
                            st[sp + 3] = cap[idx + 1];
                            sp += 4;
                            cap[idx] = -1;
                            cap[idx + 1] = -1;
                            g++;
                        }
                        break;
                    }
                    case Op.SET_I32:
                    case Op.SET_CHAR_POS: {
                        int idx = regBase + code[pc];
                        int value = op == Op.SET_I32 ? code[pc + 1] : cpos;
                        pc += op == Op.SET_I32 ? 2 : 1;
                        // save the previous value unless already saved since the last choice point
                        int k = sp;
                        boolean saved = false;
                        while (k > bp) {
                            if (st[k - 2] == idx) {
                                saved = true;
                                break;
                            }
                            k -= 2;
                        }
                        if (!saved) {
                            if (sp + 2 > st.length) st = grow(st, sp + 2);
                            st[sp] = idx;
                            st[sp + 1] = cap[idx];
                            sp += 2;
                        }
                        cap[idx] = value;
                        break;
                    }
                    case Op.CHECK_ADVANCE: {
                        int idx = regBase + code[pc++];
                        if (cap[idx] == cpos) break exec;
                        break;
                    }
                    case Op.LOOP:
                    case Op.LOOP_SPLIT_GOTO_FIRST:
                    case Op.LOOP_SPLIT_NEXT_FIRST:
                    case Op.LOOP_CHECK_ADV_SPLIT_GOTO_FIRST:
                    case Op.LOOP_CHECK_ADV_SPLIT_NEXT_FIRST: {
                        int idx = regBase + code[pc];
                        int limit;
                        int off;
                        if (op == Op.LOOP) {
                            limit = 0;
                            off = code[pc + 1];
                            pc += 2;
                        } else {
                            limit = code[pc + 1];
                            off = code[pc + 2];
                            pc += 3;
                        }
                        int counter = cap[idx] - 1;
                        int k = sp;
                        boolean saved = false;
                        while (k > bp) {
                            if (st[k - 2] == idx) {
                                saved = true;
                                break;
                            }
                            k -= 2;
                        }
                        if (!saved) {
                            if (sp + 2 > st.length) st = grow(st, sp + 2);
                            st[sp] = idx;
                            st[sp + 1] = cap[idx];
                            sp += 2;
                        }
                        cap[idx] = counter;
                        if (op == Op.LOOP) {
                            if (counter != 0) {
                                pc += off;
                                if (--interruptCounter <= 0) poll();
                            }
                        } else if (counter > limit) {
                            // mandatory iterations
                            pc += off;
                            if (--interruptCounter <= 0) poll();
                        } else {
                            if ((op == Op.LOOP_CHECK_ADV_SPLIT_GOTO_FIRST
                                    || op == Op.LOOP_CHECK_ADV_SPLIT_NEXT_FIRST)
                                    && cap[idx + 1] == cpos && counter != limit) {
                                break exec;
                            }
                            if (counter != 0) {
                                int alt;
                                if (op == Op.LOOP_SPLIT_NEXT_FIRST
                                        || op == Op.LOOP_CHECK_ADV_SPLIT_NEXT_FIRST) {
                                    alt = pc + off;
                                } else {
                                    alt = pc;
                                    pc += off;
                                }
                                if (sp + 3 > st.length) st = grow(st, sp + 3);
                                st[sp] = alt;
                                st[sp + 1] = cpos;
                                st[sp + 2] = (bp << 2) | STATE_SPLIT;
                                sp += 3;
                                bp = sp;
                            }
                        }
                        break;
                    }
                    case Op.WORD_BOUNDARY:
                    case Op.WORD_BOUNDARY_I:
                    case Op.NOT_WORD_BOUNDARY:
                    case Op.NOT_WORD_BOUNDARY_I: {
                        boolean ignoreCaseBoundary = op == Op.WORD_BOUNDARY_I
                                || op == Op.NOT_WORD_BOUNDARY_I;
                        boolean boundary = op == Op.WORD_BOUNDARY || op == Op.WORD_BOUNDARY_I;
                        boolean before;
                        if (cpos == 0) {
                            before = false;
                        } else {
                            int c = s.charAt(cpos - 1);
                            if (unicode && c >= 0xDC00 && c <= 0xDFFF && cpos >= 2
                                    && Character.isHighSurrogate(s.charAt(cpos - 2))) {
                                c = Character.toCodePoint(s.charAt(cpos - 2), (char) c);
                            }
                            before = isWordChar(c, ignoreCaseBoundary);
                        }
                        boolean after;
                        if (cpos >= end) {
                            after = false;
                        } else {
                            int c = s.charAt(cpos);
                            if (unicode && c >= 0xD800 && c <= 0xDBFF && cpos + 1 < end
                                    && Character.isLowSurrogate(s.charAt(cpos + 1))) {
                                c = Character.toCodePoint((char) c, s.charAt(cpos + 1));
                            }
                            after = isWordChar(c, ignoreCaseBoundary);
                        }
                        if ((before != after) != boundary) break exec;
                        break;
                    }
                    case Op.BACK_REFERENCE:
                    case Op.BACK_REFERENCE_I:
                    case Op.BACKWARD_BACK_REFERENCE:
                    case Op.BACKWARD_BACK_REFERENCE_I: {
                        int n = code[pc];
                        int base = pc + 1;
                        pc = base + n;
                        boolean ignoreCaseReference = op == Op.BACK_REFERENCE_I
                                || op == Op.BACKWARD_BACK_REFERENCE_I;
                        boolean forward = op == Op.BACK_REFERENCE || op == Op.BACK_REFERENCE_I;
                        for (int i = 0; i < n; i++) {
                            int g = code[base + i];
                            int cs = cap[2 * g];
                            int ce = cap[2 * g + 1];
                            if (cs < 0 || ce < 0) continue;
                            if (forward) {
                                if (!ignoreCaseReference && !unicode) {
                                    int len = ce - cs;
                                    if (cpos + len > end || !s.regionMatches(cpos, s, cs, len)) break exec;
                                    cpos += len;
                                } else {
                                    int p1 = cs;
                                    while (p1 < ce) {
                                        if (cpos >= end) break exec;
                                        int c1 = s.charAt(p1++);
                                        if (unicode && c1 >= 0xD800 && c1 <= 0xDBFF && p1 < ce
                                                && Character.isLowSurrogate(s.charAt(p1))) {
                                            c1 = Character.toCodePoint((char) c1, s.charAt(p1++));
                                        }
                                        int c2 = s.charAt(cpos++);
                                        if (unicode && c2 >= 0xD800 && c2 <= 0xDBFF && cpos < end
                                                && Character.isLowSurrogate(s.charAt(cpos))) {
                                            c2 = Character.toCodePoint((char) c2, s.charAt(cpos++));
                                        }
                                        if (ignoreCaseReference) {
                                            c1 = canon(c1);
                                            c2 = canon(c2);
                                        }
                                        if (c1 != c2) break exec;
                                    }
                                }
                            } else {
                                int p1 = ce;
                                while (p1 > cs) {
                                    if (cpos == 0) break exec;
                                    int c1 = s.charAt(--p1);
                                    if (unicode && c1 >= 0xDC00 && c1 <= 0xDFFF && p1 > cs
                                            && Character.isHighSurrogate(s.charAt(p1 - 1))) {
                                        c1 = Character.toCodePoint(s.charAt(--p1), (char) c1);
                                    }
                                    int c2 = s.charAt(--cpos);
                                    if (unicode && c2 >= 0xDC00 && c2 <= 0xDFFF && cpos > 0
                                            && Character.isHighSurrogate(s.charAt(cpos - 1))) {
                                        c2 = Character.toCodePoint(s.charAt(--cpos), (char) c2);
                                    }
                                    if (ignoreCaseReference) {
                                        c1 = canon(c1);
                                        c2 = canon(c2);
                                    }
                                    if (c1 != c2) break exec;
                                }
                            }
                            break;
                        }
                        break;
                    }
                    case Op.PREV: {
                        if (cpos == 0) break exec;
                        cpos--;
                        if (unicode && Character.isLowSurrogate(s.charAt(cpos))
                                && cpos > 0 && Character.isHighSurrogate(s.charAt(cpos - 1))) {
                            cpos--;
                        }
                        break;
                    }
                    case Op.STRING_SET: {
                        int np = matchStringGroup(prog.stringGroups[code[pc++]], cpos);
                        if (np < 0) break exec;
                        cpos = np;
                        break;
                    }
                    case Op.LOOKAHEAD_MATCH: {
                        // pop the choice points up to the lookahead start, keeping the capture undo records
                        int spTop = sp;
                        while (true) {
                            int sp1 = sp;
                            sp = bp;
                            pc = st[sp - 3];
                            cpos = st[sp - 2];
                            int word = st[sp - 1];
                            bp = word >>> 2;
                            st[sp - 1] = sp1; // end of this frame's undo records, for the copy below
                            sp -= 3;
                            if ((word & 3) == STATE_LOOKAHEAD) break;
                        }
                        if (sp != 0) {
                            int sp1 = sp;
                            while (sp1 < spTop) {
                                int next = st[sp1 + 2];
                                sp1 += 3;
                                while (sp1 < next) st[sp++] = st[sp1++];
                            }
                        }
                        break;
                    }
                    case Op.NEGATIVE_LOOKAHEAD_MATCH: {
                        // the negative lookahead body matched: undo everything up to its start, then fail
                        while (true) {
                            while (sp > bp) {
                                cap[st[sp - 2]] = st[sp - 1];
                                sp -= 2;
                            }
                            // pc and cpos are restored by the backtracking below
                            int word = st[sp - 1];
                            bp = word >>> 2;
                            sp -= 3;
                            if ((word & 3) == STATE_NEGATIVE_LOOKAHEAD) break;
                        }
                        break exec;
                    }
                    default:
                        throw new IllegalStateException("bad regexp opcode " + op);
                }
            }
            // backtrack
            while (true) {
                if (bp == 0) {
                    stack = st;
                    return false;
                }
                while (sp > bp) {
                    cap[st[sp - 2]] = st[sp - 1];
                    sp -= 2;
                }
                pc = st[sp - 3];
                cpos = st[sp - 2];
                int word = st[sp - 1];
                bp = word >>> 2;
                sp -= 3;
                if ((word & 3) != STATE_LOOKAHEAD) break;
            }
            if (--interruptCounter <= 0) poll();
        }
    }
}
