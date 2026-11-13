/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.ext;

import dev.mooner.neonjs.builtins.ElementType;
import dev.mooner.neonjs.builtins.JSArrayBuffer;
import dev.mooner.neonjs.builtins.JSDataView;
import dev.mooner.neonjs.builtins.JSTypedArray;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import java.security.SecureRandom;
import java.util.Set;
import static dev.mooner.neonjs.ext.Idl.typeErr;

/** {@code crypto.getRandomValues} and {@code crypto.randomUUID}. */
final class Crypto {
    private Crypto() {
    }

    static final class JSCrypto extends JSObject {
        JSCrypto(JSObject prototype) {
            super(prototype);
        }

        @Override
        public String getClassName() {
            return "Crypto";
        }
    }

    private static final SecureRandom SECURE = new SecureRandom();

    static void randomBytes(Realm realm, byte[] out) {
        java.util.Random source = realm.agent.randomSource;
        if (source != null) source.nextBytes(out);
        else SECURE.nextBytes(out);
    }

    private static final Set<ElementType> INTEGER_TYPES = Set.of(
            ElementType.INT8, ElementType.UINT8, ElementType.UINT8C, ElementType.INT16, ElementType.UINT16,
            ElementType.INT32, ElementType.UINT32, ElementType.BIGINT64, ElementType.BIGUINT64);

    static void install(Realm realm) {
        WebInterface iface = WebInterface.define(realm, "Crypto", 0);
        iface.operation("getRandomValues", 1, (function, receiver, arguments, newTarget) -> {
            Idl.self(JSCrypto.class, receiver, "Crypto", "getRandomValues");
            Idl.required(arguments, 1, "Crypto.getRandomValues");
            Object value = arguments[0];
            JSArrayBuffer buffer;
            if (value instanceof JSTypedArray array) buffer = array.buffer;
            else if (value instanceof JSDataView view) buffer = view.buffer;
            else throw typeErr("Crypto.getRandomValues: the argument is not an ArrayBufferView");
            if (buffer.isShared()) {
                throw typeErr("Crypto.getRandomValues: the array is a view of a SharedArrayBuffer");
            }
            if (!(value instanceof JSTypedArray array)) {
                throw WebGlobals.domException(function.realm,
                        "getRandomValues takes an integer typed array", "TypeMismatchError");
            }
            if (!INTEGER_TYPES.contains(array.type)) {
                throw WebGlobals.domException(function.realm,
                        "getRandomValues takes an integer typed array", "TypeMismatchError");
            }
            int length = Math.max(array.lengthOrOOB(), 0) * array.type.size;
            if (length > 65536) {
                throw new JSException(WebGlobals.newQuotaExceededError(function.realm,
                        "getRandomValues takes at most 65536 bytes"));
            }
            byte[] bytes = new byte[length];
            randomBytes(function.realm, bytes);
            System.arraycopy(bytes, 0, array.buffer.data, array.byteOffset, length);
            return array;
        });
        iface.operation("randomUUID", 0, (function, receiver, arguments, newTarget) -> {
            Idl.self(JSCrypto.class, receiver, "Crypto", "randomUUID");
            byte[] bytes = new byte[16];
            randomBytes(function.realm, bytes);
            bytes[6] = (byte) (((bytes[6] & 0x0F) | 0x40));
            bytes[8] = (byte) (((bytes[8] & 0x3F) | 0x80));
            StringBuilder hex = new StringBuilder(36);
            for (int k = 0; k < 16; k++) {
                if (k == 4 || k == 6 || k == 8 || k == 10) hex.append('-');
                hex.append(Character.forDigit((bytes[k] >> 4) & 0xF, 16))
                        .append(Character.forDigit(bytes[k] & 0xF, 16));
            }
            return hex.toString();
        });
        JSCrypto crypto = new JSCrypto(iface.proto);
        NativeFunction getter = new NativeFunction(realm, "crypto", 0,
                (function, receiver, arguments, newTarget) -> crypto,
                false, realm.getFunctionPrototype(), "get");
        realm.getGlobalObject().defineAccessor("crypto", getter, Undefined.INSTANCE,
                Attr.ENUMERABLE | Attr.CONFIGURABLE);
    }
}
