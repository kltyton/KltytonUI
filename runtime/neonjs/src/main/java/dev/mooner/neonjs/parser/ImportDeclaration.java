/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.List;

public class ImportDeclaration extends Node {
    public final List<ImportSpecifier> specifiers;
    public final String source;
    public final List<ImportAttribute> attributes;
    public String phase = null;

    public ImportDeclaration(List<ImportSpecifier> specifiers, String source,
                             List<ImportAttribute> attributes) {
        this.specifiers = specifiers;
        this.source = source;
        this.attributes = attributes;
    }

    public List<ImportSpecifier> getSpecifiers() {
        return specifiers;
    }

    public String getSource() {
        return source;
    }

    public List<ImportAttribute> getAttributes() {
        return attributes;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }
}
