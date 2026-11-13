package dev.mooner.neonjs.runtime;

import java.util.ArrayList;
import java.util.List;

/** String exotic object; derived from NeonJS and adapted to Java. */
public final class JSStringObject extends JSObject {
    public final String value;

    public JSStringObject(JSObject prototype, String value) {
        super(prototype);
        this.value = value;
        special |= EXOTIC_OWN;
    }

    @Override
    public String getClassName() {
        return "String";
    }

    @Override
    public PropertyDescriptor getOwnProperty(Object key) {
        if (key instanceof Integer integer && integer < value.length()) {
            return PropertyDescriptor.data(String.valueOf(value.charAt(integer)), Attr.ENUMERABLE);
        }
        if (key.equals("length")) return PropertyDescriptor.data((double) value.length(), 0);
        return ordinaryGetOwnProperty(key);
    }

    @Override
    public Object getOwnValue(Object key, Object receiver) {
        if (key instanceof Integer integer && integer < value.length()) return String.valueOf(value.charAt(integer));
        if (key.equals("length")) return (double) value.length();
        return super.getOwnValue(key, receiver);
    }

    @Override
    public boolean icSafeKey(Object key) {
        return key instanceof String && !key.equals("length");
    }

    @Override
    public boolean hasOwnProperty(Object key) {
        if (key instanceof Integer integer && integer < value.length()) return true;
        if (key.equals("length")) return true;
        return props != null && props.find(key) >= 0;
    }

    @Override
    public boolean defineOwnProperty(Object key, PropertyDescriptor descriptor) {
        if ((key instanceof Integer integer && integer < value.length()) || key.equals("length")) {
            return Ops.isCompatiblePropertyDescriptor(extensible, descriptor, getOwnProperty(key));
        }
        return ordinaryDefineOwnProperty(key, descriptor);
    }

    @Override
    public boolean delete(Object key) {
        return !((key instanceof Integer integer && integer < value.length()) || key.equals("length"))
                && super.delete(key);
    }

    @Override
    public List<Object> ownPropertyKeys() {
        List<Object> extra = new ArrayList<>(value.length());
        for (int i = 0; i < value.length(); i++) extra.add(i);
        List<Object> keys = ordinaryOwnKeys(extra);
        int firstNonIndex = 0;
        while (firstNonIndex < keys.size() && PK.arrayIndex(keys.get(firstNonIndex)) >= 0) firstNonIndex++;
        keys.add(firstNonIndex, "length");
        return keys;
    }
}
