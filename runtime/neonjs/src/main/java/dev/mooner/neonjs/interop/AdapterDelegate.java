/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.interop;

/**
 * Receives calls of overridden methods of a generated adapter class. Implementations run the JS function under the
 * owning context's lock.
 */
public interface AdapterDelegate {
    /** Calls method #{@code index} of the adapter class; returns {@link AdapterSupport#NOT_HANDLED} for super. */
    Object invoke(Object self, int index, Object[] args);
}
