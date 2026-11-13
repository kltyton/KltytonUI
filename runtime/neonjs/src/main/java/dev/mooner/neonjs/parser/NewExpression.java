/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.List;

public class NewExpression extends Node {
    public final Node callee;
    public final List<Node> arguments;

    public NewExpression(Node callee, List<Node> arguments) {
        this.callee = callee;
        this.arguments = arguments;
    }

    public Node getCallee() {
        return callee;
    }

    public List<Node> getArguments() {
        return arguments;
    }
}
