/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import java.util.List;

/** The DisposeResources loop of an async disposal, suspended at each Await. */
public final class AsyncDisposal {
    private final Realm realm;
    private final List<DisposableResource> resources;
    private int index;
    private boolean needsAwait = false;
    private boolean hasAwaited = false;
    private Throwable result;
    private Object awaitValue = Undefined.INSTANCE;

    AsyncDisposal(Realm realm, List<DisposableResource> resources, Throwable completion) {
        this.realm = realm;
        this.resources = resources;
        this.index = resources.size() - 1;
        this.result = completion;
    }

    public Throwable getResult() {
        return result;
    }

    public Object getAwaitValue() {
        return awaitValue;
    }

    public boolean step() {
        while (index >= 0) {
            DisposableResource resource = resources.get(index);
            if (resource.hint == DisposeHint.SYNC && needsAwait && !hasAwaited) {
                needsAwait = false;
                awaitValue = Undefined.INSTANCE;
                return true;
            }
            index--;
            if (resource.method == Undefined.INSTANCE) {
                needsAwait = true;
                continue;
            }
            Object value;
            try {
                value = Ops.call(resource.method, resource.value, Values.EMPTY_ARGS);
            } catch (Exception | Error error) {
                result = DisposeCapability.suppress(realm, result, error);
                continue;
            }
            if (resource.hint == DisposeHint.ASYNC) {
                hasAwaited = true;
                awaitValue = value;
                return true;
            }
        }
        if (needsAwait && !hasAwaited) {
            needsAwait = false;
            awaitValue = Undefined.INSTANCE;
            return true;
        }
        return false;
    }

    public void rejected(Object reason) {
        result = DisposeCapability.suppress(realm, result, new JSException(reason));
    }
}
