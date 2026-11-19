/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.interop;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Public host-field access linked once after HostAccess has approved the member. */
final class HostFieldAccess {
    private final String name;
    private final MethodHandle reader;
    private final MethodHandle writer;

    HostFieldAccess(Field field) {
        name = field.getDeclaringClass().getName() + "." + field.getName();
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle getter = lookup.unreflectGetter(field);
            MethodHandle setter = Modifier.isFinal(field.getModifiers()) ? null : lookup.unreflectSetter(field);
            if (Modifier.isStatic(field.getModifiers())) {
                getter = MethodHandles.dropArguments(getter, 0, Object.class);
                if (setter != null) setter = MethodHandles.dropArguments(setter, 0, Object.class);
            }
            reader = getter.asType(MethodType.methodType(Object.class, Object.class));
            writer = setter == null ? null : setter.asType(MethodType.methodType(void.class, Object.class, Object.class));
        } catch (IllegalAccessException denied) {
            throw new IllegalStateException("Cannot link public host field " + name, denied);
        }
    }

    Object read(Object receiver) {
        try {
            return (Object) reader.invokeExact(receiver);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot read host field " + name, failure);
        }
    }

    void write(Object receiver, Object value) {
        try {
            writer.invokeExact(receiver, value);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot write host field " + name, failure);
        }
    }
}
