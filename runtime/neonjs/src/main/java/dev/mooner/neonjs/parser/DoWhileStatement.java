/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class DoWhileStatement extends Node {
    public final Node body;
    public final Node test;

    public DoWhileStatement(Node body, Node test) {
        this.body = body;
        this.test = test;
    }

    public Node getBody() {
        return body;
    }

    public Node getTest() {
        return test;
    }
}
