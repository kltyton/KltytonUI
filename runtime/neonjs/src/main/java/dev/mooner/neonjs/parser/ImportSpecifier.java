/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

/** Import specifier: imported is "default", {@link ModuleNames#NAMESPACE} or a name/string. */
public class ImportSpecifier extends Node {
    public final String imported;
    public final Identifier local;

    public ImportSpecifier(String imported, Identifier local) {
        this.imported = imported;
        this.local = local;
    }

    public String getImported() {
        return imported;
    }

    public Identifier getLocal() {
        return local;
    }
}
