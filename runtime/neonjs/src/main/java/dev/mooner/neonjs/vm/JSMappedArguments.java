package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.DeclEnv;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import java.util.HashMap;

/** Arguments indices aliased to parameter bindings; derived from NeonJS and adapted to Java. */
public final class JSMappedArguments extends JSArgumentsObject {
    public final DeclEnv env;
    private HashMap<Integer, Integer> mapped;

    public JSMappedArguments(JSObject prototype, DeclEnv environment) {
        super(prototype);
        env = environment;
        special |= EXOTIC_OWN | SPECIAL_GET | SPECIAL_SET;
    }

    public void map(int index, int slot) {
        if (mapped == null) mapped = new HashMap<>();
        mapped.put(index, slot);
    }

    private int slotOf(Object key) {
        if (mapped == null || !(key instanceof Integer integer)) return -1;
        Integer slot = mapped.get(integer);
        return slot == null ? -1 : slot;
    }

    @Override
    public PropertyDescriptor getOwnProperty(Object key) {
        PropertyDescriptor descriptor = ordinaryGetOwnProperty(key);
        if (descriptor == null) return null;
        int slot = slotOf(key);
        if (slot >= 0) descriptor.value = env.slots[slot];
        return descriptor;
    }

    @Override
    public Object getOwnValue(Object key, Object receiver) {
        int slot = slotOf(key);
        return slot >= 0 ? env.slots[slot] : super.getOwnValue(key, receiver);
    }

    @Override
    public Object get(Object key, Object receiver) {
        int slot = slotOf(key);
        return slot >= 0 ? env.slots[slot] : super.get(key, receiver);
    }

    @Override
    public boolean set(Object key, Object value, Object receiver) {
        int slot = slotOf(key);
        boolean written = ordinarySet(key, value, receiver);
        if (slot >= 0 && receiver == this && written) env.slots[slot] = value;
        return written;
    }

    @Override
    public boolean defineOwnProperty(Object key, PropertyDescriptor descriptor) {
        int slot = slotOf(key);
        PropertyDescriptor actual = descriptor;
        if (slot >= 0 && descriptor.isData() && !descriptor.getHasValue()
                && descriptor.getHasWritable() && !descriptor.writable) {
            actual = new PropertyDescriptor();
            actual.present = descriptor.present;
            actual.writable = descriptor.writable;
            actual.enumerable = descriptor.enumerable;
            actual.configurable = descriptor.configurable;
            actual.value(env.slots[slot]);
        }
        if (!ordinaryDefineOwnProperty(key, actual)) return false;
        if (slot >= 0) {
            if (descriptor.isAccessor()) mapped.remove((Integer) key);
            else {
                if (descriptor.getHasValue()) env.slots[slot] = descriptor.value;
                if (descriptor.getHasWritable() && !descriptor.writable) mapped.remove((Integer) key);
            }
        }
        return true;
    }

    @Override
    public boolean delete(Object key) {
        boolean deleted = super.delete(key);
        if (deleted && slotOf(key) >= 0) mapped.remove((Integer) key);
        return deleted;
    }
}
