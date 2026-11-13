/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.BoundFunction;
import dev.mooner.neonjs.vm.Evaluator;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSFunction;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Values;
import java.util.Arrays;

/** Function constructor and prototype; derived from NeonJS and adapted to Java. */
final class FunctionBuiltins {
    private FunctionBuiltins() {
    }

    static void install(Realm realm) {
        JSObject proto = realm.functionPrototype;
        NativeFunction constructor = BuiltinsKt.makeCtor(realm, "Function", 1, proto,
                (function, thisValue, args, newTarget) ->
                        Evaluator.createDynamicFunction(function.realm, newTarget != null ? newTarget : function,
                                "normal", args));
        realm.functionConstructor = constructor;
        BuiltinsKt.global(realm, "Function", constructor);

        BuiltinsKt.method(proto, realm, "apply", 2, (function, thisValue, args, newTarget) -> {
            if (!Ops.isCallable(thisValue)) {
                throw BuiltinsKt.typeErr("Function.prototype.apply was called on "
                        + Ops.describe(thisValue) + ", which is not a function");
            }
            Object array = Values.arg(args, 1);
            Object[] list = array == dev.mooner.neonjs.runtime.Undefined.INSTANCE
                    || array == dev.mooner.neonjs.runtime.Null.INSTANCE
                    ? Values.EMPTY_ARGS : Ops.createListFromArrayLike(array);
            return ((JSObject) thisValue).call(Values.arg(args, 0), list);
        });
        BuiltinsKt.method(proto, realm, "bind", 1, (function, thisValue, args, newTarget) -> {
            if (!Ops.isCallable(thisValue)) throw BuiltinsKt.typeErr("Bind must be called on a function");
            JSObject target = (JSObject) thisValue;
            Object[] boundArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : Values.EMPTY_ARGS;
            BoundFunction bound = new BoundFunction(target, Values.arg(args, 0), boundArgs,
                    target.getPrototypeOf());
            double length = 0.0;
            if (target.hasOwnProperty("length")) {
                Object targetLength = target.get("length", target);
                if (targetLength instanceof Double value) {
                    if (value == Double.POSITIVE_INFINITY) length = value;
                    else if (value == Double.NEGATIVE_INFINITY) length = 0.0;
                    else length = Math.max(0.0, Ops.integerPart(value) - boundArgs.length);
                }
            }
            bound.defineOwn("length", length, Attr.CONFIGURABLE);
            Object targetName = target.get("name", target);
            bound.defineOwn("name", "bound " + (targetName instanceof CharSequence text ? text.toString() : ""),
                    Attr.CONFIGURABLE);
            return bound;
        });
        BuiltinsKt.method(proto, realm, "call", 1, (function, thisValue, args, newTarget) -> {
            if (!Ops.isCallable(thisValue)) {
                throw BuiltinsKt.typeErr("Function.prototype.call was called on "
                        + Ops.describe(thisValue) + ", which is not a function");
            }
            Object[] callArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : Values.EMPTY_ARGS;
            return ((JSObject) thisValue).call(Values.arg(args, 0), callArgs);
        });
        BuiltinsKt.method(proto, realm, "toString", 0, (function, thisValue, args, newTarget) -> {
            if (thisValue instanceof JSFunction source) return source.sourceText();
            if (thisValue instanceof JSObject object && object.isCallable()) {
                return "function () { [native code] }";
            }
            throw BuiltinsKt.typeErr("Function.prototype.toString requires that 'this' be a Function");
        });
        BuiltinsKt.method(proto, realm, JSSymbol.hasInstance, 1, Attr.NONE,
                (function, thisValue, args, newTarget) ->
                        Ops.ordinaryHasInstance(thisValue, Values.arg(args, 0)));
        proto.defineAccessor("caller", realm.throwTypeError, realm.throwTypeError, Attr.CONFIGURABLE);
        proto.defineAccessor("arguments", realm.throwTypeError, realm.throwTypeError, Attr.CONFIGURABLE);
    }
}
