/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.List;

public class SwitchCase extends Node {
    public final Node test;
    public final List<Node> consequent;

    public SwitchCase(Node test, List<Node> consequent) {
        this.test = test;
        this.consequent = consequent;
    }

    public Node getTest() {
        return test;
    }

    public List<Node> getConsequent() {
        return consequent;
    }
}
