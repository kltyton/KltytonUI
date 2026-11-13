/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.List;

public class CallExpression extends Node {
    public final Node callee;
    public final List<Node> arguments;
    public final boolean optional;

    public CallExpression(Node callee, List<Node> arguments, boolean optional) {
        this.callee = callee;
        this.arguments = arguments;
        this.optional = optional;
    }

    public Node getCallee() {
        return callee;
    }

    public List<Node> getArguments() {
        return arguments;
    }

    public boolean getOptional() {
        return optional;
    }
}
