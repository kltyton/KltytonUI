/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

/** Resolution result stored in {@code Identifier.ref} (and this/super/new.target references). */
public sealed abstract class Resolved permits LocalRef, GlobalRef, DynamicRef {
}
