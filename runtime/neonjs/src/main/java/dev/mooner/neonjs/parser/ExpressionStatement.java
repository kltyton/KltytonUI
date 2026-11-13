/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class ExpressionStatement extends Node {
    public final Node expression;
    public String directive = null;

    public ExpressionStatement(Node expression) {
        this.expression = expression;
    }

    public Node getExpression() {
        return expression;
    }

    public String getDirective() {
        return directive;
    }

    public void setDirective(String directive) {
        this.directive = directive;
    }
}
