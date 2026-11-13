/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class PrivateIdentifier extends Node {
    public final String name;

    public PrivateIdentifier(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
