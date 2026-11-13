/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.interop;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** Structure of a generated adapter class. */
public final class AdapterClass {
    public final Class<?> cls;
    /** Overridable methods, indexed as passed to {@link AdapterDelegate#invoke}. */
    public final List<Method> methods;
    /** (base constructor, adapter constructor taking the delegate first). */
    public final List<ConstructorPair> constructors;
    /** Generated super bridges by JS method name, for Java.super(). */
    public final Map<String, List<Method>> superMethods;

    public AdapterClass(Class<?> cls, List<Method> methods, List<ConstructorPair> constructors,
                        Map<String, List<Method>> superMethods) {
        this.cls = cls;
        this.methods = methods;
        this.constructors = constructors;
        this.superMethods = superMethods;
    }

    /** Kotlin Pair replacement: a base constructor and its generated adapter constructor. */
    public record ConstructorPair(Constructor<?> base, Constructor<?> adapter) {
    }
}
