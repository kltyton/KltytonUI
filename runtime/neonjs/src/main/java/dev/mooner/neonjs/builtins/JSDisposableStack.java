/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSObject;

/** DisposableStack / AsyncDisposableStack instance. */
public final class JSDisposableStack extends JSObject {
    public final boolean isAsync;
    public boolean disposed = false;
    public DisposeCapability capability = new DisposeCapability();

    public JSDisposableStack(JSObject proto, boolean isAsync) {
        super(proto);
        this.isAsync = isAsync;
    }

    public boolean isAsync() {
        return isAsync;
    }

    public boolean isDisposed() {
        return disposed;
    }

    public DisposeCapability getCapability() {
        return capability;
    }
}
