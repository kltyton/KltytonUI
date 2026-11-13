package dev.mooner.neonjs.jit;

/**
 * The binary name and class-file bytes of a generated class.
 * Derived from NeonJS and adapted to Java.
 */
public final class GeneratedClass {
    public final String name;
    public final byte[] bytes;

    public GeneratedClass(String name, byte[] bytes) {
        this.name = name;
        this.bytes = bytes;
    }
}
