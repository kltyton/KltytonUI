/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.NumberConv;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import static dev.mooner.neonjs.builtins.BuiltinsKt.method;
import static dev.mooner.neonjs.builtins.BuiltinsKt.typeErr;
import static dev.mooner.neonjs.runtime.Values.arg;

/** Uint8Array base64 / hex conversion methods (fromBase64, fromHex, setFromBase64, setFromHex, toBase64, toHex). */
final class Uint8ArrayCodec {
    private static final int LOOSE = 0;
    private static final int STRICT = 1;
    private static final int STOP_BEFORE_PARTIAL = 2;

    private static final String STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final String HEX = "0123456789abcdef";

    private Uint8ArrayCodec() {
    }

    /** Decoding result: bytes (first {@code length} used), number of characters read, and an optional error. */
    private static final class Decoded {
        final int read;
        final byte[] bytes;
        final int length;
        final String error;

        Decoded(int read, byte[] bytes, int length, String error) {
            this.read = read;
            this.bytes = bytes;
            this.length = length;
            this.error = error;
        }
    }

    static void install(Realm realm, JSObject constructor, JSObject prototype) {
        method(constructor, realm, "fromBase64", 1, (function, receiver, arguments, newTarget) -> {
            if (!(arg(arguments, 0) instanceof CharSequence text)) {
                throw typeErr("Uint8Array.fromBase64 requires a string");
            }
            JSObject options = optionsObject(arg(arguments, 1));
            boolean url = alphabetOption(options);
            int lastChunk = lastChunkOption(options);
            Decoded decoded = fromBase64(text.toString(), url, lastChunk, Integer.MAX_VALUE);
            if (decoded.error != null) throw JSException.syntaxError(decoded.error);
            return newUint8Array(function.realm, decoded);
        });
        method(constructor, realm, "fromHex", 1, (function, receiver, arguments, newTarget) -> {
            if (!(arg(arguments, 0) instanceof CharSequence text)) {
                throw typeErr("Uint8Array.fromHex requires a string");
            }
            Decoded decoded = fromHex(text.toString(), Integer.MAX_VALUE);
            if (decoded.error != null) throw JSException.syntaxError(decoded.error);
            return newUint8Array(function.realm, decoded);
        });
        method(prototype, realm, "setFromBase64", 1, (function, receiver, arguments, newTarget) -> {
            JSTypedArray into = thisUint8Array(receiver, "setFromBase64", true);
            if (!(arg(arguments, 0) instanceof CharSequence text)) {
                throw typeErr("Uint8Array.prototype.setFromBase64 requires a string");
            }
            JSObject options = optionsObject(arg(arguments, 1));
            boolean url = alphabetOption(options);
            int lastChunk = lastChunkOption(options);
            int length = into.lengthOrOOB();
            if (length < 0) {
                throw typeErr("Uint8Array.prototype.setFromBase64: typed array is detached or out of bounds");
            }
            return setResult(function.realm, into, fromBase64(text.toString(), url, lastChunk, length));
        });
        method(prototype, realm, "setFromHex", 1, (function, receiver, arguments, newTarget) -> {
            JSTypedArray into = thisUint8Array(receiver, "setFromHex", true);
            if (!(arg(arguments, 0) instanceof CharSequence text)) {
                throw typeErr("Uint8Array.prototype.setFromHex requires a string");
            }
            int length = into.lengthOrOOB();
            if (length < 0) {
                throw typeErr("Uint8Array.prototype.setFromHex: typed array is detached or out of bounds");
            }
            return setResult(function.realm, into, fromHex(text.toString(), length));
        });
        method(prototype, realm, "toBase64", 0, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = thisUint8Array(receiver, "toBase64", false);
            JSObject options = optionsObject(arg(arguments, 0));
            boolean url = alphabetOption(options);
            boolean omitPadding = options != null
                    && Ops.toBoolean(options.get("omitPadding", options));
            int length = array.lengthOrOOB();
            if (length < 0) {
                throw typeErr("Uint8Array.prototype.toBase64: typed array is detached or out of bounds");
            }
            return toBase64(array.buffer.data, array.byteOffset, length, url ? URL : STD, !omitPadding);
        });
        method(prototype, realm, "toHex", 0, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = thisUint8Array(receiver, "toHex", false);
            int length = array.lengthOrOOB();
            if (length < 0) {
                throw typeErr("Uint8Array.prototype.toHex: typed array is detached or out of bounds");
            }
            byte[] data = array.buffer.data;
            StringBuilder builder = new StringBuilder(length * 2);
            for (int i = array.byteOffset; i < array.byteOffset + length; i++) {
                int value = data[i] & 0xFF;
                builder.append(HEX.charAt(value >>> 4)).append(HEX.charAt(value & 15));
            }
            return builder.toString();
        });
    }

    /** ValidateUint8Array; with {@code write} the buffer must not be immutable. */
    private static JSTypedArray thisUint8Array(Object target, String method, boolean write) {
        if (!(target instanceof JSTypedArray array) || array.type != ElementType.UINT8) {
            throw typeErr("Method Uint8Array.prototype." + method
                    + " called on incompatible receiver " + Ops.describe(target));
        }
        if (write && array.buffer.immutable) {
            throw typeErr("Uint8Array.prototype." + method
                    + ": typed array is backed by an immutable ArrayBuffer");
        }
        return array;
    }

    /** GetOptionsObject: null stands for an empty options object. */
    private static JSObject optionsObject(Object value) {
        if (value == Undefined.INSTANCE) return null;
        if (value instanceof JSObject object) return object;
        throw typeErr("options must be an object");
    }

    /** Reads the "alphabet" option; true for base64url. */
    private static boolean alphabetOption(JSObject options) {
        Object value = options == null ? Undefined.INSTANCE : options.get("alphabet", options);
        if (value == Undefined.INSTANCE) return false;
        if (value instanceof CharSequence text) {
            if (text.toString().equals("base64")) return false;
            if (text.toString().equals("base64url")) return true;
        }
        throw typeErr("expected alphabet to be either \"base64\" or \"base64url\"");
    }

    private static int lastChunkOption(JSObject options) {
        Object value = options == null ? Undefined.INSTANCE : options.get("lastChunkHandling", options);
        if (value == Undefined.INSTANCE) return LOOSE;
        if (value instanceof CharSequence text) {
            String option = text.toString();
            if (option.equals("loose")) return LOOSE;
            if (option.equals("strict")) return STRICT;
            if (option.equals("stop-before-partial")) return STOP_BEFORE_PARTIAL;
        }
        throw typeErr("expected lastChunkHandling to be either \"loose\", \"strict\", or \"stop-before-partial\"");
    }

    private static JSTypedArray newUint8Array(Realm realm, Decoded decoded) {
        JSTypedArray array = TypedArrayBuiltins.allocate(realm, ElementType.UINT8,
                TypedArrayBuiltins.protoOf(realm, ElementType.UINT8), decoded.length);
        System.arraycopy(decoded.bytes, 0, array.buffer.data, 0, decoded.length);
        return array;
    }

    /** SetUint8ArrayBytes, then throws a pending error or returns { read, written }. */
    private static JSObject setResult(Realm realm, JSTypedArray into, Decoded decoded) {
        System.arraycopy(decoded.bytes, 0, into.buffer.data, into.byteOffset, decoded.length);
        if (decoded.error != null) throw JSException.syntaxError(decoded.error);
        JSObject result = new JSObject(realm.getObjectPrototype());
        result.createDataPropertyOrThrow("read", (double) decoded.read);
        result.createDataPropertyOrThrow("written", (double) decoded.length);
        return result;
    }

    private static String toBase64(byte[] data, int offset, int length, String alphabet, boolean pad) {
        StringBuilder builder = new StringBuilder((length + 2) / 3 * 4);
        int i = offset;
        int end = offset + length;
        while (end - i >= 3) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            builder.append(alphabet.charAt(n >>> 18))
                    .append(alphabet.charAt((n >>> 12) & 63))
                    .append(alphabet.charAt((n >>> 6) & 63))
                    .append(alphabet.charAt(n & 63));
            i += 3;
        }
        switch (end - i) {
            case 1:
                int one = (data[i] & 0xFF) << 16;
                builder.append(alphabet.charAt(one >>> 18))
                        .append(alphabet.charAt((one >>> 12) & 63));
                if (pad) builder.append("==");
                break;
            case 2:
                int two = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
                builder.append(alphabet.charAt(two >>> 18))
                        .append(alphabet.charAt((two >>> 12) & 63))
                        .append(alphabet.charAt((two >>> 6) & 63));
                if (pad) builder.append('=');
                break;
            default:
                break;
        }
        return builder.toString();
    }

    private static int skipWhitespace(String text, int from) {
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '\t' && c != '\n' && c != '\f' && c != '\r' && c != ' ') break;
            i++;
        }
        return i;
    }

    private static int base64Value(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        return -1;
    }

    /**
     * DecodeBase64Chunk for a partial chunk; returns the new output length, or -1 when extra bits are set and must be
     * rejected.
     */
    private static int decodePartial(byte[] out, int n, int[] chunk, int chunkLength, boolean throwOnExtraBits) {
        if (chunkLength == 2) {
            if (throwOnExtraBits && (chunk[1] & 15) != 0) return -1;
            out[n++] = (byte) ((chunk[0] << 2) | (chunk[1] >>> 4));
        } else {
            if (throwOnExtraBits && (chunk[2] & 3) != 0) return -1;
            out[n++] = (byte) ((chunk[0] << 2) | (chunk[1] >>> 4));
            out[n++] = (byte) (((chunk[1] & 15) << 4) | (chunk[2] >>> 2));
        }
        return n;
    }

    /** FromBase64(string, alphabet, lastChunkHandling, maxLength) */
    private static Decoded fromBase64(String text, boolean url, int lastChunk, int maxLength) {
        byte[] out = new byte[(int) Math.min((long) maxLength, (long) text.length() / 4 * 3 + 3)];
        if (maxLength == 0) return new Decoded(0, out, 0, null);
        int n = 0;
        int read = 0;
        int[] chunk = new int[4];
        int chunkLength = 0;
        int index = 0;
        int length = text.length();
        String bad = "Invalid base64 string";

        while (true) {
            index = skipWhitespace(text, index);
            if (index == length) {
                if (chunkLength > 0) {
                    if (lastChunk == STOP_BEFORE_PARTIAL) return new Decoded(read, out, n, null);
                    if (lastChunk == LOOSE) {
                        if (chunkLength == 1) return new Decoded(read, out, n, bad);
                        int next = decodePartial(out, n, chunk, chunkLength, false);
                        n = next;
                    } else {
                        return new Decoded(read, out, n, bad);
                    }
                }
                return new Decoded(length, out, n, null);
            }
            char c = text.charAt(index++);
            if (c == '=') {
                if (chunkLength < 2) return new Decoded(read, out, n, bad);
                index = skipWhitespace(text, index);
                if (chunkLength == 2) {
                    if (index == length) {
                        if (lastChunk == STOP_BEFORE_PARTIAL) return new Decoded(read, out, n, null);
                        return new Decoded(read, out, n, bad);
                    }
                    c = text.charAt(index);
                    if (c == '=') index = skipWhitespace(text, index + 1);
                }
                if (index < length) return new Decoded(read, out, n, bad);
                int next = decodePartial(out, n, chunk, chunkLength, lastChunk == STRICT);
                if (next < 0) return new Decoded(read, out, n, bad);
                n = next;
                return new Decoded(length, out, n, null);
            }
            if (url) {
                if (c == '+' || c == '/') return new Decoded(read, out, n, bad);
                if (c == '-') c = '+';
                else if (c == '_') c = '/';
            }
            int digit = base64Value(c);
            if (digit < 0) return new Decoded(read, out, n, bad);
            int remaining = maxLength - n;
            if ((remaining == 1 && chunkLength == 2) || (remaining == 2 && chunkLength == 3)) {
                return new Decoded(read, out, n, null);
            }
            chunk[chunkLength++] = digit;
            if (chunkLength == 4) {
                out[n++] = (byte) ((chunk[0] << 2) | (chunk[1] >>> 4));
                out[n++] = (byte) (((chunk[1] & 15) << 4) | (chunk[2] >>> 2));
                out[n++] = (byte) (((chunk[2] & 3) << 6) | chunk[3]);
                chunkLength = 0;
                read = index;
                if (n == maxLength) return new Decoded(read, out, n, null);
            }
        }
    }

    /** FromHex(string, maxLength) */
    private static Decoded fromHex(String text, int maxLength) {
        int length = text.length();
        byte[] out = new byte[Math.min(maxLength, length / 2)];
        if (length % 2 != 0) return new Decoded(0, out, 0, "Hex string must have an even length");
        int read = 0;
        int n = 0;
        while (read < length && n < maxLength) {
            int high = NumberConv.digitVal(text.charAt(read), 16);
            int low = NumberConv.digitVal(text.charAt(read + 1), 16);
            if (high < 0 || low < 0 || !isAsciiHex(text.charAt(read)) || !isAsciiHex(text.charAt(read + 1))) {
                return new Decoded(read, out, n, "Invalid hex string");
            }
            read += 2;
            out[n++] = (byte) ((high << 4) | low);
        }
        return new Decoded(read, out, n, null);
    }

    private static boolean isAsciiHex(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }
}
