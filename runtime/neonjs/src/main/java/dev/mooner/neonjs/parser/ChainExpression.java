/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

/** Wraps an optional chain (a?.b.c) so short-circuiting covers the whole chain. */
public class ChainExpression extends Node {
    public final Node expression;

    public ChainExpression(Node expression) {
        this.expression = expression;
    }

    public Node getExpression() {
        return expression;
    }
}
