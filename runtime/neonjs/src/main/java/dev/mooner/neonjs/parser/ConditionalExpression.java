/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class ConditionalExpression extends Node {
    public final Node test;
    public final Node consequent;
    public final Node alternate;

    public ConditionalExpression(Node test, Node consequent, Node alternate) {
        this.test = test;
        this.consequent = consequent;
        this.alternate = alternate;
    }

    public Node getTest() {
        return test;
    }

    public Node getConsequent() {
        return consequent;
    }

    public Node getAlternate() {
        return alternate;
    }
}
