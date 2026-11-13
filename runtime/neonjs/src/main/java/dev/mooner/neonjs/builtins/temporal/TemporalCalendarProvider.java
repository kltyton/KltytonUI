/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Service hook for the implementation-defined parts of Temporal that need locale data: non-ISO calendars and the
 * primary identifiers of IANA time zone links. Found with {@link ServiceLoader} ({@code META-INF/services}); without a
 * provider Temporal supports only the {@code iso8601} calendar and keeps link names as their own primary identifiers.
 *
 * <p>Implementations are shared by every agent and thread of the JVM, so they must be stateless or thread-safe.
 */
public interface TemporalCalendarProvider {
    /** Canonical (lowercase) calendar types this provider implements, not including "iso8601". */
    Collection<String> calendarIds();

    /** The year-structure source for {@code calendarId} (one of {@link #calendarIds()}), or null. */
    TemporalCalendarSource calendarSource(String calendarId);

    /**
     * The IANA primary identifier of the available named time zone {@code id} (given in its canonical case), or null to
     * treat {@code id} as primary. The "UTC" special case of ECMA-402 is applied by the caller.
     */
    default String primaryTimeZoneId(String id) {
        return null;
    }
}

/** Lazy access to the installed {@link TemporalCalendarProvider}. */
final class TemporalProviders {
    public static final TemporalProviders INSTANCE = new TemporalProviders();

    /**
     * The installed provider, or null when none is on the class path. This is computed on first access to this
     * class, so Temporal and {@link ServiceLoader} are not initialized before a provider is actually needed.
     */
    public static final TemporalCalendarProvider provider = load();

    private TemporalProviders() {
    }

    /** Kotlin-style getter for the {@code provider} property. */
    public static TemporalCalendarProvider provider() {
        return provider;
    }

    /** Kotlin-style getter for the {@code provider} property. */
    public static TemporalCalendarProvider getProvider() {
        return provider;
    }

    private static TemporalCalendarProvider load() {
        List<ClassLoader> loaders = new ArrayList<>(2);
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) loaders.add(context);
        ClassLoader own = TemporalCalendarProvider.class.getClassLoader();
        if (own != null && !loaders.contains(own)) loaders.add(own);
        for (ClassLoader loader : loaders) {
            try {
                Iterator<TemporalCalendarProvider> providers =
                        ServiceLoader.load(TemporalCalendarProvider.class, loader).iterator();
                if (providers.hasNext()) return providers.next();
            } catch (ServiceConfigurationError ignored) {
                // try the next loader
            }
        }
        return null;
    }
}
