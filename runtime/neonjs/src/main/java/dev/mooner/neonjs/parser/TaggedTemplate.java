/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class TaggedTemplate extends Node {
    public final Node tag;
    public final TemplateLiteral quasi;

    public TaggedTemplate(Node tag, TemplateLiteral quasi) {
        this.tag = tag;
        this.quasi = quasi;
    }

    public Node getTag() {
        return tag;
    }

    public TemplateLiteral getQuasi() {
        return quasi;
    }
}
