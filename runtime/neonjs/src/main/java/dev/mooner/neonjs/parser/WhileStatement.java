/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class WhileStatement extends Node {
    public final Node test;
    public final Node body;

    public WhileStatement(Node test, Node body) {
        this.test = test;
        this.body = body;
    }

    public Node getTest() {
        return test;
    }

    public Node getBody() {
        return body;
    }
}
