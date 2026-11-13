/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public enum FunctionKind {
    NORMAL,
    ARROW,
    METHOD,
    GETTER,
    SETTER,
    CLASS_CONSTRUCTOR,
    DERIVED_CONSTRUCTOR,
    /** Synthetic function evaluating class field initializers (and static blocks). */
    CLASS_FIELD_INIT,
    STATIC_BLOCK
}
