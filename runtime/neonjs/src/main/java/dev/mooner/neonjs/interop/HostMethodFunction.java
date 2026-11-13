/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.interop;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSFunction;
import dev.mooner.neonjs.runtime.Ops;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/** Callable wrapping one or more host method overloads, optionally bound to a receiver. */
public class HostMethodFunction extends JSFunction {
    public final HostBridge bridge;
    public final List<Method> overloads;
    public final Object target;
    private HostBridge.Overloads choice;

    public HostMethodFunction(HostBridge bridge, String name, List<Method> overloads, Object target) {
        super(bridge.realm, bridge.realm.getFunctionPrototype());
        this.bridge = bridge;
        this.overloads = overloads;
        this.target = target;
        int length = Integer.MAX_VALUE;
        for (Method method : overloads) length = Math.min(length, method.getParameterCount());
        if (length == Integer.MAX_VALUE) length = 0;
        defineOwn("length", (double) length, Attr.CONFIGURABLE);
        defineOwn("name", name, Attr.CONFIGURABLE);
    }

    @Override
    public Object call(Object thisArg, Object[] args) {
        Object receiver = target;
        if (receiver == null && !Modifier.isStatic(overloads.get(0).getModifiers())) {
            // unbound instance method: use thisArg
            Object normalized = bridge.gate.normalizeGuest(thisArg);
            if (!(normalized instanceof HostObject object)) {
                throw JSException.typeError("Host method " + debugName() + " called on incompatible receiver");
            }
            receiver = object.target;
        }
        HostBridge.Overloads selected = choice;
        if (selected == null) {
            selected = bridge.overloads(overloads);
            choice = selected;
        }
        int index = bridge.selectIndex(selected, args);
        if (index < 0) {
            throw JSException.typeError("No applicable overload for " + debugName()
                    + " with arguments (" + joinTypes(args) + ")");
        }
        return bridge.invoke(overloads.get(index), selected.sigs[index], receiver, args);
    }

    private static String joinTypes(Object[] args) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(Ops.typeOf(args[i]));
        }
        return builder.toString();
    }

    @Override
    public String sourceText() {
        return "function " + debugName() + "() { [native code] }";
    }
}
