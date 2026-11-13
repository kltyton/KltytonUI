/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.Collections;
import java.util.List;

public class MethodDefinition extends Node {
    public final Node key;
    public final FunctionNode value;
    public final MethodKind kind;
    public final boolean isStatic;
    public final boolean computed;
    public List<Node> decorators = Collections.emptyList();

    public MethodDefinition(Node key, FunctionNode value, MethodKind kind, boolean isStatic,
                            boolean computed) {
        this.key = key;
        this.value = value;
        this.kind = kind;
        this.isStatic = isStatic;
        this.computed = computed;
    }

    public Node getKey() {
        return key;
    }

    public FunctionNode getValue() {
        return value;
    }

    public MethodKind getKind() {
        return kind;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public boolean getComputed() {
        return computed;
    }

    public List<Node> getDecorators() {
        return decorators;
    }

    public void setDecorators(List<Node> decorators) {
        this.decorators = decorators;
    }
}
