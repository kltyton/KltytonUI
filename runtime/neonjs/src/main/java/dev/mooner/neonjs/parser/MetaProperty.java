/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class MetaProperty extends Node {
    public final String meta;
    public final String property;

    public MetaProperty(String meta, String property) {
        this.meta = meta;
        this.property = property;
    }

    public String getMeta() {
        return meta;
    }

    public String getProperty() {
        return property;
    }
}
