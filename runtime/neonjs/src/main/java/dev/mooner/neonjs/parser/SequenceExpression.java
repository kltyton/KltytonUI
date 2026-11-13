/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.List;

public class SequenceExpression extends Node {
    public final List<Node> expressions;

    public SequenceExpression(List<Node> expressions) {
        this.expressions = expressions;
    }

    public List<Node> getExpressions() {
        return expressions;
    }
}
