/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class ContinueStatement extends Node {
    public final String label;

    public ContinueStatement(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
