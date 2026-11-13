package dev.mooner.neonjs.jit;

/** Isolated Java 8 bytecode definer; derived from NeonJS and adapted to Java. */
public final class IsolatedCodeDefiner implements CodeDefiner {
    @Override
    public int getClassFileVersion() {
        return 52;
    }

    @Override
    public Class<?> define(String name, byte[] bytes, ClassLoader parent) {
        return new JvmCodeDefiner.OwnLoader(parent).define(name, bytes);
    }
}
