/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class UnaryExpression extends Node {
    public final String op;
    public final Node argument;

    public UnaryExpression(String op, Node argument) {
        this.op = op;
        this.argument = argument;
    }

    public String getOp() {
        return op;
    }

    public Node getArgument() {
        return argument;
    }
}
