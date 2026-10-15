package io.github.kltyton.kltytonui.script.host;

import dev.latvian.mods.rhino.Scriptable;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/** Keeps script identity with the native object without rooting retired DOM nodes and events. */
public final class ScriptHostBindings {
    private IdentityHashMap<RuntimeCache, RhinoHostObject> wrappers;

    /** Weak tracking and release, serialized by the owning runtime's lock. */
    public static final class RuntimeCache {
        private final ReferenceQueue<RhinoHostObject> collected = new ReferenceQueue<>();
        private final Set<WeakReference<RhinoHostObject>> tracked = new HashSet<>();

        public RhinoHostObject wrap(KuiScriptHost host, Scriptable delegate, Scriptable scope) {
            ScriptHostBindings bindings = host.getScriptBindings();
            synchronized (bindings) {
                if (bindings.wrappers == null) bindings.wrappers = new IdentityHashMap<>(1);
                RhinoHostObject cached = bindings.wrappers.get(this);
                if (cached != null) return cached;
                pruneCollected();
                RhinoHostObject wrapped = new RhinoHostObject(host, delegate, scope);
                bindings.wrappers.put(this, wrapped);
                tracked.add(new WeakReference<>(wrapped, collected));
                return wrapped;
            }
        }

        public void clear() {
            for (WeakReference<RhinoHostObject> reference : tracked) {
                RhinoHostObject wrapped = reference.get();
                if (wrapped == null) continue;
                ScriptHostBindings bindings = ((KuiScriptHost) wrapped.unwrap()).getScriptBindings();
                synchronized (bindings) {
                    bindings.wrappers.remove(this, wrapped);
                    if (bindings.wrappers.isEmpty()) bindings.wrappers = null;
                }
            }
            tracked.clear();
            pruneCollected();
        }

        private void pruneCollected() {
            Reference<? extends RhinoHostObject> reference;
            while ((reference = collected.poll()) != null) tracked.remove(reference);
        }
    }
}
