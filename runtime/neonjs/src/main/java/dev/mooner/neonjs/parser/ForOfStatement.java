/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class ForOfStatement extends Node {
    public final Node left;
    public final Node right;
    public final Node body;
    public final boolean isAwait;

    public ForOfStatement(Node left, Node right, Node body, boolean isAwait) {
        this.left = left;
        this.right = right;
        this.body = body;
        this.isAwait = isAwait;
    }

    public Node getLeft() {
        return left;
    }

    public Node getRight() {
        return right;
    }

    public Node getBody() {
        return body;
    }

    public boolean isAwait() {
        return isAwait;
    }
}
