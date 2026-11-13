package io.github.kltyton.kltytonui.script.neon.host;

import io.github.kltyton.kltytonui.script.neon.NeonDocumentRuntime;
import dev.mooner.neonjs.NeonValue;
import java.util.AbstractList;
import java.util.Objects;

/** A live Java list view over a JavaScript array or typed array. */
public final class NeonScriptArray extends AbstractList<Object> implements NeonScriptValue {
    private final NeonDocumentRuntime owner;
    private final NeonValue source;
    public NeonScriptArray(NeonDocumentRuntime owner, NeonValue source) {
        this.owner = Objects.requireNonNull(owner);
        this.source = Objects.requireNonNull(source);
    }
    @Override public NeonDocumentRuntime owner() { return owner; }
    @Override public NeonValue toGuestValue() { return owner.accessValue(context -> source); }
    @Override public int size() { return owner.accessValue(context -> source.getMember("length").asInt()); }
    @Override public Object get(int index) {
        return owner.accessValue(context -> {
            Objects.checkIndex(index, size());
            return converter().fromGuest(owner.engine().api().callMember("read", source, index));
        });
    }
    @Override public Object set(int index, Object value) {
        return owner.accessValue(context -> {
            Object previous = get(index);
            if (!owner.engine().api().callMember("write", source, index, converter().toGuest(value)).asBoolean()) {
                throw new IllegalStateException("JavaScript array element is not writable");
            }
            return previous;
        });
    }
    @Override public void add(int index, Object value) {
        owner.accessValue(context -> {
            if (index < 0 || index > size()) throw new IndexOutOfBoundsException(index);
            owner.engine().api().callMember("call", source, "splice", index, 0, converter().toGuest(value));
            return null;
        });
    }
    @Override public boolean add(Object value) {
        owner.accessValue(context -> owner.engine().api().callMember("call", source, "push", converter().toGuest(value)));
        return true;
    }
    @Override public Object remove(int index) {
        return owner.accessValue(context -> {
            Objects.checkIndex(index, size());
            NeonValue removed = owner.engine().api().callMember("call", source, "splice", index, 1);
            return converter().fromGuest(owner.engine().api().callMember("read", removed, 0));
        });
    }
    private NeonHostConverter converter() { return owner.engine().converter(); }
}

