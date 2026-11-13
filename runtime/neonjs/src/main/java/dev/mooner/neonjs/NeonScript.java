/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs;

import dev.mooner.neonjs.compiler.CodeBlock;

/** A script compiled once and runnable in any context of the same engine. */
public final class NeonScript {
    public final NeonEngine engine;
    final CodeBlock code;
    public final String name;

    NeonScript(NeonEngine engine, CodeBlock code, String name) {
        this.engine = engine;
        this.code = code;
        this.name = name;
    }

    public NeonEngine getEngine() {
        return engine;
    }

    public String getName() {
        return name;
    }
}
