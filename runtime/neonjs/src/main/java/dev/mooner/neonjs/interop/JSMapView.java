/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.interop;

import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import java.lang.reflect.Type;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** Live java.util.Map view of a JS object's own enumerable string-keyed properties. */
public final class JSMapView extends AbstractMap<String, Object> {
    final HostBridge bridge;
    final JSObject obj;
    private final Type valueType;

    public JSMapView(HostBridge bridge, JSObject obj, Type valueType) {
        this.bridge = bridge;
        this.obj = obj;
        this.valueType = valueType;
    }

    /**
     * The entries as they are when asked for; an entry's setValue and the iterator's remove change the JS object, as
     * they change a map.
     */
    @Override
    public Set<Map.Entry<String, Object>> entrySet() {
        List<Entry> snapshot = bridge.gate.enter(() -> {
            List<Entry> list = new ArrayList<>();
            for (Object key : obj.ownPropertyKeys()) {
                if (key instanceof JSSymbol) continue;
                PropertyDescriptor descriptor = obj.getOwnProperty(key);
                if (descriptor == null || !descriptor.enumerable) continue;
                list.add(new Entry(PK.toStringKey(key),
                        bridge.toHost(obj.get(key, obj), HostTypeBindings.raw(valueType), valueType)));
            }
            return list;
        });
        return new AbstractSet<>() {
            @Override
            public int size() {
                return snapshot.size();
            }

            @Override
            public Iterator<Map.Entry<String, Object>> iterator() {
                return new Iterator<>() {
                    private int next;

                    @Override
                    public boolean hasNext() {
                        return next < snapshot.size();
                    }

                    @Override
                    public Map.Entry<String, Object> next() {
                        if (next >= snapshot.size()) throw new NoSuchElementException();
                        return snapshot.get(next++);
                    }

                    @Override
                    public void remove() {
                        if (!(next > 0)) throw new IllegalStateException("next() not called");
                        JSMapView.this.remove(snapshot.get(next - 1).getKey());
                    }
                };
            }
        };
    }

    private final class Entry extends AbstractMap.SimpleEntry<String, Object> {
        Entry(String key, Object value) {
            super(key, value);
        }

        @Override
        public Object setValue(Object value) {
            put(getKey(), value);
            return super.setValue(value);
        }
    }

    @Override
    public Object get(Object key) {
        String name = (String) key;
        return bridge.gate.enter(() -> bridge.toHost(obj.get(PK.fromString(name), obj),
                HostTypeBindings.raw(valueType), valueType));
    }

    @Override
    public boolean containsKey(Object key) {
        String name = (String) key;
        return bridge.gate.enter(() -> obj.hasProperty(PK.fromString(name)));
    }

    @Override
    public Object put(String key, Object value) {
        String name = key;
        return bridge.gate.enter(() -> {
            Object property = PK.fromString(name);
            Object old = obj.get(property, obj);
            obj.setOrThrow(property, bridge.toJS(value));
            return bridge.toHost(old, HostTypeBindings.raw(valueType), valueType);
        });
    }

    @Override
    public Object remove(Object key) {
        String name = (String) key;
        return bridge.gate.enter(() -> {
            Object property = PK.fromString(name);
            Object old = obj.get(property, obj);
            obj.delete(property);
            return bridge.toHost(old, HostTypeBindings.raw(valueType), valueType);
        });
    }
}
