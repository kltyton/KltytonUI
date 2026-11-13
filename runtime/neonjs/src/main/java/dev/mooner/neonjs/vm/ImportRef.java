/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.DeclEnv;

/** Live binding to an exported variable of another module (stored in import binding slots). */
public final class ImportRef {
    public final DeclEnv env;
    public final int slot;
    public final String name;

    public ImportRef(DeclEnv env, int slot, String name) {
        this.env = env;
        this.slot = slot;
        this.name = name;
    }
}
