/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.interop;

import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import java.lang.reflect.Type;
import java.util.AbstractList;

/** Live java.util.List view of a JS array. */
public final class JSListView extends AbstractList<Object> {
    final HostBridge bridge;
    final JSObject arr;
    private final Type elementType;

    public JSListView(HostBridge bridge, JSObject arr, Type elementType) {
        this.bridge = bridge;
        this.arr = arr;
        this.elementType = elementType;
    }

    @Override
    public int size() {
        return bridge.gate.enter(() -> (int) Ops.lengthOfArrayLike(arr));
    }

    @Override
    public Object get(int index) {
        return bridge.gate.enter(() -> {
            if (index < 0 || index >= Ops.lengthOfArrayLike(arr)) {
                throw new IndexOutOfBoundsException(Integer.toString(index));
            }
            return bridge.toHost(arr.get(index, arr), HostTypeBindings.raw(elementType), elementType);
        });
    }

    @Override
    public Object set(int index, Object element) {
        return bridge.gate.enter(() -> {
            Object old = arr.get(index, arr);
            arr.setOrThrow(index, bridge.toJS(element));
            return bridge.toHost(old, HostTypeBindings.raw(elementType), elementType);
        });
    }

    @Override
    public void add(int index, Object element) {
        bridge.gate.enter(() -> Ops.invoke(arr, "splice",
                new Object[]{(double) index, 0.0, bridge.toJS(element)}));
    }

    @Override
    public Object remove(int index) {
        return bridge.gate.enter(() -> {
            Object result = Ops.invoke(arr, "splice", new Object[]{(double) index, 1.0});
            JSObject array = (JSObject) result;
            return bridge.toHost(array.get(0, array), HostTypeBindings.raw(elementType), elementType);
        });
    }
}
