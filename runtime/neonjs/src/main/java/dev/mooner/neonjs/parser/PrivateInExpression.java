/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

/** {@code #x in obj} */
public class PrivateInExpression extends Node {
    public final PrivateIdentifier name;
    public final Node right;

    public PrivateInExpression(PrivateIdentifier name, Node right) {
        this.name = name;
        this.right = right;
    }

    public PrivateIdentifier getName() {
        return name;
    }

    public Node getRight() {
        return right;
    }
}
