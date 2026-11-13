/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class YieldExpression extends Node {
    public final Node argument;
    public final boolean delegate;

    public YieldExpression(Node argument, boolean delegate) {
        this.argument = argument;
        this.delegate = delegate;
    }

    public Node getArgument() {
        return argument;
    }

    public boolean getDelegate() {
        return delegate;
    }
}
