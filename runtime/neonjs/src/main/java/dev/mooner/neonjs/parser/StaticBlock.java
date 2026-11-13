/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.List;

public class StaticBlock extends Node {
    public final List<Node> body;
    public FunctionNode function = null;

    public StaticBlock(List<Node> body) {
        this.body = body;
    }

    public List<Node> getBody() {
        return body;
    }

    public FunctionNode getFunction() {
        return function;
    }

    public void setFunction(FunctionNode function) {
        this.function = function;
    }
}
