package dev.mooner.neonjs.jit;

import java.lang.invoke.MethodHandles;

/** Standard JVM hidden-class definer; derived from NeonJS and adapted to Java. */
public class JvmCodeDefiner implements CodeDefiner {
    private static final String JIT_PACKAGE = "dev.mooner.neonjs.jit.";
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    public static final boolean VISIBLE_CLASSES = System.getProperty("neonjs.jit.visibleClasses") != null;

    @Override
    public Class<?> define(String name, byte[] bytes, ClassLoader parent) {
        if (name.startsWith(JIT_PACKAGE) && parent == JvmCodeDefiner.class.getClassLoader()) {
            try {
                MethodHandles.Lookup lookup = VISIBLE_CLASSES
                        ? MethodHandles.privateLookupIn(LOOKUP.defineClass(bytes), LOOKUP)
                        : LOOKUP.defineHiddenClass(bytes, true);
                return lookup.lookupClass();
            } catch (IllegalAccessException error) {
                return JvmCodeDefiner.<RuntimeException, Class<?>>rethrow(error);
            }
        }
        return new OwnLoader(parent).define(name, bytes);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T rethrow(Throwable error) throws E {
        throw (E) error;
    }

    public static final class OwnLoader extends ClassLoader {
        public OwnLoader(ClassLoader parent) {
            super(parent);
        }

        public Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
