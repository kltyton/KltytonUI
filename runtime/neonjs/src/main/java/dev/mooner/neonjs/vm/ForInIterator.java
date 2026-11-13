package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import java.util.HashSet;
import java.util.List;

/** EnumerateObjectProperties state; derived from NeonJS and adapted to Java. */
public final class ForInIterator {
    private JSObject object;
    private List<Object> keys;
    private int index;
    private final HashSet<Object> visited = new HashSet<>();

    public ForInIterator(JSObject start) {
        object = start;
    }

    public Object next() {
        while (true) {
            JSObject current = object;
            if (current == null) return null;
            List<Object> currentKeys = keys;
            if (currentKeys == null) {
                currentKeys = current.ownPropertyKeys();
                keys = currentKeys;
                index = 0;
            }
            while (index < currentKeys.size()) {
                Object key = currentKeys.get(index++);
                if (key instanceof JSSymbol || visited.contains(key)) continue;
                PropertyDescriptor descriptor = current.getOwnProperty(key);
                if (descriptor == null) continue;
                visited.add(key);
                if (descriptor.enumerable) return PK.toValue(key);
            }
            object = current.getPrototypeOf();
            keys = null;
        }
    }
}
