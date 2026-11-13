/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.regexp;

import dev.mooner.neonjs.unicode.CharRanges;
import dev.mooner.neonjs.unicode.UnicodeTables;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import static dev.mooner.neonjs.regexp.RegExpProgram.FLAG_DOTALL;
import static dev.mooner.neonjs.regexp.RegExpProgram.FLAG_IGNORECASE;
import static dev.mooner.neonjs.regexp.RegExpProgram.FLAG_MULTILINE;
import static dev.mooner.neonjs.regexp.RegExpProgram.FLAG_UNICODE;
import static dev.mooner.neonjs.regexp.RegExpProgram.FLAG_UNICODE_SETS;

/**
 * Regular expression parser and bytecode compiler (ECMAScript 2025 pattern grammar including Annex B legacy syntax
 * in non-unicode mode, named groups with duplicates, lookbehind, {@code v} flag set operations and pattern modifiers).
 * Structure after QuickJS libregexp.
 */
final class RegExpCompiler {
    private static final int CLASS = -2;
    private static final int INF = Integer.MAX_VALUE;
    private static final int MAX_DEPTH = 1000;
    private static final int MAX_CAPTURES = 65535;
    private static final int MAX_REGISTERS = 65535;

    private static final int[] DIGITS = CharRanges.INSTANCE.of('0', '9');
    private static final int[] BASIC_WORD = new CharRanges.Builder()
            .addRange('0', '9').addRange('A', 'Z').addRange('_', '_')
            .addRange('a', 'z').build();

    /** WhiteSpace and LineTerminator code points ({@code \s}). */
    static final int[] spaceSet = CharRanges.INSTANCE.union(
            new CharRanges.Builder().addRange(9, 13).addRange(0xA0, 0xA0)
                    .addRange(0x2028, 0x2029).addRange(0xFEFF, 0xFEFF).build(),
            UnicodeTables.generalCategory("Zs"));

    private final String src;
    private final int reFlags;
    private final int end;
    private int pos;
    private final boolean isUnicode;
    private final boolean unicodeSets;
    private boolean ignoreCase;
    private boolean multiline;
    private boolean dotAll;

    private final IntBuf code = new IntBuf();
    private int captureCount = 1;
    private final ArrayList<String> groupNames = new ArrayList<>();
    private boolean anyGroupName;

    /** Group names that may participate together with a group defined at the current position. */
    private HashSet<String> visibleNames = new HashSet<>();
    private final ArrayList<StringGroup> stringGroups = new ArrayList<>();
    private int depth;

    // pre-scan results (computed on demand)
    private boolean prescanned;
    private boolean prescanHasNames;
    private final ArrayList<String> prescanNames = new ArrayList<>();

    // result of getClassAtom when it returns CLASS
    private ClassSet atomClass;
    private int atomClassEscape;
    private int escEnd;

    private RegExpCompiler(String src, int reFlags) {
        this.src = src;
        this.reFlags = reFlags;
        this.end = src.length();
        this.isUnicode = (reFlags & (FLAG_UNICODE | FLAG_UNICODE_SETS)) != 0;
        this.unicodeSets = (reFlags & FLAG_UNICODE_SETS) != 0;
        this.ignoreCase = (reFlags & FLAG_IGNORECASE) != 0;
        this.multiline = (reFlags & FLAG_MULTILINE) != 0;
        this.dotAll = (reFlags & FLAG_DOTALL) != 0;
        groupNames.add(null);
    }

    /** A character class value: code points (inversion list) plus, in {@code v} mode, strings of length != 1. */
    private static final class ClassSet {
        int[] chars;
        HashSet<String> strings;
        boolean mayContainStrings;

        ClassSet(int[] chars) {
            this.chars = chars;
        }
    }

    private RegExpSyntaxError error(String message) {
        return new RegExpSyntaxError(message);
    }

    private int ch(int index) {
        return index < end ? src.charAt(index) : -1;
    }

    // ------------------------------------------------------------------ emission helpers

    private void emit(int op) {
        code.add(op);
    }

    private void emit(int op, int a) {
        code.add(op);
        code.add(a);
    }

    /** Emits a jump instruction with a placeholder offset; returns the offset slot. */
    private int emitJump(int op) {
        code.add(op);
        code.add(0);
        return code.size - 1;
    }

    /** Emits a jump to {@code target}. */
    private void emitJumpTo(int op, int target) {
        code.add(op);
        code.add(target - (code.size + 1));
    }

    /** Points the jump whose offset is at {@code slot} to the current end of the code. */
    private void patch(int slot) {
        code.set(slot, code.size - (slot + 1));
    }

    // ------------------------------------------------------------------ compile

    private RegExpProgram compile() {
        // the search over start positions of non-sticky patterns is done by RegExpMatcher.match
        emit(Op.SAVE_START, 0);
        parseDisjunction(false);
        emit(Op.SAVE_END, 0);
        emit(Op.MATCH);
        if (pos < end) {
            throw error(src.charAt(pos) == ')' ? "unmatched ')'" : "extraneous characters at the end");
        }
        int[] codeArray = code.toArray();
        int registerCount = computeRegisterCount(codeArray);
        return new RegExpProgram(codeArray, captureCount, registerCount,
                anyGroupName ? groupNames.toArray(new String[0]) : null,
                reFlags, stringGroups.toArray(new StringGroup[0]));
    }

    /** Allocates loop registers as a stack (the control flow is properly nested). */
    private int computeRegisterCount(int[] c) {
        int stackSize = 0;
        int max = 0;
        int pc = 0;
        while (pc < c.length) {
            switch (c[pc]) {
                case Op.SET_I32:
                case Op.SET_CHAR_POS:
                    c[pc + 1] = stackSize;
                    stackSize++;
                    if (stackSize > max) {
                        if (stackSize > MAX_REGISTERS) throw error("too many nested quantifiers");
                        max = stackSize;
                    }
                    break;
                case Op.CHECK_ADVANCE:
                case Op.LOOP:
                case Op.LOOP_SPLIT_GOTO_FIRST:
                case Op.LOOP_SPLIT_NEXT_FIRST:
                    stackSize--;
                    c[pc + 1] = stackSize;
                    break;
                case Op.LOOP_CHECK_ADV_SPLIT_GOTO_FIRST:
                case Op.LOOP_CHECK_ADV_SPLIT_NEXT_FIRST:
                    stackSize -= 2;
                    c[pc + 1] = stackSize;
                    break;
                default:
                    break;
            }
            pc += Op.size(c, pc);
        }
        return max;
    }

    private void enterNesting() {
        if (++depth > MAX_DEPTH) throw error("regular expression too deeply nested");
    }

    // ------------------------------------------------------------------ disjunction / alternative

    private void parseDisjunction(boolean backward) {
        enterNesting();
        int start = code.size;
        HashSet<String> snapshot = hasNamedGroups() ? new HashSet<>(visibleNames) : null;
        HashSet<String> accum = null;
        parseAlternative(backward);
        while (ch(pos) == '|') {
            pos++;
            if (snapshot != null) {
                if (accum == null) accum = new HashSet<>();
                accum.addAll(visibleNames);
                visibleNames = new HashSet<>(snapshot);
            }
            int len = code.size - start;
            code.insert(start, 2);
            code.set(start, Op.SPLIT_NEXT_FIRST);
            code.set(start + 1, len + 2);
            int gotoSlot = emitJump(Op.GOTO);
            parseAlternative(backward);
            patch(gotoSlot);
        }
        if (accum != null) visibleNames.addAll(accum);
        depth--;
    }

    private void parseAlternative(boolean backward) {
        int start = code.size;
        while (true) {
            int c = ch(pos);
            if (c < 0 || c == '|' || c == ')') break;
            int termStart = code.size;
            parseTerm(backward);
            if (backward && termStart != start) {
                // reverse the order of the terms
                int termSize = code.size - termStart;
                int[] term = Arrays.copyOfRange(code.a, termStart, code.size);
                System.arraycopy(code.a, start, code.a, start + termSize, termStart - start);
                System.arraycopy(term, 0, code.a, start, termSize);
            }
        }
    }

    // ------------------------------------------------------------------ term

    private void parseTerm(boolean backward) {
        int lastAtomStart = -1;
        int lastCaptureCount = 0;
        int c = ch(pos);
        int atomChar = -1; // a single character atom to emit, or CLASS
        switch (c) {
            case '^':
                pos++;
                emit(multiline ? Op.LINE_START_M : Op.LINE_START);
                break;
            case '$':
                pos++;
                emit(multiline ? Op.LINE_END_M : Op.LINE_END);
                break;
            case '.':
                pos++;
                lastAtomStart = code.size;
                lastCaptureCount = captureCount;
                if (backward) emit(Op.PREV);
                emit(dotAll ? Op.ANY : Op.DOT);
                if (backward) emit(Op.PREV);
                break;
            case '{':
                if (isUnicode) throw error("nothing to repeat");
                if (isDigit(ch(pos + 1))) {
                    // Annex B: error only if it looks like a complete repetition count
                    int p = pos + 1;
                    while (isDigit(ch(p))) p++;
                    if (ch(p) == ',') {
                        p++;
                        while (isDigit(ch(p))) p++;
                    }
                    if (ch(p) == '}') throw error("nothing to repeat");
                }
                atomChar = getClassAtom(false, true);
                break;
            case '*':
            case '+':
            case '?':
                throw error("nothing to repeat");
            case '(':
                if (ch(pos + 1) == '?') {
                    int c2 = ch(pos + 2);
                    if (c2 == ':') {
                        pos += 3;
                        lastAtomStart = code.size;
                        lastCaptureCount = captureCount;
                        parseDisjunction(backward);
                        expect(')');
                    } else if (c2 == 'i' || c2 == 'm' || c2 == 's' || c2 == '-') {
                        pos += 2;
                        int addMask = parseModifiers();
                        int removeMask = 0;
                        if (ch(pos) == '-') {
                            pos++;
                            removeMask = parseModifiers();
                        }
                        if ((addMask == 0 && removeMask == 0) || (addMask & removeMask) != 0) {
                            throw error("invalid modifiers");
                        }
                        expect(':');
                        boolean savedI = ignoreCase;
                        boolean savedM = multiline;
                        boolean savedS = dotAll;
                        ignoreCase = updateModifier(ignoreCase, addMask, removeMask, FLAG_IGNORECASE);
                        multiline = updateModifier(multiline, addMask, removeMask, FLAG_MULTILINE);
                        dotAll = updateModifier(dotAll, addMask, removeMask, FLAG_DOTALL);
                        lastAtomStart = code.size;
                        lastCaptureCount = captureCount;
                        parseDisjunction(backward);
                        expect(')');
                        ignoreCase = savedI;
                        multiline = savedM;
                        dotAll = savedS;
                    } else if (c2 == '=' || c2 == '!'
                            || (c2 == '<' && (ch(pos + 3) == '=' || ch(pos + 3) == '!'))) {
                        boolean isBackwardLookaround = c2 == '<';
                        boolean isNeg = (isBackwardLookaround ? ch(pos + 3) : c2) == '!';
                        pos += isBackwardLookaround ? 4 : 3;
                        // Annex B: lookaheads are quantifiable in non-unicode mode
                        if (!isUnicode && !isBackwardLookaround) {
                            lastAtomStart = code.size;
                            lastCaptureCount = captureCount;
                        }
                        int slot = emitJump(isNeg ? Op.NEGATIVE_LOOKAHEAD : Op.LOOKAHEAD);
                        parseDisjunction(isBackwardLookaround);
                        expect(')');
                        emit(isNeg ? Op.NEGATIVE_LOOKAHEAD_MATCH : Op.LOOKAHEAD_MATCH);
                        patch(slot);
                    } else if (c2 == '<') {
                        String name = parseGroupName(pos + 3);
                        if (name == null) throw error("invalid capture group name");
                        pos = escEnd;
                        if (!visibleNames.add(name)) throw error("duplicate capture group name");
                        anyGroupName = true;
                        lastAtomStart = code.size;
                        lastCaptureCount = captureCount;
                        parseCapture(name, backward);
                    } else {
                        throw error("invalid group");
                    }
                } else {
                    pos++;
                    lastAtomStart = code.size;
                    lastCaptureCount = captureCount;
                    parseCapture(null, backward);
                }
                break;
            case '\\': {
                int escape = ch(pos + 1);
                if (escape == 'b' || escape == 'B') {
                    boolean boundary = ch(pos + 1) == 'b';
                    boolean i = ignoreCase && isUnicode;
                    emit(boundary
                            ? (i ? Op.WORD_BOUNDARY_I : Op.WORD_BOUNDARY)
                            : (i ? Op.NOT_WORD_BOUNDARY_I : Op.NOT_WORD_BOUNDARY));
                    pos += 2;
                } else if (escape == 'k') {
                    int[] indices = parseNamedBackReference();
                    if (indices == null) {
                        atomChar = getClassAtom(false, true);
                    } else {
                        lastAtomStart = code.size;
                        lastCaptureCount = captureCount;
                        emitBackReference(indices, backward);
                    }
                } else if (escape == '0') {
                    pos += 2;
                    c = 0;
                    if (isUnicode) {
                        if (isDigit(ch(pos))) throw error("invalid decimal escape");
                    } else if (ch(pos) >= '0' && ch(pos) <= '7') {
                        // Annex B legacy octal escape
                        c = ch(pos++) - '0';
                        if (ch(pos) >= '0' && ch(pos) <= '7') {
                            c = c * 8 + (ch(pos++) - '0');
                        }
                    }
                    atomChar = c;
                } else if (escape >= '1' && escape <= '9') {
                    pos++;
                    int q = pos;
                    int n = parseDigits(false);
                    if (n < 0 || (n >= captureCount && n >= totalCaptureCount())) {
                        if (isUnicode) throw error("back reference out of range");
                        // Annex B: legacy octal escape or identity escape
                        pos = q;
                        if (ch(pos) <= '7') {
                            c = 0;
                            if (ch(pos) <= '3') c = ch(pos++) - '0';
                            if (ch(pos) >= '0' && ch(pos) <= '7') {
                                c = c * 8 + (ch(pos++) - '0');
                                if (ch(pos) >= '0' && ch(pos) <= '7') {
                                    c = c * 8 + (ch(pos++) - '0');
                                }
                            }
                        } else {
                            c = ch(pos++);
                        }
                        atomChar = c;
                    } else {
                        lastAtomStart = code.size;
                        lastCaptureCount = captureCount;
                        emitBackReference(new int[] {n}, backward);
                    }
                } else {
                    atomChar = getClassAtom(false, true);
                }
                break;
            }
            case '[': {
                lastAtomStart = code.size;
                lastCaptureCount = captureCount;
                ClassSet classSet = unicodeSets ? parseClassV() : parseClassLegacy();
                emitClassSet(classSet, backward);
                break;
            }
            case ']':
            case '}':
                if (isUnicode) throw error("lone quantifier brackets");
                atomChar = getClassAtom(false, true);
                break;
            default:
                atomChar = getClassAtom(false, true);
                break;
        }
        if (atomChar != -1) {
            lastAtomStart = code.size;
            lastCaptureCount = captureCount;
            if (atomChar == CLASS) {
                ClassSet classSet = atomClass;
                atomClass = null;
                if (atomClassEscape == 's' || atomClassEscape == 'S') {
                    if (backward) emit(Op.PREV);
                    emit(atomClassEscape == 's' ? Op.SPACE : Op.NOT_SPACE);
                    if (backward) emit(Op.PREV);
                } else {
                    if (!unicodeSets && ignoreCase) {
                        classSet.chars = UnicodeTables.canonicalizeSet(classSet.chars, isUnicode);
                    }
                    emitClassSet(classSet, backward);
                }
            } else {
                if (backward) emit(Op.PREV);
                if (ignoreCase) {
                    emit(Op.CHAR_I, UnicodeTables.canonicalize(atomChar, isUnicode));
                } else {
                    emit(Op.CHAR, atomChar);
                }
                if (backward) emit(Op.PREV);
            }
        }
        if (lastAtomStart >= 0) parseQuantifier(lastAtomStart, lastCaptureCount);
    }

    private void parseCapture(String name, boolean backward) {
        if (captureCount >= MAX_CAPTURES) throw error("too many captures");
        int index = captureCount++;
        groupNames.add(name);
        emit(backward ? Op.SAVE_END : Op.SAVE_START, index);
        parseDisjunction(backward);
        emit(backward ? Op.SAVE_START : Op.SAVE_END, index);
        expect(')');
    }

    private int parseModifiers() {
        int mask = 0;
        while (true) {
            int c = ch(pos);
            int value;
            if (c == 'i') {
                value = FLAG_IGNORECASE;
            } else if (c == 'm') {
                value = FLAG_MULTILINE;
            } else if (c == 's') {
                value = FLAG_DOTALL;
            } else {
                break;
            }
            if ((mask & value) != 0) throw error("duplicate modifier");
            mask |= value;
            pos++;
        }
        return mask;
    }

    private boolean updateModifier(boolean value, int add, int remove, int mask) {
        if ((remove & mask) != 0) return false;
        if ((add & mask) != 0) return true;
        return value;
    }

    private void expect(char c) {
        if (ch(pos) != c) throw error("expecting '" + c + "'");
        pos++;
    }

    /** Parses decimal digits at {@code pos}; clamps to Int.MAX_VALUE if {@code allowOverflow}, else returns -1. */
    private int parseDigits(boolean allowOverflow) {
        long value = 0;
        while (isDigit(ch(pos))) {
            value = value * 10 + (ch(pos) - '0');
            if (value >= Integer.MAX_VALUE) {
                if (!allowOverflow) return -1;
                value = Integer.MAX_VALUE;
            }
            pos++;
        }
        return (int) value;
    }

    private void emitBackReference(int[] indices, boolean backward) {
        int op = backward
                ? (ignoreCase ? Op.BACKWARD_BACK_REFERENCE_I : Op.BACKWARD_BACK_REFERENCE)
                : (ignoreCase ? Op.BACK_REFERENCE_I : Op.BACK_REFERENCE);
        emit(op, indices.length);
        for (int index : indices) code.add(index);
    }

    /**
     * Parses {@code \k<name>} at {@code pos}. Returns the capture indices of the groups with that name, or null if the
     * escape must be parsed as an identity escape (Annex B, non-unicode pattern without named groups).
     */
    private int[] parseNamedBackReference() {
        boolean strict = isUnicode || hasNamedGroups();
        if (ch(pos + 2) != '<') {
            if (strict) throw error("expecting group name");
            return null;
        }
        String name = parseGroupName(pos + 3);
        if (name == null) {
            if (strict) throw error("invalid group name");
            return null;
        }
        prescan();
        ArrayList<Integer> indices = new ArrayList<>();
        for (int i = 0; i < prescanNames.size(); i++) {
            if (Objects.equals(prescanNames.get(i), name)) indices.add(i);
        }
        if (indices.isEmpty()) {
            if (strict) throw error("group name not defined");
            return null;
        }
        pos = escEnd;
        int[] result = new int[indices.size()];
        for (int i = 0; i < result.length; i++) result[i] = indices.get(i);
        return result;
    }

    // ------------------------------------------------------------------ quantifiers

    private void parseQuantifier(int atomStart, int lastCaptureCount) {
        int lastAtomStart = atomStart;
        int quantMin;
        int quantMax;
        int c = ch(pos);
        if (c == '*') {
            pos++;
            quantMin = 0;
            quantMax = INF;
        } else if (c == '+') {
            pos++;
            quantMin = 1;
            quantMax = INF;
        } else if (c == '?') {
            pos++;
            quantMin = 0;
            quantMax = 1;
        } else if (c == '{') {
            int p1 = pos;
            if (!isDigit(ch(pos + 1))) {
                if (isUnicode) throw error("incomplete quantifier");
                return;
            }
            pos++;
            quantMin = parseDigits(true);
            quantMax = quantMin;
            if (ch(pos) == ',') {
                pos++;
                quantMax = isDigit(ch(pos)) ? parseDigits(true) : INF;
            }
            if (ch(pos) != '}') {
                if (!isUnicode) {
                    // Annex B: '{' is a literal character
                    pos = p1;
                    return;
                }
                throw error("incomplete quantifier");
            }
            pos++;
            if (quantMax < quantMin) throw error("numbers out of order in {} quantifier");
        } else {
            return;
        }
        boolean greedy = true;
        if (ch(pos) == '?') {
            pos++;
            greedy = false;
        }
        // analyse the atom
        boolean addZeroAdvanceCheck = true;
        boolean needCaptureInit = false;
        analysis:
        {
            int pc = lastAtomStart;
            while (pc < code.size) {
                switch (code.get(pc)) {
                    case Op.RANGE:
                    case Op.RANGE_I:
                    case Op.CHAR:
                    case Op.CHAR_I:
                    case Op.DOT:
                    case Op.ANY:
                    case Op.SPACE:
                    case Op.NOT_SPACE:
                        addZeroAdvanceCheck = false;
                        break;
                    case Op.LINE_START:
                    case Op.LINE_START_M:
                    case Op.LINE_END:
                    case Op.LINE_END_M:
                    case Op.SET_I32:
                    case Op.SET_CHAR_POS:
                    case Op.WORD_BOUNDARY:
                    case Op.WORD_BOUNDARY_I:
                    case Op.NOT_WORD_BOUNDARY:
                    case Op.NOT_WORD_BOUNDARY_I:
                    case Op.PREV:
                    case Op.SAVE_START:
                    case Op.SAVE_END:
                    case Op.SAVE_RESET:
                        break;
                    case Op.BACK_REFERENCE:
                    case Op.BACK_REFERENCE_I:
                    case Op.BACKWARD_BACK_REFERENCE:
                    case Op.BACKWARD_BACK_REFERENCE_I:
                        needCaptureInit = true;
                        break;
                    default:
                        needCaptureInit = true;
                        break analysis;
                }
                pc += Op.size(code.a, pc);
            }
        }
        // reset the captures of the atom at each iteration when they may be stale
        if (needCaptureInit && lastCaptureCount != captureCount) {
            code.insert(lastAtomStart, 3);
            code.set(lastAtomStart, Op.SAVE_RESET);
            code.set(lastAtomStart + 1, lastCaptureCount);
            code.set(lastAtomStart + 2, captureCount - 1);
        }
        int len = code.size - lastAtomStart;
        if (quantMin == 0) {
            // the captures must be reset if the atom is skipped
            if (!needCaptureInit && lastCaptureCount != captureCount) {
                code.insert(lastAtomStart, 3);
                code.set(lastAtomStart++, Op.SAVE_RESET);
                code.set(lastAtomStart++, lastCaptureCount);
                code.set(lastAtomStart++, captureCount - 1);
            }
            int adv = addZeroAdvanceCheck ? 1 : 0;
            if (quantMax == 0) {
                code.size = lastAtomStart;
            } else if (quantMax == 1 || quantMax == INF) {
                boolean hasGoto = quantMax == INF;
                code.insert(lastAtomStart, 2 + adv * 2);
                code.set(lastAtomStart, greedy ? Op.SPLIT_NEXT_FIRST : Op.SPLIT_GOTO_FIRST);
                code.set(lastAtomStart + 1, len + (hasGoto ? 2 : 0) + adv * 4);
                if (addZeroAdvanceCheck) {
                    code.set(lastAtomStart + 2, Op.SET_CHAR_POS);
                    code.set(lastAtomStart + 3, 0);
                    emit(Op.CHECK_ADVANCE, 0);
                }
                if (hasGoto) emitJumpTo(Op.GOTO, lastAtomStart);
            } else {
                code.insert(lastAtomStart, 5 + adv * 2);
                int p = lastAtomStart;
                code.set(p++, greedy ? Op.SPLIT_NEXT_FIRST : Op.SPLIT_GOTO_FIRST);
                code.set(p++, 3 + adv * 2 + len + 4);
                code.set(p++, Op.SET_I32);
                code.set(p++, 0);
                code.set(p++, quantMax);
                lastAtomStart = p;
                if (addZeroAdvanceCheck) {
                    code.set(p++, Op.SET_CHAR_POS);
                    code.set(p, 0);
                }
                emitLoop(loopOp(addZeroAdvanceCheck, greedy), quantMax, lastAtomStart);
            }
        } else if (quantMin == 1 && quantMax == INF && !addZeroAdvanceCheck) {
            emitJumpTo(greedy ? Op.SPLIT_GOTO_FIRST : Op.SPLIT_NEXT_FIRST, lastAtomStart);
        } else {
            if (quantMin == quantMax) addZeroAdvanceCheck = false;
            int adv = addZeroAdvanceCheck ? 1 : 0;
            code.insert(lastAtomStart, 3 + adv * 2);
            int p = lastAtomStart;
            code.set(p++, Op.SET_I32);
            code.set(p++, 0);
            code.set(p++, quantMax);
            lastAtomStart = p;
            if (addZeroAdvanceCheck) {
                code.set(p++, Op.SET_CHAR_POS);
                code.set(p, 0);
            }
            if (quantMin == quantMax) {
                code.add(Op.LOOP);
                code.add(0);
                code.add(lastAtomStart - (code.size + 1));
            } else {
                emitLoop(loopOp(addZeroAdvanceCheck, greedy), quantMax - quantMin, lastAtomStart);
            }
        }
    }

    private int loopOp(boolean checkAdvance, boolean greedy) {
        if (checkAdvance) {
            return greedy ? Op.LOOP_CHECK_ADV_SPLIT_GOTO_FIRST : Op.LOOP_CHECK_ADV_SPLIT_NEXT_FIRST;
        }
        return greedy ? Op.LOOP_SPLIT_GOTO_FIRST : Op.LOOP_SPLIT_NEXT_FIRST;
    }

    private void emitLoop(int op, int limit, int target) {
        code.add(op);
        code.add(0);
        code.add(limit);
        code.add(target - (code.size + 1));
    }

    // ------------------------------------------------------------------ escapes and class atoms

    /**
     * Parses an escape sequence whose letter is at {@code p0} (after the backslash). {@code allowUtf16}: 0 disallows
     * braced Unicode escapes, 1 allows them, and 2 selects Unicode mode (braced escapes, combined surrogate pairs,
     * no legacy octal). Returns the code point (and sets {@link #escEnd}), -1 if malformed, -2 if not a
     * character escape.
     */
    private int parseEscapeAt(int p0, int allowUtf16) {
        int p = p0;
        int c = ch(p++);
        switch (c) {
            case 'b':
                c = 8;
                break;
            case 'f':
                c = 12;
                break;
            case 'n':
                c = 10;
                break;
            case 'r':
                c = 13;
                break;
            case 't':
                c = 9;
                break;
            case 'v':
                c = 11;
                break;
            case 'x': {
                int h0 = hexVal(ch(p));
                int h1 = hexVal(ch(p + 1));
                if (h0 < 0 || h1 < 0) return -1;
                c = h0 * 16 + h1;
                p += 2;
                break;
            }
            case 'u':
                if (ch(p) == '{' && allowUtf16 != 0) {
                    p++;
                    c = 0;
                    while (true) {
                        int h = hexVal(ch(p));
                        if (h < 0) return -1;
                        c = c * 16 + h;
                        if (c > 0x10FFFF) return -1;
                        p++;
                        if (ch(p) == '}') break;
                    }
                    p++;
                } else {
                    c = 0;
                    for (int i = 0; i < 4; i++) {
                        int h = hexVal(ch(p));
                        if (h < 0) return -1;
                        c = c * 16 + h;
                        p++;
                    }
                    if (c >= 0xD800 && c <= 0xDBFF && allowUtf16 == 2
                            && ch(p) == '\\' && ch(p + 1) == 'u') {
                        int c1 = 0;
                        int i = 0;
                        while (i < 4) {
                            int h = hexVal(ch(p + 2 + i));
                            if (h < 0) break;
                            c1 = c1 * 16 + h;
                            i++;
                        }
                        if (i == 4 && c1 >= 0xDC00 && c1 <= 0xDFFF) {
                            p += 6;
                            c = Character.toCodePoint((char) c, (char) c1);
                        }
                    }
                }
                break;
            default:
                if (c >= '0' && c <= '7') {
                    c -= '0';
                    if (allowUtf16 == 2) {
                        if (c != 0 || isDigit(ch(p))) return -1;
                    } else {
                        // legacy octal sequence
                        int v = ch(p) - '0';
                        if (v >= 0 && v <= 7) {
                            c = c * 8 + v;
                            p++;
                            if (c < 32) {
                                v = ch(p) - '0';
                                if (v >= 0 && v <= 7) {
                                    c = c * 8 + v;
                                    p++;
                                }
                            }
                        }
                    }
                } else {
                    return -2;
                }
                break;
        }
        escEnd = p;
        return c;
    }

    private int readSourceChar() {
        int c = src.charAt(pos++);
        if (isUnicode && c >= 0xD800 && c <= 0xDBFF && pos < end) {
            int d = src.charAt(pos);
            if (d >= 0xDC00 && d <= 0xDFFF) {
                pos++;
                return Character.toCodePoint((char) c, (char) d);
            }
        }
        return c;
    }

    /**
     * Parses a class atom (or a pattern character when {@code !inClass}). Returns the code point, or {@link #CLASS}
     * with the set in {@link #atomClass} for class escapes ({@code \d}, {@code \p{..}}, {@code \q{..}}...) when
     * {@code allowClass}.
     */
    private int getClassAtom(boolean inClass, boolean allowClass) {
        int c = ch(pos);
        if (c == '\\') {
            if (pos + 1 >= end) throw error("\\ at end of pattern");
            int e = src.charAt(pos + 1);
            pos += 2;
            if (e == 'd' || e == 'D' || e == 's' || e == 'S' || e == 'w' || e == 'W') {
                if (allowClass) {
                    atomClass = new ClassSet(classEscape(e));
                    atomClassEscape = e;
                    return CLASS;
                }
            } else if (e == 'c') {
                int d = ch(pos);
                if (isAsciiLetter(d) || ((isDigit(d) || d == '_') && inClass && !isUnicode)) {
                    pos++;
                    return d & 0x1f;
                }
                if (isUnicode) throw error("invalid escape");
                // Annex B: '\' is a literal character, 'c' is parsed next
                pos--;
                return '\\';
            } else if (e == '-') {
                if (!inClass && isUnicode) throw error("invalid escape");
                return e;
            } else if (e == '&' || e == '!' || e == '#' || e == '%' || e == ',' || e == ':'
                    || e == ';' || e == '<' || e == '=' || e == '>' || e == '@' || e == '`'
                    || e == '~') {
                if (isUnicode && (!inClass || !unicodeSets)) throw error("invalid escape");
                return e;
            } else if (e == '^' || e == '$' || e == '\\' || e == '.' || e == '*' || e == '+'
                    || e == '?' || e == '(' || e == ')' || e == '[' || e == ']' || e == '{'
                    || e == '}' || e == '|' || e == '/') {
                return e;
            } else if (e == 'p' || e == 'P') {
                if (isUnicode && allowClass) {
                    atomClass = parseUnicodeProperty(e == 'P');
                    atomClassEscape = e;
                    return CLASS;
                }
            } else if (e == 'q') {
                if (unicodeSets && allowClass && inClass) {
                    atomClass = parseClassStringDisjunction();
                    atomClassEscape = e;
                    return CLASS;
                }
            }
            int r = parseEscapeAt(pos - 1, isUnicode ? 2 : 0);
            if (r >= 0) {
                pos = escEnd;
                return r;
            }
            if (isUnicode) throw error("invalid escape");
            if (e == 'k' && hasNamedGroups()) throw error("invalid escape");
            // Annex B identity escape: the character after the backslash
            pos--;
            return readSourceChar();
        }
        if (c < 0) throw error(inClass ? "unterminated character class" : "unexpected end");
        if (inClass && unicodeSets) {
            if (c == '&' || c == '!' || c == '#' || c == '$' || c == '%' || c == '*' || c == '+'
                    || c == ',' || c == '.' || c == ':' || c == ';' || c == '<' || c == '='
                    || c == '>' || c == '?' || c == '@' || c == '^' || c == '`' || c == '~') {
                if (ch(pos + 1) == c) throw error("invalid set operation in character class");
            } else if (c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}'
                    || c == '/' || c == '-' || c == '|') {
                throw error("invalid character in character class");
            }
        }
        return readSourceChar();
    }

    private int[] wordChars() {
        int[] set = BASIC_WORD;
        if (isUnicode && ignoreCase) set = CharRanges.INSTANCE.union(set, extraWord());
        return set;
    }

    /** Set of a {@code \d \D \s \S \w \W} escape (case folded in {@code v} mode when ignoring case). */
    private int[] classEscape(int e) {
        if (e == 'd') return DIGITS;
        if (e == 'D') return CharRanges.INSTANCE.invert(DIGITS);
        if (e == 's') return spaceSet;
        if (e == 'S') return CharRanges.INSTANCE.invert(spaceSet);
        if (e == 'w') return maybeFold(wordChars());
        return CharRanges.INSTANCE.invert(maybeFold(wordChars()));
    }

    /** MaybeSimpleCaseFolding: folds the set in {@code v} mode when ignoring case. */
    private int[] maybeFold(int[] set) {
        return unicodeSets && ignoreCase ? UnicodeTables.canonicalizeSet(set, true) : set;
    }

    private ClassSet maybeFold(ClassSet classSet) {
        if (!(unicodeSets && ignoreCase)) return classSet;
        classSet.chars = UnicodeTables.canonicalizeSet(classSet.chars, true);
        HashSet<String> strings = classSet.strings;
        if (strings != null) {
            HashSet<String> folded = new HashSet<>();
            for (String s : strings) {
                StringBuilder builder = new StringBuilder(s.length());
                int i = 0;
                while (i < s.length()) {
                    int cp = s.codePointAt(i);
                    builder.appendCodePoint(UnicodeTables.canonicalize(cp, true));
                    i += Character.charCount(cp);
                }
                folded.add(builder.toString());
            }
            classSet.strings = folded;
        }
        return classSet;
    }

    private ClassSet parseUnicodeProperty(boolean negate) {
        if (ch(pos) != '{') throw error("invalid property name");
        pos++;
        int nameStart = pos;
        while (isPropertyChar(ch(pos))) pos++;
        String name = src.substring(nameStart, pos);
        String value = null;
        if (ch(pos) == '=') {
            pos++;
            int valueStart = pos;
            while (isPropertyChar(ch(pos))) pos++;
            value = src.substring(valueStart, pos);
        }
        if (ch(pos) != '}') throw error("invalid property name");
        pos++;
        int[] chars;
        if (value != null) {
            if ("General_Category".equals(name) || "gc".equals(name)) {
                chars = UnicodeTables.generalCategory(value);
            } else if ("Script".equals(name) || "sc".equals(name)) {
                chars = UnicodeTables.script(value, false);
            } else if ("Script_Extensions".equals(name) || "scx".equals(name)) {
                chars = UnicodeTables.script(value, true);
            } else {
                chars = null;
            }
            if (chars == null) throw error("invalid property name");
        } else {
            chars = UnicodeTables.generalCategory(name);
            if (chars == null) chars = UnicodeTables.binaryProperty(name);
            if (chars == null) {
                UnicodeTables.StringProperty property = unicodeSets ? UnicodeTables.stringProperty(name) : null;
                if (property == null || negate) throw error("invalid property name");
                ClassSet classSet = new ClassSet(property.chars);
                HashSet<String> strings = new HashSet<>(property.strings.size() * 2);
                for (int[] string : property.strings) {
                    strings.add(new String(string, 0, string.length));
                }
                classSet.strings = strings;
                classSet.mayContainStrings = true;
                return maybeFold(classSet);
            }
        }
        int[] set = maybeFold(chars);
        if (negate) set = CharRanges.INSTANCE.invert(set);
        return new ClassSet(set);
    }

    /** {@code \q{...}} (after the 'q'). */
    private ClassSet parseClassStringDisjunction() {
        if (ch(pos) != '{') throw error("expecting '{' after \\q");
        pos++;
        CharRanges.Builder chars = new CharRanges.Builder();
        HashSet<String> strings = new HashSet<>();
        boolean mayContainStrings = false;
        while (true) {
            StringBuilder builder = new StringBuilder();
            int n = 0;
            int last = 0;
            while (ch(pos) != '}' && ch(pos) != '|') {
                last = getClassAtom(true, false);
                builder.appendCodePoint(last);
                n++;
            }
            if (n == 1) {
                chars.add(last);
            } else {
                strings.add(builder.toString());
                mayContainStrings = true;
            }
            if (ch(pos) == '}') break;
            pos++;
        }
        pos++;
        ClassSet classSet = new ClassSet(chars.build());
        if (!strings.isEmpty()) classSet.strings = strings;
        classSet.mayContainStrings = mayContainStrings;
        return maybeFold(classSet);
    }

    // ------------------------------------------------------------------ character classes

    /** {@code [...]} without the {@code v} flag (NonemptyClassRanges with Annex B extensions). */
    private ClassSet parseClassLegacy() {
        pos++;
        boolean invert = ch(pos) == '^';
        if (invert) pos++;
        CharRanges.Builder builder = new CharRanges.Builder();
        while (ch(pos) != ']') {
            int c1 = getClassAtom(true, true);
            int[] set1 = c1 == CLASS ? atomClass.chars : null;
            if (ch(pos) == '-' && ch(pos + 1) != ']') {
                if (set1 != null) {
                    if (isUnicode) throw error("invalid character class range");
                    builder.addSet(set1); // Annex B: '-' is parsed as a character next
                    continue;
                }
                int save = pos;
                pos++;
                int c2 = getClassAtom(true, true);
                if (c2 == CLASS) {
                    if (isUnicode) throw error("invalid character class range");
                    pos = save; // Annex B: '-' is parsed as a character next
                    builder.add(c1);
                    continue;
                }
                if (c2 < c1) throw error("range out of order in character class");
                builder.addRange(c1, c2);
            } else if (set1 != null) {
                builder.addSet(set1);
            } else {
                builder.add(c1);
            }
        }
        pos++;
        int[] set = builder.build();
        if (ignoreCase) set = UnicodeTables.canonicalizeSet(set, isUnicode);
        if (invert) set = CharRanges.INSTANCE.invert(set);
        return new ClassSet(set);
    }

    /** {@code [...]} with the {@code v} flag (ClassSetExpression), also used for nested classes. */
    private ClassSet parseClassV() {
        enterNesting();
        pos++;
        boolean invert = ch(pos) == '^';
        if (invert) pos++;
        ClassSet acc = new ClassSet(CharRanges.EMPTY);
        boolean isFirst = true;
        while (ch(pos) != ']') {
            ClassSet operand;
            if (ch(pos) == '[') {
                operand = parseClassV();
            } else {
                int c1 = getClassAtom(true, true);
                if (ch(pos) == '-' && ch(pos + 1) != ']'
                        && !(ch(pos + 1) == '-' && isFirst)) {
                    if (c1 == CLASS) throw error("invalid character class range");
                    pos++;
                    int c2 = getClassAtom(true, true);
                    if (c2 == CLASS) throw error("invalid character class range");
                    if (c2 < c1) throw error("range out of order in character class");
                    acc = setOp(acc, new ClassSet(maybeFold(CharRanges.INSTANCE.of(c1, c2))),
                            CharRanges.OP_UNION);
                    isFirst = false;
                    continue;
                }
                operand = c1 == CLASS
                        ? atomClass : new ClassSet(maybeFold(CharRanges.INSTANCE.single(c1)));
            }
            acc = setOp(acc, operand, CharRanges.OP_UNION);
            acc.mayContainStrings = acc.mayContainStrings || operand.mayContainStrings;
            if (isFirst) {
                if (ch(pos) == '&' && ch(pos + 1) == '&' && ch(pos + 2) != '&') {
                    boolean may = acc.mayContainStrings;
                    while (ch(pos) != ']') {
                        if (ch(pos) == '&' && ch(pos + 1) == '&' && ch(pos + 2) != '&') {
                            pos += 2;
                        } else {
                            throw error("invalid set operation in character class");
                        }
                        ClassSet op = parseClassSetOperand();
                        acc = setOp(acc, op, CharRanges.OP_INTER);
                        may = may && op.mayContainStrings;
                    }
                    acc.mayContainStrings = may;
                } else if (ch(pos) == '-' && ch(pos + 1) == '-') {
                    boolean may = acc.mayContainStrings;
                    while (ch(pos) != ']') {
                        if (ch(pos) == '-' && ch(pos + 1) == '-') {
                            pos += 2;
                        } else {
                            throw error("invalid set operation in character class");
                        }
                        acc = setOp(acc, parseClassSetOperand(), CharRanges.OP_SUB);
                    }
                    acc.mayContainStrings = may;
                }
            }
            isFirst = false;
        }
        pos++;
        depth--;
        if (invert) {
            if (acc.mayContainStrings) throw error("negated character class may contain strings");
            return new ClassSet(CharRanges.INSTANCE.invert(acc.chars));
        }
        return acc;
    }

    private ClassSet parseClassSetOperand() {
        if (ch(pos) == '[') return parseClassV();
        int c = getClassAtom(true, true);
        if (c == CLASS) return atomClass;
        return new ClassSet(maybeFold(CharRanges.INSTANCE.single(c)));
    }

    private ClassSet setOp(ClassSet a, ClassSet b, int op) {
        ClassSet result = new ClassSet(CharRanges.INSTANCE.op(a.chars, b.chars, op));
        HashSet<String> aStrings = a.strings;
        HashSet<String> bStrings = b.strings;
        result.mayContainStrings = a.mayContainStrings;
        if (op == CharRanges.OP_UNION) {
            if (aStrings == null) {
                result.strings = bStrings == null ? null : new HashSet<>(bStrings);
            } else if (bStrings == null) {
                result.strings = aStrings;
            } else {
                aStrings.addAll(bStrings);
                result.strings = aStrings;
            }
        } else if (op == CharRanges.OP_INTER) {
            if (aStrings == null || bStrings == null) {
                result.strings = null;
            } else {
                aStrings.retainAll(bStrings);
                result.strings = aStrings;
            }
        } else {
            if (aStrings == null || bStrings == null) {
                result.strings = aStrings;
            } else {
                aStrings.removeAll(bStrings);
                result.strings = aStrings;
            }
        }
        return result;
    }

    /** Emits a matcher for a class value (alternatives longest first when it contains strings). */
    private void emitClassSet(ClassSet classSet, boolean backward) {
        HashSet<String> strings = classSet.strings;
        if (strings == null || strings.isEmpty()) {
            emitSingleSet(classSet.chars, backward);
            return;
        }
        TreeMap<Integer, HashSet<String>> byLength = new TreeMap<>(Comparator.reverseOrder());
        boolean hasEmpty = false;
        for (String s : strings) {
            int n = s.codePointCount(0, s.length());
            if (n == 0) {
                hasEmpty = true;
            } else {
                byLength.computeIfAbsent(n, key -> new HashSet<>()).add(s);
            }
        }
        ArrayList<Integer> gotoSlots = new ArrayList<>();
        int gi = 0;
        for (Map.Entry<Integer, HashSet<String>> entry : byLength.entrySet()) {
            int length = entry.getKey();
            HashSet<String> group = entry.getValue();
            boolean isLast = !hasEmpty && classSet.chars.length == 0 && gi == byLength.size() - 1;
            int split = !isLast ? emitJump(Op.SPLIT_NEXT_FIRST) : -1;
            stringGroups.add(new StringGroup(length, group, ignoreCase, backward));
            emit(Op.STRING_SET, stringGroups.size() - 1);
            if (!isLast) {
                gotoSlots.add(emitJump(Op.GOTO));
                patch(split);
            }
            gi++;
        }
        if (classSet.chars.length != 0) {
            boolean isLast = !hasEmpty;
            int split = !isLast ? emitJump(Op.SPLIT_NEXT_FIRST) : -1;
            emitSingleSet(classSet.chars, backward);
            if (!isLast) patch(split);
        }
        for (int slot : gotoSlots) patch(slot);
    }

    private void emitSingleSet(int[] set, boolean backward) {
        if (backward) emit(Op.PREV);
        if (set.length == 0) {
            emit(Op.CHAR, -1); // never matches
        } else if (set.length == 2 && set[1] == set[0] + 1) {
            emit(ignoreCase ? Op.CHAR_I : Op.CHAR, set[0]);
        } else {
            int n = set.length / 2;
            emit(ignoreCase ? Op.RANGE_I : Op.RANGE, n);
            int[] bitmap = new int[4];
            int i = 0;
            while (i < set.length && set[i] < 128) {
                for (int c = set[i]; c < Math.min(set[i + 1], 128); c++) {
                    bitmap[c >>> 5] |= 1 << (c & 31);
                }
                i += 2;
            }
            for (int word : bitmap) code.add(word);
            i = 0;
            while (i < set.length) {
                code.add(set[i]);
                code.add(set[i + 1] - 1);
                i += 2;
            }
        }
        if (backward) emit(Op.PREV);
    }

    // ------------------------------------------------------------------ group names

    /** Parses a group name starting at {@code start} (after '&lt;'); returns it and sets {@link #escEnd} after the '&gt;', or null. */
    private String parseGroupName(int start) {
        int p = start;
        StringBuilder builder = new StringBuilder();
        while (true) {
            int c = ch(p);
            if (c == '\\') {
                p++;
                if (ch(p) != 'u') return null;
                c = parseEscapeAt(p, 2);
                if (c < 0) return null;
                p = escEnd;
            } else if (c == '>') {
                break;
            } else if (c < 0) {
                return null;
            } else {
                p++;
                if (c >= 0xD800 && c <= 0xDBFF && p < end
                        && Character.isLowSurrogate(src.charAt(p))) {
                    c = Character.toCodePoint((char) c, src.charAt(p));
                    p++;
                }
            }
            if (builder.length() == 0) {
                if (!isIdentStart(c)) return null;
            } else if (!isIdentPart(c)) {
                return null;
            }
            builder.appendCodePoint(c);
        }
        if (builder.length() == 0) return null;
        escEnd = p + 1;
        return builder.toString();
    }

    /** Scans the whole pattern for capture groups (needed for forward references and {@code \N} disambiguation). */
    private void prescan() {
        if (prescanned) return;
        prescanned = true;
        int savedEscEnd = escEnd;
        prescanNames.add(null);
        int i = 0;
        int classDepth = 0;
        while (i < end) {
            char c = src.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '[') {
                if (classDepth == 0 || unicodeSets) classDepth++;
            } else if (c == ']') {
                if (classDepth > 0) classDepth--;
            } else if (c == '(' && classDepth == 0) {
                if (ch(i + 1) == '?') {
                    if (ch(i + 2) == '<' && ch(i + 3) != '=' && ch(i + 3) != '!') {
                        prescanHasNames = true;
                        prescanNames.add(parseGroupName(i + 3));
                    }
                } else {
                    prescanNames.add(null);
                }
            }
            i++;
        }
        escEnd = savedEscEnd;
    }

    private boolean hasNamedGroups() {
        prescan();
        return prescanHasNames;
    }

    /** Total number of capture groups in the pattern + 1. */
    private int totalCaptureCount() {
        prescan();
        return prescanNames.size();
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiLetter(int c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static boolean isPropertyChar(int c) {
        return isAsciiLetter(c) || isDigit(c) || c == '_';
    }

    private static int hexVal(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static boolean isIdentStart(int c) {
        return c < 128
                ? isAsciiLetter(c) || c == '$' || c == '_'
                : UnicodeTables.isIdStart(c);
    }

    private static boolean isIdentPart(int c) {
        return c < 128
                ? isAsciiLetter(c) || isDigit(c) || c == '$' || c == '_'
                : c == 0x200C || c == 0x200D || UnicodeTables.isIdContinue(c);
    }

    private static int[] extraWord() {
        return ExtraWordHolder.VALUE;
    }

    private static int[] computeExtraWord() {
        CharRanges.Builder builder = new CharRanges.Builder();
        UnicodeTables.CaseMap map = UnicodeTables.getSimpleFolding();
        for (int i = 0; i < map.keys.length; i++) {
            if (CharRanges.contains(BASIC_WORD, map.values[i])
                    && !CharRanges.contains(BASIC_WORD, map.keys[i])) {
                builder.add(map.keys[i]);
            }
        }
        return builder.build();
    }

    private static final class ExtraWordHolder {
        static final int[] VALUE = computeExtraWord();
    }

    /** Compiles {@code pattern} with the given flag bits; throws {@link RegExpSyntaxError}. */
    static RegExpProgram compile(String pattern, int flags) {
        return new RegExpCompiler(pattern, flags).compile();
    }
}
