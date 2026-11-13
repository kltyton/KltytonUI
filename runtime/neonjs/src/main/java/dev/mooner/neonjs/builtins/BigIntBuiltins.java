/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSPrimitiveWrapper;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.NumberConv;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;

import java.math.BigInteger;

/** BigInt built-ins; derived from NeonJS and adapted to Java. */
final class BigIntBuiltins {
    private BigIntBuiltins() {
    }

    static BigInteger thisBigInt(Object value, String method) {
        if (value instanceof BigInteger integer) return integer;
        if (value instanceof JSPrimitiveWrapper wrapper) {
            if (wrapper.primitive instanceof BigInteger integer) return integer;
            throw BuiltinsKt.typeErr("BigInt.prototype." + method + " requires that 'this' be a BigInt");
        }
        throw BuiltinsKt.typeErr("BigInt.prototype." + method + " requires that 'this' be a BigInt");
    }

    static void install(Realm realm) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.bigintPrototype = proto;
        NativeFunction ctor = BuiltinsKt.makeCtor(realm, "BigInt", 1, proto, (function, receiver, args, newTarget) -> {
            if (newTarget != null) throw BuiltinsKt.typeErr("BigInt is not a constructor");
            Object primitive = Ops.toPrimitive(Values.arg(args, 0), Ops.HINT_NUMBER);
            if (primitive instanceof Double number) {
                if (!Ops.isIntegral(number)) {
                    throw BuiltinsKt.rangeErr("The number " + NumberConv.toString(number)
                            + " cannot be converted to a BigInt because it is not an integer");
                }
                return Ops.bigIntFromDouble(number);
            }
            return Ops.toBigInt(primitive);
        });
        BuiltinsKt.global(realm, "BigInt", ctor);
        BuiltinsKt.method(ctor, realm, "asIntN", 2, (function, receiver, args, newTarget) -> {
            long bits = Ops.toIndex(Values.arg(args, 0));
            BigInteger b = Ops.toBigInt(Values.arg(args, 1));
            if (bits == 0L) return BigInteger.ZERO;
            if (bits > Ops.MAX_BIGINT_BITS) {
                if (b.bitLength() < bits) return b;
                throw BuiltinsKt.rangeErr("Maximum BigInt size exceeded");
            }
            int n = (int) bits;
            BigInteger mod = b.mod(BigInteger.ONE.shiftLeft(n));
            if (mod.testBit(n - 1)) return mod.subtract(BigInteger.ONE.shiftLeft(n));
            return mod;
        });
        BuiltinsKt.method(ctor, realm, "asUintN", 2, (function, receiver, args, newTarget) -> {
            long bits = Ops.toIndex(Values.arg(args, 0));
            BigInteger b = Ops.toBigInt(Values.arg(args, 1));
            if (bits > Ops.MAX_BIGINT_BITS) {
                if (b.signum() >= 0 && b.bitLength() <= bits) return b;
                throw BuiltinsKt.rangeErr("Maximum BigInt size exceeded");
            }
            return b.mod(BigInteger.ONE.shiftLeft((int) bits));
        });
        BuiltinsKt.method(proto, realm, "toLocaleString", 0, (function, receiver, args, newTarget) ->
                thisBigInt(receiver, "toLocaleString").toString());
        BuiltinsKt.method(proto, realm, "toString", 0, (function, receiver, args, newTarget) -> {
            BigInteger x = thisBigInt(receiver, "toString");
            Object r = Values.arg(args, 0);
            double radix = r == Undefined.INSTANCE ? 10.0 : Ops.toIntegerOrInfinity(r);
            if (radix < 2 || radix > 36) throw BuiltinsKt.rangeErr("toString() radix must be between 2 and 36");
            return x.toString((int) radix);
        });
        BuiltinsKt.method(proto, realm, "valueOf", 0, (function, receiver, args, newTarget) ->
                thisBigInt(receiver, "valueOf"));
        BuiltinsKt.value(proto, JSSymbol.toStringTag, "BigInt", Attr.CONFIGURABLE);
    }
}
