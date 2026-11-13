/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.List;

public class ExportNamedDeclaration extends Node {
    public final Node declaration;
    public final List<ExportSpecifier> specifiers;
    public final String source;
    public final List<ImportAttribute> attributes;

    public ExportNamedDeclaration(Node declaration, List<ExportSpecifier> specifiers,
                                  String source, List<ImportAttribute> attributes) {
        this.declaration = declaration;
        this.specifiers = specifiers;
        this.source = source;
        this.attributes = attributes;
    }

    public Node getDeclaration() {
        return declaration;
    }

    public List<ExportSpecifier> getSpecifiers() {
        return specifiers;
    }

    public String getSource() {
        return source;
    }

    public List<ImportAttribute> getAttributes() {
        return attributes;
    }
}
