/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

/** Implemented by typed arrays so the array iterator can query their length. */
public interface TypedArrayLike {
    long typedLength();

    boolean isOutOfBounds();
}
