/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class TemplateElement extends Node {
    public final String cooked;
    public final String raw;

    public TemplateElement(String cooked, String raw) {
        this.cooked = cooked;
        this.raw = raw;
    }

    public String getCooked() {
        return cooked;
    }

    public String getRaw() {
        return raw;
    }
}
