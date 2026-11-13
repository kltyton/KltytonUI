/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.interop;

import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import java.lang.reflect.Method;

/** Runs adapter methods implemented by a JS object. */
public final class JSAdapterDelegate implements AdapterDelegate {
    private final HostBridge bridge;
    private final JSObject impl;
    private final AdapterClass adapter;

    public JSAdapterDelegate(HostBridge bridge, JSObject impl, AdapterClass adapter) {
        this.bridge = bridge;
        this.impl = impl;
        this.adapter = adapter;
    }

    @Override
    public Object invoke(Object self, int index, Object[] args) {
        return bridge.gate.enter(() -> {
            Method method = adapter.methods.get(index);
            Object function = impl.get(PK.fromString(method.getName()), impl);
            if (!Ops.isCallable(function)) return AdapterSupport.NOT_HANDLED;
            Object[] jsArgs = new Object[args.length];
            for (int i = 0; i < args.length; i++) jsArgs[i] = bridge.toJS(args[i]);
            Object result = ((JSObject) function).call(bridge.toJS(self), jsArgs);
            HostBridge.Sig sig = bridge.sig(method);
            if (sig.returnType == Void.TYPE) return null;
            return bridge.toHost(result, sig.returnType, sig.genericReturn());
        });
    }
}
