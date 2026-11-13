/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.List;

public class ObjectLiteral extends Node {
    public final List<Node> properties;

    public ObjectLiteral(List<Node> properties) {
        this.properties = properties;
    }

    public List<Node> getProperties() {
        return properties;
    }
}
