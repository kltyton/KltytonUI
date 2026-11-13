/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class WithStatement extends Node {
    public final Node obj;
    public final Node body;

    public WithStatement(Node obj, Node body) {
        this.obj = obj;
        this.body = body;
    }

    public Node getObj() {
        return obj;
    }

    public Node getBody() {
        return body;
    }
}
