package dev.mooner.neonjs.jit;

import java.util.ArrayList;
import java.util.List;

/** Defines generated JVM classes; derived from NeonJS and adapted to Java. */
public interface CodeDefiner {
    default int getClassFileVersion() {
        return 61;
    }

    default boolean isSupported() {
        return true;
    }

    Class<?> define(String name, byte[] bytes, ClassLoader parent);

    default List<Class<?>> defineAll(List<GeneratedClass> classes, ClassLoader parent) {
        List<Class<?>> defined = new ArrayList<>(classes.size());
        for (GeneratedClass generated : classes) defined.add(define(generated.name, generated.bytes, parent));
        return defined;
    }
}
