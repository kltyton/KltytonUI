/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class AwaitExpression extends Node {
    public final Node argument;

    public AwaitExpression(Node argument) {
        this.argument = argument;
    }

    public Node getArgument() {
        return argument;
    }
}
