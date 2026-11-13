/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import static dev.mooner.neonjs.builtins.BuiltinsKt.getter;
import static dev.mooner.neonjs.builtins.BuiltinsKt.global;
import static dev.mooner.neonjs.builtins.BuiltinsKt.makeCtor;
import static dev.mooner.neonjs.builtins.BuiltinsKt.method;
import static dev.mooner.neonjs.builtins.BuiltinsKt.rangeErr;
import static dev.mooner.neonjs.builtins.BuiltinsKt.typeErr;
import static dev.mooner.neonjs.builtins.BuiltinsKt.value;
import static dev.mooner.neonjs.runtime.Values.arg;

/** DataView constructor and prototype. */
final class DataViewBuiltins {
    private DataViewBuiltins() {
    }

    static void install(Realm realm) {
        JSObject prototype = new JSObject(realm.getObjectPrototype());
        realm.intrinsics.put("%DataView.prototype%", prototype);
        var constructor = makeCtor(realm, "DataView", 1, prototype,
                (function, receiver, arguments, newTarget) -> {
                    if (newTarget == null) throw typeErr("Constructor DataView requires 'new'");
                    if (!(arg(arguments, 0) instanceof JSArrayBuffer buffer)) {
                        throw typeErr("First argument to DataView constructor must be an ArrayBuffer");
                    }
                    long offset = Ops.toIndex(arg(arguments, 1));
                    if (buffer.detached) {
                        throw typeErr("Cannot construct a DataView on a detached ArrayBuffer");
                    }
                    long bufferLength = buffer.byteLength();
                    if (offset > bufferLength) {
                        throw rangeErr("Start offset " + offset + " is outside the bounds of the buffer");
                    }
                    Object lengthArgument = arg(arguments, 2);
                    long viewByteLength;
                    if (lengthArgument == Undefined.INSTANCE) {
                        viewByteLength = buffer.isFixedLength() ? bufferLength - offset : -1L;
                    } else {
                        long length = Ops.toIndex(lengthArgument);
                        if (offset + length > bufferLength) throw rangeErr("Invalid DataView length " + length);
                        viewByteLength = length;
                    }
                    JSObject viewPrototype = Ops.getPrototypeFromConstructor(newTarget,
                            r -> r.intrinsic("%DataView.prototype%"));
                    if (buffer.detached) {
                        throw typeErr("Cannot construct a DataView on a detached ArrayBuffer");
                    }
                    bufferLength = buffer.byteLength();
                    if (offset > bufferLength) {
                        throw rangeErr("Start offset " + offset + " is outside the bounds of the buffer");
                    }
                    if (lengthArgument != Undefined.INSTANCE && offset + viewByteLength > bufferLength) {
                        throw rangeErr("Invalid DataView length " + viewByteLength);
                    }
                    return new JSDataView(viewPrototype, buffer, (int) offset, (int) viewByteLength);
                });
        realm.intrinsics.put("%DataView%", constructor);
        global(realm, "DataView", constructor);

        getter(prototype, realm, "buffer", (function, receiver, arguments, newTarget) ->
                thisView(receiver, "buffer").buffer);
        getter(prototype, realm, "byteLength", (function, receiver, arguments, newTarget) -> {
            int length = thisView(receiver, "byteLength").byteLengthOrOOB();
            if (length < 0) throw typeErr("DataView is detached or out of bounds");
            return (double) length;
        });
        getter(prototype, realm, "byteOffset", (function, receiver, arguments, newTarget) -> {
            JSDataView view = thisView(receiver, "byteOffset");
            if (view.byteLengthOrOOB() < 0) throw typeErr("DataView is detached or out of bounds");
            return (double) view.byteOffset;
        });

        for (ElementType type : ElementType.values()) {
            if (type == ElementType.UINT8C) continue;
            String name = type.jsName;
            method(prototype, realm, "get" + name, 1, (function, receiver, arguments, newTarget) -> {
                JSDataView view = thisView(receiver, "get" + name);
                long index = Ops.toIndex(arg(arguments, 0));
                boolean littleEndian = Ops.toBoolean(arg(arguments, 1));
                int at = checkAccess(view, index, type);
                return BufferOps.load(view.buffer.data, at, type, littleEndian);
            });
            method(prototype, realm, "set" + name, 2, (function, receiver, arguments, newTarget) -> {
                JSDataView view = thisView(receiver, "set" + name);
                if (view.buffer.immutable) {
                    throw typeErr("DataView.prototype.set" + name
                            + ": the DataView is backed by an immutable ArrayBuffer");
                }
                long index = Ops.toIndex(arg(arguments, 0));
                Object value = type.coerce(arg(arguments, 1));
                boolean littleEndian = Ops.toBoolean(arg(arguments, 2));
                int at = checkAccess(view, index, type);
                BufferOps.store(view.buffer.data, at, type, value, littleEndian);
                return Undefined.INSTANCE;
            });
        }
        value(prototype, JSSymbol.toStringTag, "DataView", Attr.CONFIGURABLE);
    }

    private static JSDataView thisView(Object target, String name) {
        if (target instanceof JSDataView view) return view;
        throw typeErr("Method DataView.prototype." + name
                + " called on incompatible receiver " + Ops.describe(target));
    }

    /** Bounds checks of GetViewValue / SetViewValue; returns the buffer byte index. */
    private static int checkAccess(JSDataView view, long index, ElementType type) {
        int viewSize = view.byteLengthOrOOB();
        if (viewSize < 0) throw typeErr("DataView is detached or out of bounds");
        if (index + type.size > viewSize) throw rangeErr("Offset is outside the bounds of the DataView");
        return (int) (index + view.byteOffset);
    }
}
