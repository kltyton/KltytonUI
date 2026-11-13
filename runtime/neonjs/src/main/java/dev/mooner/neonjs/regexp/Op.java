/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.regexp;

/**
 * Opcodes of the backtracking regular expression matcher (design after QuickJS libregexp).
 *
 * <p>Instructions are stored in an IntArray: the opcode followed by its operands. Jump offsets are
 * always the last operand of an instruction and are relative to the end of that instruction.
 */
final class Op {
    static final int CHAR = 1;
    static final int CHAR_I = 2;
    static final int DOT = 3;
    static final int ANY = 4;
    static final int SPACE = 5;
    static final int NOT_SPACE = 6;
    static final int LINE_START = 7;
    static final int LINE_START_M = 8;
    static final int LINE_END = 9;
    static final int LINE_END_M = 10;
    static final int GOTO = 11;
    static final int SPLIT_GOTO_FIRST = 12;
    static final int SPLIT_NEXT_FIRST = 13;
    static final int MATCH = 14;
    static final int LOOKAHEAD_MATCH = 15;
    static final int NEGATIVE_LOOKAHEAD_MATCH = 16;
    static final int SAVE_START = 17;
    static final int SAVE_END = 18;
    static final int SAVE_RESET = 19;
    static final int LOOP = 20;
    static final int LOOP_SPLIT_GOTO_FIRST = 21;
    static final int LOOP_SPLIT_NEXT_FIRST = 22;
    static final int LOOP_CHECK_ADV_SPLIT_GOTO_FIRST = 23;
    static final int LOOP_CHECK_ADV_SPLIT_NEXT_FIRST = 24;
    static final int SET_I32 = 25;
    static final int WORD_BOUNDARY = 26;
    static final int WORD_BOUNDARY_I = 27;
    static final int NOT_WORD_BOUNDARY = 28;
    static final int NOT_WORD_BOUNDARY_I = 29;
    static final int BACK_REFERENCE = 30;
    static final int BACK_REFERENCE_I = 31;
    static final int BACKWARD_BACK_REFERENCE = 32;
    static final int BACKWARD_BACK_REFERENCE_I = 33;
    static final int RANGE = 34;
    static final int RANGE_I = 35;
    static final int LOOKAHEAD = 36;
    static final int NEGATIVE_LOOKAHEAD = 37;
    static final int SET_CHAR_POS = 38;
    static final int CHECK_ADVANCE = 39;
    static final int PREV = 40;
    static final int STRING_SET = 41;

    private Op() {
    }

    /** Size in ints of the instruction at {@code pc}. */
    static int size(int[] code, int pc) {
        switch (code[pc]) {
            case CHAR:
            case CHAR_I:
            case GOTO:
            case SPLIT_GOTO_FIRST:
            case SPLIT_NEXT_FIRST:
            case SAVE_START:
            case SAVE_END:
            case LOOKAHEAD:
            case NEGATIVE_LOOKAHEAD:
            case SET_CHAR_POS:
            case CHECK_ADVANCE:
            case STRING_SET:
                return 2;
            case SAVE_RESET:
            case LOOP:
            case SET_I32:
                return 3;
            case LOOP_SPLIT_GOTO_FIRST:
            case LOOP_SPLIT_NEXT_FIRST:
            case LOOP_CHECK_ADV_SPLIT_GOTO_FIRST:
            case LOOP_CHECK_ADV_SPLIT_NEXT_FIRST:
                return 4;
            case BACK_REFERENCE:
            case BACK_REFERENCE_I:
            case BACKWARD_BACK_REFERENCE:
            case BACKWARD_BACK_REFERENCE_I:
                return 2 + code[pc + 1];
            case RANGE:
            case RANGE_I:
                return 6 + 2 * code[pc + 1];
            default:
                return 1;
        }
    }
}
