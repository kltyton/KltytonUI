/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

/** DisposableResource Record. */
public final class DisposableResource {
    public final Object value;
    public final DisposeHint hint;
    public final Object method;

    public DisposableResource(Object value, DisposeHint hint, Object method) {
        this.value = value;
        this.hint = hint;
        this.method = method;
    }

    public Object getValue() {
        return value;
    }

    public DisposeHint getHint() {
        return hint;
    }

    public Object getMethod() {
        return method;
    }
}
