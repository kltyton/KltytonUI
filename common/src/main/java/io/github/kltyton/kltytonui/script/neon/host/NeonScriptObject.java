package io.github.kltyton.kltytonui.script.neon.host;

import io.github.kltyton.kltytonui.script.neon.NeonDocumentRuntime;
import dev.mooner.neonjs.NeonValue;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A live Java map view over a JavaScript object. */
public final class NeonScriptObject extends AbstractMap<String, Object> implements NeonScriptValue {
    private final NeonDocumentRuntime owner;
    private final NeonValue source;
    public NeonScriptObject(NeonDocumentRuntime owner, NeonValue source) {
        this.owner = Objects.requireNonNull(owner);
        this.source = Objects.requireNonNull(source);
    }
    @Override public NeonDocumentRuntime owner() { return owner; }
    @Override public NeonValue toGuestValue() { return owner.accessValue(context -> source); }
    @Override public boolean containsKey(Object key) {
        return key instanceof String property && owner.accessValue(context -> owner.engine().api().callMember("hasOwn", source, property).asBoolean());
    }
    @Override public Object get(Object key) {
        if (!(key instanceof String property)) return null;
        return owner.accessValue(context -> source.hasMember(property)
                ? converter().fromGuest(owner.engine().api().callMember("read", source, property)) : null);
    }
    @Override public Object put(String key, Object value) {
        Objects.requireNonNull(key);
        return owner.accessValue(context -> {
            Object previous = containsKey(key) ? get(key) : null;
            if (!owner.engine().api().callMember("write", source, key, converter().toGuest(value)).asBoolean()) {
                throw new IllegalStateException("JavaScript property is not writable: " + key);
            }
            return previous;
        });
    }
    @Override public Object remove(Object key) {
        if (!(key instanceof String property)) return null;
        return owner.accessValue(context -> {
            if (!containsKey(property)) return null;
            Object previous = get(property);
            if (!owner.engine().api().callMember("remove", source, property).asBoolean()) {
                throw new IllegalStateException("JavaScript property cannot be deleted: " + property);
            }
            return previous;
        });
    }
    private List<String> keys() {
        return owner.accessValue(context -> {
            NeonValue names = owner.engine().api().callMember("ownNames", source);
            List<String> result = new ArrayList<>();
            for (long i = 0; i < names.getArraySize(); i++) result.add(names.getElement(i).asString());
            return result;
        });
    }
    @Override public int size() { return keys().size(); }
    @Override public Set<Entry<String, Object>> entrySet() {
        List<String> keys = keys();
        return new AbstractSet<>() {
            @Override public int size() { return keys.size(); }
            @Override public Iterator<Entry<String, Object>> iterator() {
                Iterator<String> iterator = keys.iterator();
                return new Iterator<>() {
                    private String current;
                    private boolean removable;
                    @Override public boolean hasNext() { return iterator.hasNext(); }
                    @Override public Entry<String, Object> next() {
                        current = iterator.next();
                        removable = true;
                        String key = current;
                        return new SimpleEntry<>(key, NeonScriptObject.this.get(key)) {
                            @Override public Object setValue(Object value) {
                                Object previous = NeonScriptObject.this.put(key, value);
                                super.setValue(value);
                                return previous;
                            }
                        };
                    }
                    @Override public void remove() {
                        if (!removable) throw new IllegalStateException();
                        NeonScriptObject.this.remove(current);
                        removable = false;
                    }
                };
            }
        };
    }
    private NeonHostConverter converter() { return owner.engine().converter(); }
}

