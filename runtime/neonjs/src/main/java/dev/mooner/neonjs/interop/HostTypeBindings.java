package dev.mooner.neonjs.interop;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class HostTypeBindings {
    private final HashMap<TypeVariable<?>, Type> bindings = new HashMap<>();

    public HostTypeBindings(Type declared) {
        collect(declared, new HashSet<Class<?>>());
    }

    private void collect(Type type, Set<Class<?>> seen) {
        Type current = upper(type);
        Class<?> cls = raw(current);
        if (current instanceof ParameterizedType) {
            Type[] actual = ((ParameterizedType) current).getActualTypeArguments();
            Type[] args = new Type[actual.length];
            for (int i = 0; i < actual.length; i++) {
                args[i] = resolve(actual[i]);
            }
            TypeVariable<?>[] variables = cls.getTypeParameters();
            for (int i = 0; i < variables.length; i++) {
                bindings.put(variables[i], args[i]);
            }
        }
        if (!seen.add(cls)) {
            return;
        }
        for (Type parent : cls.getGenericInterfaces()) {
            collect(resolve(parent), seen);
        }
    }

    public Type resolve(Type type) {
        return resolve(type, new HashSet<TypeVariable<?>>());
    }

    private Type resolve(Type type, Set<TypeVariable<?>> path) {
        if (type instanceof TypeVariable<?>) {
            TypeVariable<?> variable = (TypeVariable<?>) type;
            if (path.contains(variable)) {
                return type;
            }
            Type replacement = bindings.get(variable);
            if (replacement == null) {
                Type[] bounds = variable.getBounds();
                replacement = bounds.length > 0 ? bounds[0] : Object.class;
            }
            if (replacement.equals(variable)) {
                return type;
            }
            Set<TypeVariable<?>> next = new HashSet<>(path);
            next.add(variable);
            return resolve(replacement, next);
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            Type owner = parameterized.getOwnerType();
            Type[] actual = parameterized.getActualTypeArguments();
            Type[] args = new Type[actual.length];
            for (int i = 0; i < actual.length; i++) {
                args[i] = resolve(actual[i], path);
            }
            return new AppliedType(parameterized.getRawType(), owner == null ? null : resolve(owner, path), args);
        }
        if (type instanceof GenericArrayType) {
            Type component = resolve(((GenericArrayType) type).getGenericComponentType(), path);
            if (component instanceof Class<?>) {
                return Array.newInstance((Class<?>) component, 0).getClass();
            }
            return new ArrayType(component);
        }
        if (type instanceof WildcardType) {
            Type[] upperBounds = ((WildcardType) type).getUpperBounds();
            Type bound = upperBounds.length > 0 ? upperBounds[0] : Object.class;
            return resolve(bound, path);
        }
        return type;
    }

    private static Type upper(Type type) {
        if (type == null) {
            return Object.class;
        }
        if (type instanceof WildcardType) {
            Type[] upperBounds = ((WildcardType) type).getUpperBounds();
            return upper(upperBounds.length > 0 ? upperBounds[0] : null);
        }
        if (type instanceof TypeVariable<?>) {
            Type[] bounds = ((TypeVariable<?>) type).getBounds();
            return upper(bounds.length > 0 ? bounds[0] : null);
        }
        return type;
    }

    public static Class<?> raw(Type type) {
        Type current = upper(type);
        if (current instanceof Class<?>) {
            return (Class<?>) current;
        }
        if (current instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) current).getRawType();
        }
        if (current instanceof GenericArrayType) {
            return Array.newInstance(raw(((GenericArrayType) current).getGenericComponentType()), 0).getClass();
        }
        throw new IllegalArgumentException("Unsupported Java type: " + current);
    }

    public static Type element(Type type, int index) {
        Type current = type == null ? null : upper(type);
        if (current instanceof ParameterizedType) {
            Type[] args = ((ParameterizedType) current).getActualTypeArguments();
            if (index >= 0 && index < args.length) {
                Type arg = args[index];
                if (arg != null) {
                    return arg;
                }
            }
        }
        return Object.class;
    }

    public static Type component(Type type, Class<?> fallback) {
        Type current = type == null ? fallback : upper(type);
        if (current instanceof GenericArrayType) {
            return ((GenericArrayType) current).getGenericComponentType();
        }
        if (current instanceof Class<?>) {
            Class<?> cls = (Class<?>) current;
            return cls.isArray() ? cls.getComponentType() : fallback;
        }
        return fallback;
    }

    private static final class AppliedType implements ParameterizedType {
        private final Type base;
        private final Type owner;
        private final Type[] args;

        private AppliedType(Type base, Type owner, Type[] args) {
            this.base = base;
            this.owner = owner;
            this.args = args;
        }

        @Override
        public Type getRawType() {
            return base;
        }

        @Override
        public Type getOwnerType() {
            return owner;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return args.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ParameterizedType)) {
                return false;
            }
            ParameterizedType that = (ParameterizedType) other;
            return base.equals(that.getRawType())
                    && Objects.equals(owner, that.getOwnerType())
                    && Arrays.equals(args, that.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(args) ^ (owner == null ? 0 : owner.hashCode()) ^ base.hashCode();
        }
    }

    private static final class ArrayType implements GenericArrayType {
        private final Type component;

        private ArrayType(Type component) {
            this.component = component;
        }

        @Override
        public Type getGenericComponentType() {
            return component;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GenericArrayType
                    && Objects.equals(component, ((GenericArrayType) other).getGenericComponentType());
        }

        @Override
        public int hashCode() {
            return component.hashCode();
        }
    }
}
