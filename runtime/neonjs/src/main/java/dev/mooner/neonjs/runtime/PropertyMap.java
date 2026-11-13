package dev.mooner.neonjs.runtime;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntConsumer;

/** Insertion-ordered property storage; derived from NeonJS and adapted to Java. */
public final class PropertyMap {
    public static final int MAX_LAYOUT = 8;
    private static final Object DELETED = new Object();
    public Shape shape;
    public Object[] keys;
    public Object[] values;
    public int[] flags;
    public int used;
    public int count;
    public boolean hasIndexKeys;
    private int[] hashes;
    private boolean layoutShared;
    private int[] index;

    private PropertyMap(Shape shape, Object[] keys, Object[] values, int[] flags, int[] hashes,
                        int used, boolean layoutShared) {
        this.shape = shape;
        this.keys = keys;
        this.values = values;
        this.flags = flags;
        this.hashes = hashes;
        this.used = used;
        count = used;
        this.layoutShared = layoutShared;
    }

    public PropertyMap(Shape initialShape) {
        this(initialShape, 4);
    }

    public PropertyMap(Shape initialShape, int capacity) {
        this(initialShape == null ? Shape.unique() : initialShape,
                new Object[capacity], new Object[capacity], new int[capacity], new int[capacity], 0, false);
    }

    public int find(Object key) {
        int[] current = index;
        if (current == null) {
            Object[] currentKeys = keys;
            int[] currentHashes = hashes;
            int hash = keyHash(key);
            for (int i = 0; i < used; i++) {
                if (currentHashes[i] != hash) continue;
                Object stored = currentKeys[i];
                if (stored == key || Objects.equals(stored, key)) return i;
            }
            return -1;
        }
        int mask = current.length - 1;
        int slot = spread(keyHash(key)) & mask;
        while (true) {
            int entry = current[slot];
            if (entry == 0) return -1;
            Object stored = keys[entry - 1];
            if (stored == key || (stored != DELETED && Objects.equals(stored, key))) return entry - 1;
            slot = (slot + 1) & mask;
        }
    }

    private static int keyHash(Object key) {
        if (key instanceof String text) return text.hashCode();
        if (key instanceof Integer integer) return integer;
        return System.identityHashCode(key);
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16) ^ (hash >>> 7);
    }

    public int add(Object key, Object value, int attributes) {
        int entry = append(key, value, attributes);
        Shape current = shape;
        if (current.cacheable) shape = current.append(key, attributes);
        return entry;
    }

    public void addTransition(Shape next, Object value) {
        Shape before = shape;
        append(Objects.requireNonNull(next.key), value, next.attrs);
        shape = shape == before ? next : Shape.unique();
    }

    private void ownLayout() {
        if (!layoutShared) return;
        keys = keys.clone();
        flags = flags.clone();
        hashes = hashes.clone();
        layoutShared = false;
    }

    public void setFlags(int entry, int attributes) {
        if (flags[entry] == attributes) return;
        ownLayout();
        flags[entry] = attributes;
        if (shape.cacheable) shape = Shape.unique();
    }

    public void reroot(Shape newRoot) {
        Shape current = shape;
        if (!current.cacheable) return;
        if (newRoot == Shape.UNCACHEABLE) {
            shape = newRoot;
            return;
        }
        if (newRoot == null || !current.shared || !newRoot.shared) {
            shape = Shape.unique();
            return;
        }
        Shape next = newRoot;
        for (int i = 0; i < used; i++) {
            next = next.append(Objects.requireNonNull(keys[i]), flags[i]);
            if (!next.shared) break;
        }
        shape = next;
    }

    private int append(Object key, Object value, int attributes) {
        if (used == keys.length) grow();
        ownLayout();
        int entry = used++;
        keys[entry] = key;
        values[entry] = value;
        flags[entry] = attributes;
        hashes[entry] = keyHash(key);
        count++;
        if (key instanceof Integer) hasIndexKeys = true;
        int[] current = index;
        if (current != null) {
            if (used * 2 > current.length) rebuildIndex(current.length * 2);
            else insertIndex(current, key, entry);
        } else if (used > 8) {
            rebuildIndex(32);
        }
        return entry;
    }

    private void insertIndex(int[] destination, Object key, int entry) {
        int mask = destination.length - 1;
        int slot = spread(keyHash(key)) & mask;
        while (destination[slot] != 0) slot = (slot + 1) & mask;
        destination[slot] = entry + 1;
    }

    private void rebuildIndex(int size) {
        while (size < used * 2) size *= 2;
        int[] rebuilt = new int[size];
        for (int i = 0; i < used; i++) {
            Object key = keys[i];
            if (key != DELETED) insertIndex(rebuilt, Objects.requireNonNull(key), i);
        }
        index = rebuilt;
    }

    private void grow() {
        if (count < used && count <= used / 2) {
            compact();
            if (used < keys.length) return;
        }
        int size = Math.max(4, keys.length * 2);
        keys = Arrays.copyOf(keys, size);
        values = Arrays.copyOf(values, size);
        flags = Arrays.copyOf(flags, size);
        hashes = Arrays.copyOf(hashes, size);
        layoutShared = false;
    }

    private void compact() {
        int destination = 0;
        for (int i = 0; i < used; i++) {
            Object key = keys[i];
            if (key != DELETED) {
                keys[destination] = key;
                values[destination] = values[i];
                flags[destination] = flags[i];
                hashes[destination] = hashes[i];
                destination++;
            }
        }
        for (int i = destination; i < used; i++) {
            keys[i] = null;
            values[i] = null;
        }
        used = destination;
        if (shape.cacheable) shape = Shape.unique();
        if (index != null) {
            if (used > 8) rebuildIndex(32);
            else index = null;
        }
    }

    public void removeAt(int entry) {
        ownLayout();
        if (shape.cacheable) shape = Shape.unique();
        keys[entry] = DELETED;
        values[entry] = null;
        count--;
        if (count == 0) {
            for (int i = 0; i < used; i++) {
                keys[i] = null;
                values[i] = null;
            }
            used = 0;
            index = null;
        }
    }

    public boolean isLive(int entry) {
        return keys[entry] != DELETED;
    }

    public void forEachLive(IntConsumer consumer) {
        int limit = used;
        for (int i = 0; i < limit; i++) if (isLive(i)) consumer.accept(i);
    }

    public static Layout layoutOf(Shape root, Object[] keys) {
        if (root == null || !root.shared || keys.length > MAX_LAYOUT) return null;
        Shape current = root;
        Object[] stored = new Object[keys.length];
        int[] attributes = new int[keys.length];
        int[] hashes = new int[keys.length];
        boolean hasIndex = false;
        for (int i = 0; i < keys.length; i++) {
            Object key = keys[i];
            current = current.append(key, Attr.ALL);
            if (!current.shared) return null;
            stored[i] = key;
            attributes[i] = Attr.ALL;
            hashes[i] = keyHash(key);
            if (key instanceof Integer) hasIndex = true;
        }
        return new Layout(root, current, stored, attributes, hashes, hasIndex);
    }

    public static PropertyMap withLayout(Layout layout, Object[] values) {
        PropertyMap map = new PropertyMap(layout.shape, layout.keys, values, layout.flags,
                layout.hashes, values.length, true);
        map.hasIndexKeys = layout.hasIndexKeys;
        return map;
    }

    public static final class Layout {
        public final Shape root;
        public final Shape shape;
        public final Object[] keys;
        public final int[] flags;
        public final int[] hashes;
        public final boolean hasIndexKeys;

        public Layout(Shape root, Shape shape, Object[] keys, int[] flags, int[] hashes, boolean hasIndexKeys) {
            this.root = root;
            this.shape = shape;
            this.keys = keys;
            this.flags = flags;
            this.hashes = hashes;
            this.hasIndexKeys = hasIndexKeys;
        }
    }
}
