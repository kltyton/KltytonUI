/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

/** Object literal / object pattern property. In patterns, {@link #value} is the target pattern. */
public class Property extends Node {
    public Node key;
    public Node value;
    public final PropKind kind;
    public final boolean computed;
    public final boolean shorthand;
    public final boolean method;

    public Property(Node key, Node value, PropKind kind, boolean computed,
                    boolean shorthand, boolean method) {
        this.key = key;
        this.value = value;
        this.kind = kind;
        this.computed = computed;
        this.shorthand = shorthand;
        this.method = method;
    }

    public Node getKey() {
        return key;
    }

    public void setKey(Node key) {
        this.key = key;
    }

    public Node getValue() {
        return value;
    }

    public void setValue(Node value) {
        this.value = value;
    }

    public PropKind getKind() {
        return kind;
    }

    public boolean getComputed() {
        return computed;
    }

    public boolean getShorthand() {
        return shorthand;
    }

    public boolean getMethod() {
        return method;
    }
}
