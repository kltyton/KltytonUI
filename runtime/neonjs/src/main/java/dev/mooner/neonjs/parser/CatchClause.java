/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class CatchClause extends Node {
    public final Node param;
    public final BlockStatement body;

    public CatchClause(Node param, BlockStatement body) {
        this.param = param;
        this.body = body;
    }

    public Node getParam() {
        return param;
    }

    public BlockStatement getBody() {
        return body;
    }
}
