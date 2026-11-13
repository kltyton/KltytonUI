/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class MemberExpression extends Node {
    public final Node obj;
    public final Node property;
    public final boolean computed;
    public final boolean optional;

    public MemberExpression(Node obj, Node property, boolean computed, boolean optional) {
        this.obj = obj;
        this.property = property;
        this.computed = computed;
        this.optional = optional;
    }

    public Node getObj() {
        return obj;
    }

    public Node getProperty() {
        return property;
    }

    public boolean getComputed() {
        return computed;
    }

    public boolean getOptional() {
        return optional;
    }
}
