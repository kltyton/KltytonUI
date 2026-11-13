/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.Collections;
import java.util.List;

public class ClassNode extends Node {
    public final Identifier id;
    public final Node superClass;
    public final List<Node> body;
    public FunctionNode constructor = null;
    /** Class decorators (`@dec class ...`), in source order. */
    public List<Node> decorators = Collections.emptyList();
    public boolean isDeclaration = false;
    public int srcStart = 0;
    public int srcEnd = 0;

    public ClassNode(Identifier id, Node superClass, List<Node> body) {
        this.id = id;
        this.superClass = superClass;
        this.body = body;
    }

    public Identifier getId() {
        return id;
    }

    public Node getSuperClass() {
        return superClass;
    }

    public List<Node> getBody() {
        return body;
    }

    public FunctionNode getConstructor() {
        return constructor;
    }

    public void setConstructor(FunctionNode constructor) {
        this.constructor = constructor;
    }

    public List<Node> getDecorators() {
        return decorators;
    }

    public void setDecorators(List<Node> decorators) {
        this.decorators = decorators;
    }

    public boolean isDeclaration() {
        return isDeclaration;
    }

    public void setDeclaration(boolean declaration) {
        this.isDeclaration = declaration;
    }

    public int getSrcStart() {
        return srcStart;
    }

    public void setSrcStart(int srcStart) {
        this.srcStart = srcStart;
    }

    public int getSrcEnd() {
        return srcEnd;
    }

    public void setSrcEnd(int srcEnd) {
        this.srcEnd = srcEnd;
    }
}
