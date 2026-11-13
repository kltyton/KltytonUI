/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.runtime.JSObject;

import java.math.BigInteger;

/** JavaScript Temporal.Instant instance. */
public final class JSTemporalInstant extends JSObject {
    public final BigInteger epochNs;

    public JSTemporalInstant(JSObject proto, BigInteger epochNs) {
        super(proto);
        this.epochNs = epochNs;
    }

    public BigInteger getEpochNs() {
        return epochNs;
    }
}
