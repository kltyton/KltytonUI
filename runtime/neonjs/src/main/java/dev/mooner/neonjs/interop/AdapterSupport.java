/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.interop;

/** Adapter support values; derived from NeonJS and adapted to Java. */
public final class AdapterSupport {
    /** Returned by {@link AdapterDelegate#invoke} when JS does not implement the method. */
    public static final Object NOT_HANDLED = new Object();

    private AdapterSupport() {
    }
}
