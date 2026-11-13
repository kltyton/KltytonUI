/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.Collections;
import java.util.List;

public class PropertyDefinition extends Node {
    public final Node key;
    public final Node value;
    public final boolean isStatic;
    public final boolean computed;
    /** Synthetic function wrapping the initializer (so `this`, `arguments` checks, scope work like a method). */
    public FunctionNode initializer = null;
    public boolean isAccessor = false;
    public List<Node> decorators = Collections.emptyList();

    public PropertyDefinition(Node key, Node value, boolean isStatic, boolean computed) {
        this.key = key;
        this.value = value;
        this.isStatic = isStatic;
        this.computed = computed;
    }

    public Node getKey() {
        return key;
    }

    public Node getValue() {
        return value;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public boolean getComputed() {
        return computed;
    }

    public FunctionNode getInitializer() {
        return initializer;
    }

    public void setInitializer(FunctionNode initializer) {
        this.initializer = initializer;
    }

    public boolean isAccessor() {
        return isAccessor;
    }

    public void setAccessor(boolean accessor) {
        this.isAccessor = accessor;
    }

    public List<Node> getDecorators() {
        return decorators;
    }

    public void setDecorators(List<Node> decorators) {
        this.decorators = decorators;
    }
}
