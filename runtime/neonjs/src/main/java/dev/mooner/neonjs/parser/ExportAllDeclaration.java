/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.List;

public class ExportAllDeclaration extends Node {
    public final String exported;
    public final String source;
    public final List<ImportAttribute> attributes;

    public ExportAllDeclaration(String exported, String source,
                                List<ImportAttribute> attributes) {
        this.exported = exported;
        this.source = source;
        this.attributes = attributes;
    }

    public String getExported() {
        return exported;
    }

    public String getSource() {
        return source;
    }

    public List<ImportAttribute> getAttributes() {
        return attributes;
    }
}
