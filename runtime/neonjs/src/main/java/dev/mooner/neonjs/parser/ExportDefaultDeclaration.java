/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

/** declaration is a FunctionDeclaration, ClassDeclaration, or an expression. */
public class ExportDefaultDeclaration extends Node {
    public final Node declaration;

    public ExportDefaultDeclaration(Node declaration) {
        this.declaration = declaration;
    }

    public Node getDeclaration() {
        return declaration;
    }
}
