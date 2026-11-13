/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class AssignmentExpression extends Node {
    public final String op;
    public Node target;
    public final Node value;

    public AssignmentExpression(String op, Node target, Node value) {
        this.op = op;
        this.target = target;
        this.value = value;
    }

    public String getOp() {
        return op;
    }

    public Node getTarget() {
        return target;
    }

    public void setTarget(Node target) {
        this.target = target;
    }

    public Node getValue() {
        return value;
    }
}
