/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.List;

public class BlockStatement extends Node {
    public final List<Node> body;

    public BlockStatement(List<Node> body) {
        this.body = body;
    }

    public List<Node> getBody() {
        return body;
    }
}
