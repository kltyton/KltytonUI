/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs;

/** How JS functions are executed. Derived from NeonJS and adapted to Java. */
public enum ExecutionMode {
    /** Bytecode interpreter only (fast startup, no class generation). */
    INTERPRETER,
    /** Functions are compiled to JVM bytecode before their first call. */
    COMPILED,
    /**
     * Interpret first; compile hot functions to JVM bytecode after
     * {@link NeonEngine.Builder#jitThreshold} calls (in the background unless
     * {@link NeonEngine.Builder#backgroundCompilation} is off: the function stays interpreted meanwhile).
     */
    ADAPTIVE
}
