package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.PK;
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
                int attributes = current.getOwnPropertyAttributes(key);
                if (attributes < 0) continue;
                visited.add(key);
                if ((attributes & Attr.ENUMERABLE) != 0) return PK.toValue(key);
            }
            object = current.getPrototypeOf();
            keys = null;
        }
    }
}
