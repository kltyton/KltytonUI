package dev.mooner.neonjs.runtime;

import java.util.HashMap;
import java.util.Objects;

/** Context-owned property-layout token; derived from NeonJS and adapted to Java. */
public final class Shape {
    public static final int MAX_SHARED_SIZE = 128;
    public static final int MAX_TREE_SIZE = 16384;
    public static final Shape UNCACHEABLE = new Shape(null, 0, 0, null, null, false);
    private static final int SPECIAL_ACCESS = JSObject.SPECIAL_GET | JSObject.SPECIAL_SET | JSObject.SPECIAL_HAS;

    public final Object key;
    public final int attrs;
    public final int size;
    public final Class<?> ownerClass;
    public final boolean cacheable;
    public final boolean shared;
    private final Shape root;
    private Object transitions;
    private int treeSize = 1;

    private Shape(Object key, int attrs, int size, Shape root, Class<?> ownerClass, boolean cacheable) {
        this.key = key;
        this.attrs = attrs;
        this.size = size;
        this.root = root == null ? this : root;
        this.ownerClass = ownerClass;
        this.cacheable = cacheable;
        shared = ownerClass != null || root != null;
    }

    @SuppressWarnings("unchecked")
    public Shape append(Object key, int attrs) {
        if (!shared || size >= MAX_SHARED_SIZE) return unique();
        Object current = transitions;
        if (current instanceof Shape shape) {
            if (shape.attrs == attrs && Objects.equals(shape.key, key)) return shape;
        } else if (current != null) {
            Shape known = ((HashMap<TransitionKey, Shape>) current).get(new TransitionKey(key, attrs));
            if (known != null) return known;
        }
        if (root.treeSize >= MAX_TREE_SIZE) return unique();
        root.treeSize++;
        Shape child = new Shape(key, attrs, size + 1, root, null, true);
        if (current == null) {
            transitions = child;
        } else if (current instanceof Shape shape) {
            HashMap<TransitionKey, Shape> next = new HashMap<>();
            next.put(new TransitionKey(Objects.requireNonNull(shape.key), shape.attrs), shape);
            next.put(new TransitionKey(key, attrs), child);
            transitions = next;
        } else {
            ((HashMap<TransitionKey, Shape>) current).put(new TransitionKey(key, attrs), child);
        }
        return child;
    }

    public static Shape unique() {
        return new Shape(null, 0, 0, null, null, true);
    }

    public static Shape newRoot(Class<?> type) {
        return new Shape(null, 0, 0, null, type, true);
    }

    public static Shape emptyShapeOf(JSObject object) {
        if ((object.special & SPECIAL_ACCESS) != 0) return UNCACHEABLE;
        JSObject prototype = object.proto;
        if (prototype == null) return null;
        Object cached = prototype.childShapes;
        Class<?> type = object.getClass();
        if (cached instanceof Shape shape && shape.ownerClass == type) return shape;
        return rootSlow(prototype, type);
    }

    @SuppressWarnings("unchecked")
    private static Shape rootSlow(JSObject prototype, Class<?> type) {
        Object cached = prototype.childShapes;
        if (cached == null) {
            Shape root = newRoot(type);
            prototype.childShapes = root;
            return root;
        }
        if (cached instanceof Shape shape) {
            if (shape.ownerClass == type) return shape;
            HashMap<Class<?>, Shape> roots = new HashMap<>(4);
            roots.put(Objects.requireNonNull(shape.ownerClass), shape);
            Shape root = newRoot(type);
            roots.put(type, root);
            prototype.childShapes = roots;
            return root;
        }
        return ((HashMap<Class<?>, Shape>) cached).computeIfAbsent(type, Shape::newRoot);
    }

    @Override
    public String toString() {
        if (!cacheable) return "Shape(uncacheable)";
        if (!shared) return "Shape(unique@" + Integer.toHexString(System.identityHashCode(this)) + ")";
        return "Shape(size=" + size + ", key=" + key + ")";
    }

    private record TransitionKey(Object key, int attrs) {
    }
}
