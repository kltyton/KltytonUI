/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class LabeledStatement extends Node {
    public final String label;
    public final Node body;

    public LabeledStatement(String label, Node body) {
        this.label = label;
        this.body = body;
    }

    public String getLabel() {
        return label;
    }

    public Node getBody() {
        return body;
    }
}
