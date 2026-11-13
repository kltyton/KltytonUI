package dev.mooner.neonjs.runtime;

import java.util.LinkedHashSet;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Finds the optional internationalization provider when it is first needed.
 * Derived from NeonJS and adapted to Java.
 */
public final class IntlSupport {
    public static final IntlSupport INSTANCE = new IntlSupport();
    private static volatile boolean initialized;
    private static IntlProvider provider;

    private IntlSupport() {
    }

    public static IntlProvider getProvider() {
        if (!initialized) {
            synchronized (INSTANCE) {
                if (!initialized) {
                    provider = findProvider();
                    initialized = true;
                }
            }
        }
        return provider;
    }

    private static IntlProvider findProvider() {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader providerLoader = IntlProvider.class.getClassLoader();
        if (contextLoader != null) {
            loaders.add(contextLoader);
        }
        if (providerLoader != null) {
            loaders.add(providerLoader);
        }
        for (ClassLoader loader : loaders) {
            var providers = ServiceLoader.load(IntlProvider.class, loader).iterator();
            if (providers.hasNext()) {
                return providers.next();
            }
        }
        return null;
    }
}
