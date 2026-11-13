/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Realm;

/** ShadowRealm instance: {@code [[ShadowRealm]]} slot. */
public final class JSShadowRealm extends JSObject {
    public final Realm shadowRealm;

    public JSShadowRealm(JSObject prototype, Realm shadowRealm) {
        super(prototype);
        this.shadowRealm = shadowRealm;
    }

    @Override
    public String getClassName() {
        return "ShadowRealm";
    }
}
