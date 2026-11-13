/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class ForStatement extends Node {
    public final Node init;
    public final Node test;
    public final Node update;
    public final Node body;

    public ForStatement(Node init, Node test, Node update, Node body) {
        this.init = init;
        this.test = test;
        this.update = update;
        this.body = body;
    }

    public Node getInit() {
        return init;
    }

    public Node getTest() {
        return test;
    }

    public Node getUpdate() {
        return update;
    }

    public Node getBody() {
        return body;
    }
}
