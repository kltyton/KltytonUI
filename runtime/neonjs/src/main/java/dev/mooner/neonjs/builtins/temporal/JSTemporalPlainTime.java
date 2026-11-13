/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.runtime.JSObject;

/** JavaScript Temporal.PlainTime instance. */
public final class JSTemporalPlainTime extends JSObject {
    public final TimeRec time;

    public JSTemporalPlainTime(JSObject proto, TimeRec time) {
        super(proto);
        this.time = time;
    }

    public TimeRec getTime() {
        return time;
    }
}
