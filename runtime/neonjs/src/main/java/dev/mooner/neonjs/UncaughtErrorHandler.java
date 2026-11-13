/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs;

/** Receives the errors JS leaves uncaught while jobs run (see {@link NeonContext#setUncaughtErrorHandler}). */
@FunctionalInterface
public interface UncaughtErrorHandler {
    /**
     * {@code error} carries the thrown value or the rejection reason as its {@link NeonException#getGuestValue()};
     * {@code rejection} tells which. Called on the thread running the jobs, inside the context.
     */
    void uncaught(NeonException error, boolean rejection);
}
