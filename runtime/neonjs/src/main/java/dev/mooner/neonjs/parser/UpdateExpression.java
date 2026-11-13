/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class UpdateExpression extends Node {
    public final String op;
    public final boolean prefix;
    public final Node argument;

    public UpdateExpression(String op, boolean prefix, Node argument) {
        this.op = op;
        this.prefix = prefix;
        this.argument = argument;
    }

    public String getOp() {
        return op;
    }

    public boolean getPrefix() {
        return prefix;
    }

    public Node getArgument() {
        return argument;
    }
}
