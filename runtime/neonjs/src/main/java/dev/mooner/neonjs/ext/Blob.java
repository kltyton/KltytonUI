/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.ext;

import dev.mooner.neonjs.builtins.ArrayBufferBuiltins;
import dev.mooner.neonjs.builtins.ElementType;
import dev.mooner.neonjs.builtins.JSArrayBuffer;
import dev.mooner.neonjs.builtins.JSDataView;
import dev.mooner.neonjs.builtins.JSTypedArray;
import dev.mooner.neonjs.builtins.TypedArrayBuiltins;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static dev.mooner.neonjs.ext.Idl.typeErr;
import static dev.mooner.neonjs.runtime.Values.arg;

/** {@code Blob} and {@code File} (File API). */
final class Blob {
    private Blob() {
    }

    static class JSBlob extends JSObject {
        final byte[] data;
        final int offset;
        final int size;
        final String type;

        JSBlob(JSObject prototype, byte[] data, int offset, int size, String type) {
            super(prototype);
            this.data = data;
            this.offset = offset;
            this.size = size;
            this.type = type;
        }

        @Override
        public String getClassName() {
            return "Blob";
        }

        byte[] bytes() {
            return java.util.Arrays.copyOfRange(data, offset, offset + size);
        }
    }

    static final class JSFile extends JSBlob {
        final String name;
        final double lastModified;

        JSFile(JSObject prototype, byte[] data, int offset, int size, String type,
               String name, double lastModified) {
            super(prototype, data, offset, size, type);
            this.name = name;
            this.lastModified = lastModified;
        }

        @Override
        public String getClassName() {
            return "File";
        }
    }

    static JSBlob newBlob(Realm realm, byte[] bytes, String type) {
        return new JSBlob(realm.intrinsic("%Blob.prototype%"), bytes, 0, bytes.length, type);
    }

    static JSFile newFile(Realm realm, byte[] bytes, String type, String name, double lastModified) {
        return new JSFile(realm.intrinsic("%File.prototype%"), bytes, 0, bytes.length,
                type, name, lastModified);
    }

    /** A blob's type: "" unless all of it is printable ASCII, then in lower case. */
    private static String normalizeType(String type) {
        for (int i = 0; i < type.length(); i++) {
            char c = type.charAt(i);
            if (c < 0x20 || c > 0x7E) return "";
        }
        return type.toLowerCase(Locale.ROOT);
    }

    /** A {@code sequence<BlobPart>} argument: each part's bytes, blob or string. */
    private static List<Object> convertParts(Realm realm, Object parts, String what) {
        if (parts == Undefined.INSTANCE) return List.of();
        return Idl.sequence(realm, parts, what + " blobParts", item -> blobPart(item, what));
    }

    /** "process blob parts": the bytes of the parts, strings with their line endings converted when native. */
    private static byte[] processParts(Realm realm, List<Object> items, boolean nativeEndings, String what) {
        long total = 0;
        ArrayList<Object> chunks = new ArrayList<>(items.size());
        for (Object item : items) {
            Object chunk = item instanceof String text
                    ? WebGlobals.utf8(nativeEndings ? nativeLineEndings(text) : text) : item;
            total += chunk instanceof JSBlob blob ? blob.size : ((byte[]) chunk).length;
            if (total > Integer.MAX_VALUE - 8) {
                throw JSException.rangeError(what + ": the blob is too large");
            }
            chunks.add(chunk);
        }
        realm.agent.reserveAllocation(total);
        byte[] out = new byte[(int) total];
        int at = 0;
        for (Object chunk : chunks) {
            if (chunk instanceof JSBlob blob) {
                System.arraycopy(blob.data, blob.offset, out, at, blob.size);
                at += blob.size;
            } else {
                byte[] bytes = (byte[]) chunk;
                System.arraycopy(bytes, 0, out, at, bytes.length);
                at += bytes.length;
            }
        }
        return out;
    }

    private static Object blobPart(Object value, String what) {
        if (value instanceof JSArrayBuffer buffer) {
            return buffer.isShared() ? Idl.usvString(value) : Idl.bytes(value, what + " part", false);
        }
        if (value instanceof JSTypedArray || value instanceof JSDataView) {
            return Idl.bytes(value, what + " part", false);
        }
        if (value instanceof JSBlob) return value;
        return Idl.usvString(value);
    }

    private static String nativeLineEndings(String text) {
        String nativeSeparator = System.lineSeparator();
        if (text.indexOf('\r') < 0 && nativeSeparator.equals("\n")) return text;
        StringBuilder builder = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\r') {
                builder.append(nativeSeparator);
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            } else if (c == '\n') {
                builder.append(nativeSeparator);
            } else {
                builder.append(c);
            }
            i++;
        }
        return builder.toString();
    }

    private record BlobOptions(boolean nativeEndings, String type) {
    }

    /** BlobPropertyBag members in lexicographic order: endings, then type. */
    private static BlobOptions blobOptions(JSObject options, String what) {
        Object endingsValue = Idl.member(options, "endings");
        String endings = endingsValue == Undefined.INSTANCE ? "transparent" : Idl.domString(endingsValue);
        if (!endings.equals("transparent") && !endings.equals("native")) {
            throw typeErr(what + ": '" + endings + "' is not a valid value for endings");
        }
        Object typeValue = Idl.member(options, "type");
        String type = typeValue == Undefined.INSTANCE ? "" : normalizeType(Idl.domString(typeValue));
        return new BlobOptions(endings.equals("native"), type);
    }

    static void install(Realm realm) {
        WebInterface blobInterface = WebInterface.define(realm, "Blob", 0, null, null,
                (arguments, prototype) -> {
                    List<Object> parts = convertParts(realm, arg(arguments, 0), "Blob");
                    BlobOptions options = blobOptions(Idl.dictionary(arg(arguments, 1), "BlobPropertyBag"),
                            "Blob");
                    byte[] data = processParts(realm, parts, options.nativeEndings(), "Blob");
                    return new JSBlob(prototype, data, 0, data.length, options.type());
                });
        blobInterface.attribute("size", (function, receiver, arguments, newTarget) ->
                (double) Idl.self(JSBlob.class, receiver, "Blob", "size").size);
        blobInterface.attribute("type", (function, receiver, arguments, newTarget) ->
                Idl.self(JSBlob.class, receiver, "Blob", "type").type);
        blobInterface.operation("slice", 0, (function, receiver, arguments, newTarget) -> {
            JSBlob blob = Idl.self(JSBlob.class, receiver, "Blob", "slice");
            Double start = arg(arguments, 0) == Undefined.INSTANCE ? null
                    : Idl.integer(arg(arguments, 0), 64, true, "Blob.slice start", false, true);
            Double end = arg(arguments, 1) == Undefined.INSTANCE ? null
                    : Idl.integer(arg(arguments, 1), 64, true, "Blob.slice end", false, true);
            String contentType = arg(arguments, 2) == Undefined.INSTANCE ? ""
                    : normalizeType(Idl.domString(arg(arguments, 2)));
            double size = blob.size;
            double from = start == null ? 0.0 : start < 0 ? Math.max(size + start, 0.0) : Math.min(start, size);
            double to = end == null ? size : end < 0 ? Math.max(size + end, 0.0) : Math.min(end, size);
            int span = (int) Math.max(to - from, 0.0);
            return new JSBlob(function.realm.intrinsic("%Blob.prototype%"), blob.data,
                    blob.offset + (int) from, span, contentType);
        });
        blobInterface.promiseOperation("text", 0, (function, receiver, arguments, newTarget) ->
                WebGlobals.utf8Decode(function.realm,
                        Idl.self(JSBlob.class, receiver, "Blob", "text").bytes()));
        blobInterface.promiseOperation("arrayBuffer", 0, (function, receiver, arguments, newTarget) -> {
            JSBlob blob = Idl.self(JSBlob.class, receiver, "Blob", "arrayBuffer");
            JSArrayBuffer buffer = ArrayBufferBuiltins.allocate(function.realm, null, blob.size, -1);
            System.arraycopy(blob.data, blob.offset, buffer.data, 0, blob.size);
            return buffer;
        });
        blobInterface.promiseOperation("bytes", 0, (function, receiver, arguments, newTarget) -> {
            JSBlob blob = Idl.self(JSBlob.class, receiver, "Blob", "bytes");
            JSTypedArray array = TypedArrayBuiltins.allocate(function.realm, ElementType.UINT8,
                    TypedArrayBuiltins.protoOf(function.realm, ElementType.UINT8), blob.size);
            System.arraycopy(blob.data, blob.offset, array.buffer.data, 0, blob.size);
            return array;
        });

        WebInterface fileInterface = WebInterface.define(realm, "File", 2, "Blob", (arguments, prototype) -> {
            Idl.required(arguments, 2, "File constructor");
            List<Object> parts = convertParts(realm, arguments[0], "File");
            String name = Idl.usvString(arguments[1]);
            JSObject options = Idl.dictionary(arg(arguments, 2), "FilePropertyBag");
            BlobOptions blobOptions = blobOptions(options, "File");
            Object lastModifiedValue = Idl.member(options, "lastModified");
            double lastModified = lastModifiedValue == Undefined.INSTANCE
                    ? Math.floor(realm.agent.currentTimeMillis())
                    : Idl.integer(lastModifiedValue, 64, true, "File lastModified");
            byte[] data = processParts(realm, parts, blobOptions.nativeEndings(), "File");
            return new JSFile(prototype, data, 0, data.length, blobOptions.type(), name, lastModified);
        });
        fileInterface.attribute("name", (function, receiver, arguments, newTarget) ->
                Idl.self(JSFile.class, receiver, "File", "name").name);
        fileInterface.attribute("lastModified", (function, receiver, arguments, newTarget) ->
                Idl.self(JSFile.class, receiver, "File", "lastModified").lastModified);
    }
}
