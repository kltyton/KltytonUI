/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

/** The declaration kind of one binding. */
public enum BKind {
    VAR, FUNCTION, PARAM, LET, CONST, CLASS, CLASS_INNER, CALLEE, CATCH, IMPORT, BLOCK_FUNCTION, USING,
    THIS, NEW_TARGET, HOME, FN, ARGUMENTS, PRIVATE;

    public boolean isLexical() {
        return this == LET || this == CONST || this == CLASS || this == CLASS_INNER || this == USING;
    }

    public boolean isConst() {
        return this == CONST || this == CLASS_INNER || this == IMPORT || this == USING;
    }
}
