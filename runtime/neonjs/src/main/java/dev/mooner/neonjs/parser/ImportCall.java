/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class ImportCall extends Node {
    public final Node source;
    public final Node options;
    public final String phase;

    public ImportCall(Node source, Node options, String phase) {
        this.source = source;
        this.options = options;
        this.phase = phase;
    }

    public ImportCall(Node source, Node options) {
        this(source, options, null);
    }

    public Node getSource() {
        return source;
    }

    public Node getOptions() {
        return options;
    }

    public String getPhase() {
        return phase;
    }
}
