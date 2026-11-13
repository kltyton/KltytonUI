/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

/** The kind of declarative environment a scope represents. */
public enum ScopeKind {
    GLOBAL, EVAL, MODULE, FUNCTION, BODY, CALLEE, BLOCK, CATCH, FOR, SWITCH, WITH, CLASS
}
