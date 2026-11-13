/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.List;

public class ArrayPattern extends Node {
    public final List<Node> elements;

    public ArrayPattern(List<Node> elements) {
        this.elements = elements;
    }

    public List<Node> getElements() {
        return elements;
    }
}
