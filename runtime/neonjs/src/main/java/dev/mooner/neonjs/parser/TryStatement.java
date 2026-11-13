/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class TryStatement extends Node {
    public final BlockStatement block;
    public final CatchClause handler;
    public final BlockStatement finalizer;

    public TryStatement(BlockStatement block, CatchClause handler, BlockStatement finalizer) {
        this.block = block;
        this.handler = handler;
        this.finalizer = finalizer;
    }

    public BlockStatement getBlock() {
        return block;
    }

    public CatchClause getHandler() {
        return handler;
    }

    public BlockStatement getFinalizer() {
        return finalizer;
    }
}
