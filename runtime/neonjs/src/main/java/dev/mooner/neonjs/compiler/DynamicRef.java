/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

/** A reference resolved at run time (eval variable environment or a with-object). */
public final class DynamicRef extends Resolved {
    public final String name;

    public DynamicRef(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
