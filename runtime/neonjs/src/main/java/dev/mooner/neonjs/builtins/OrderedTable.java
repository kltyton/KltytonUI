/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Undefined;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.BiConsumer;

/**
 * Insertion-ordered hash table with tombstones. Live iterators are tracked weakly and re-indexed on compaction, so
 * iteration observes additions and deletions as the spec requires.
 */
public class OrderedTable {
    private static final Object TOMB = new Object();

    private final ArrayList<Object> keys = new ArrayList<>();
    private final ArrayList<Object> values = new ArrayList<>();
    private final HashMap<MapKey, Integer> index = new HashMap<>();
    private int tombstones;
    private final ArrayList<WeakReference<TableCursor>> iterators = new ArrayList<>();

    public int size() {
        return index.size();
    }

    public int find(Object key) {
        Integer position = index.get(MapKey.of(key));
        return position == null ? -1 : position;
    }

    public Object get(Object key) {
        int i = find(key);
        return i < 0 ? Undefined.INSTANCE : values.get(i);
    }

    public boolean has(Object key) {
        return find(key) >= 0;
    }

    public void set(Object k, Object v) {
        Object key;
        if (k instanceof Double d && d == 0.0) {
            key = 0.0;
        } else if (k instanceof CharSequence text) {
            key = text.toString();
        } else {
            key = k;
        }
        MapKey mapKey = MapKey.of(key);
        Integer existing = index.get(mapKey);
        if (existing != null) {
            values.set(existing, v);
            return;
        }
        index.put(mapKey, keys.size());
        keys.add(key);
        values.add(v);
    }

    public boolean delete(Object k) {
        MapKey mapKey = MapKey.of(k);
        Integer position = index.remove(mapKey);
        if (position == null) return false;
        int i = position;
        keys.set(i, TOMB);
        values.set(i, null);
        tombstones++;
        maybeCompact(false);
        return true;
    }

    public void clear() {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i) != TOMB) {
                keys.set(i, TOMB);
                values.set(i, null);
                tombstones++;
            }
        }
        index.clear();
        maybeCompact(true);
    }

    private void maybeCompact(boolean force) {
        if (!force && (tombstones < 16 || tombstones * 2 < keys.size())) return;
        int keyCount = keys.size();
        int[] live = new int[keyCount + 1];
        int n = 0;
        for (int i = 0; i < keyCount; i++) {
            live[i] = n;
            if (keys.get(i) != TOMB) n++;
        }
        live[keyCount] = n;
        var iterator = iterators.iterator();
        while (iterator.hasNext()) {
            TableCursor cursor = iterator.next().get();
            if (cursor == null) {
                iterator.remove();
                continue;
            }
            cursor.pos = live[Math.min(cursor.pos, keyCount)];
        }
        ArrayList<Object> newKeys = new ArrayList<>(n);
        ArrayList<Object> newValues = new ArrayList<>(n);
        for (int i = 0; i < keyCount; i++) {
            if (keys.get(i) != TOMB) {
                newKeys.add(keys.get(i));
                newValues.add(values.get(i));
            }
        }
        keys.clear();
        keys.addAll(newKeys);
        values.clear();
        values.addAll(newValues);
        index.clear();
        for (int i = 0; i < keys.size(); i++) index.put(MapKey.of(keys.get(i)), i);
        tombstones = 0;
    }

    public TableCursor cursor() {
        TableCursor cursor = new TableCursor(this);
        iterators.add(new WeakReference<>(cursor));
        if (iterators.size() > 64) iterators.removeIf(reference -> reference.get() == null);
        return cursor;
    }

    /** Advances {@code cursor}; returns false when exhausted. */
    public boolean advance(TableCursor cursor) {
        while (cursor.pos < keys.size()) {
            int i = cursor.pos++;
            if (keys.get(i) != TOMB) {
                cursor.key = keys.get(i);
                cursor.value = values.get(i);
                return true;
            }
        }
        return false;
    }

    /** Snapshot-free forEach that tolerates mutation. */
    public void forEachLive(BiConsumer<Object, Object> action) {
        TableCursor cursor = cursor();
        while (advance(cursor)) action.accept(cursor.key, cursor.value);
    }
}
