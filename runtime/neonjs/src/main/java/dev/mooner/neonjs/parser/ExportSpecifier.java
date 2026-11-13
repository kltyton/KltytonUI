/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class ExportSpecifier extends Node {
    public final String local;
    public final String exported;
    public final boolean localIsString;

    public ExportSpecifier(String local, String exported, boolean localIsString) {
        this.local = local;
        this.exported = exported;
        this.localIsString = localIsString;
    }

    public ExportSpecifier(String local, String exported) {
        this(local, exported, false);
    }

    public String getLocal() {
        return local;
    }

    public String getExported() {
        return exported;
    }

    public boolean getLocalIsString() {
        return localIsString;
    }
}
