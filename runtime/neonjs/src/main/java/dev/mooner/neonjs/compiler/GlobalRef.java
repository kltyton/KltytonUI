/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

/** A reference resolved through the global environment. */
public final class GlobalRef extends Resolved {
    public final String name;

    public GlobalRef(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
