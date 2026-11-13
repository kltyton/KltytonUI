/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

import java.util.ArrayList;

/** Bytecode jump label with unresolved branch fixups. */
public final class Label {
    public int pos = -1;
    public int depth = -1;
    public final ArrayList<Integer> fixups = new ArrayList<>();
}
