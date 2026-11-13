/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Ops;

/** Element types of typed arrays / DataView accessors (ECMA-262 Table 71). */
public enum ElementType {
    INT8("Int8", 1, false),
    UINT8("Uint8", 1, false),
    UINT8C("Uint8Clamped", 1, false),
    INT16("Int16", 2, false),
    UINT16("Uint16", 2, false),
    INT32("Int32", 4, false),
    UINT32("Uint32", 4, false),
    FLOAT16("Float16", 2, false),
    FLOAT32("Float32", 4, false),
    FLOAT64("Float64", 8, false),
    BIGINT64("BigInt64", 8, true),
    BIGUINT64("BigUint64", 8, true);

    public final String jsName;
    public final int size;
    public final boolean isBigInt;
    public final int shift;

    ElementType(String jsName, int size, boolean isBigInt) {
        this.jsName = jsName;
        this.size = size;
        this.isBigInt = isBigInt;
        this.shift = Integer.numberOfTrailingZeros(size);
    }

    public String ctorName() {
        return jsName + "Array";
    }

    /** ToNumber / ToBigInt as required before storing into this element type. */
    public Object coerce(Object value) {
        if (isBigInt) return Ops.toBigInt(value);
        if (value instanceof Double) return value;
        return Ops.toNumber(value);
    }
}
