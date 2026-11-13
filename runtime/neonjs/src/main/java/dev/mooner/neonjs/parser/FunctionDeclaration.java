/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class FunctionDeclaration extends Node {
    public final FunctionNode function;

    public FunctionDeclaration(FunctionNode function) {
        this.function = function;
    }

    public FunctionNode getFunction() {
        return function;
    }
}
