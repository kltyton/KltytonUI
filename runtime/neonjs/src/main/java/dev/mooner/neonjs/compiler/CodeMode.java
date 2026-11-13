/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

/** The compilation mode of the analyzed code. */
public enum CodeMode {
    SCRIPT, MODULE, EVAL_DIRECT, EVAL_INDIRECT, FUNCTION_CTOR
}
