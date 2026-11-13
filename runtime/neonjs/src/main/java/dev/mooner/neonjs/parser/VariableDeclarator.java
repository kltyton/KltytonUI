/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class VariableDeclarator extends Node {
    public final Node id;
    public final Node init;

    public VariableDeclarator(Node id, Node init) {
        this.id = id;
        this.init = init;
    }

    public Node getId() {
        return id;
    }

    public Node getInit() {
        return init;
    }
}
