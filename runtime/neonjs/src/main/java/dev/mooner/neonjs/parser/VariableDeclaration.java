/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.List;

public class VariableDeclaration extends Node {
    public final VarKind kind;
    public final List<VariableDeclarator> declarations;

    public VariableDeclaration(VarKind kind, List<VariableDeclarator> declarations) {
        this.kind = kind;
        this.declarations = declarations;
    }

    public VarKind getKind() {
        return kind;
    }

    public List<VariableDeclarator> getDeclarations() {
        return declarations;
    }
}
