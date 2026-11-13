/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.ext;

import dev.mooner.neonjs.builtins.Builtins;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static dev.mooner.neonjs.ext.Idl.typeErr;

/** {@code FormData} and its multipart/form-data encoding and parsing. */
final class FormData {
    private FormData() {
    }

    static final class JSFormData extends JSObject {
        final ArrayList<Map.Entry<String, Object>> entries = new ArrayList<>();

        JSFormData(JSObject prototype) {
            super(prototype);
        }

        @Override
        public String getClassName() {
            return "FormData";
        }
    }

    record Multipart(byte[] body, String contentType) {
    }

    /** "create an entry": a string or a blob; a blob becomes a File named {@code filename}. */
    static Object entryValue(Realm realm, Object value, String filename) {
        if (!(value instanceof Blob.JSBlob blob)) return value;
        if (blob instanceof Blob.JSFile file && filename == null) return file;
        String name = filename != null ? filename : blob instanceof Blob.JSFile file ? file.name : "blob";
        double lastModified = blob instanceof Blob.JSFile file
                ? file.lastModified : Math.floor(realm.agent.currentTimeMillis());
        return new Blob.JSFile(realm.intrinsic("%File.prototype%"), blob.data, blob.offset,
                blob.size, blob.type, name, lastModified);
    }

    private static Map.Entry<String, Object> entryArgs(Realm realm, Object[] arguments, String what) {
        Idl.required(arguments, 2, what);
        String name = Idl.usvString(arguments[0]);
        if (arguments.length >= 3 || arguments[1] instanceof Blob.JSBlob) {
            if (!(arguments[1] instanceof Blob.JSBlob blob)) {
                throw typeErr(what + ": the value is not a Blob");
            }
            String filename = arguments.length >= 3 && arguments[2] != Undefined.INSTANCE
                    ? Idl.usvString(arguments[2]) : null;
            return new AbstractMap.SimpleImmutableEntry<>(name, entryValue(realm, blob, filename));
        }
        return new AbstractMap.SimpleImmutableEntry<>(name, Idl.usvString(arguments[1]));
    }

    static JSFormData newFormData(Realm realm) {
        return new JSFormData(realm.intrinsic("%FormData.prototype%"));
    }

    private static String crlf(String text) {
        if (text.indexOf('\r') < 0 && text.indexOf('\n') < 0) return text;
        StringBuilder builder = new StringBuilder(text.length() + 8);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\r') {
                builder.append("\r\n");
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            } else if (c == '\n') {
                builder.append("\r\n");
            } else {
                builder.append(c);
            }
            i++;
        }
        return builder.toString();
    }

    private static String escapeName(String text) {
        return text.replace("\n", "%0A").replace("\r", "%0D").replace("\"", "%22");
    }

    /** The multipart/form-data encoding of {@code formData}'s entries. */
    static Multipart multipart(Realm realm, JSFormData formData) {
        byte[] random = new byte[16];
        Crypto.randomBytes(realm, random);
        StringBuilder boundaryBuilder = new StringBuilder("----formdata-neonjs-");
        for (byte b : random) boundaryBuilder.append(String.format("%02x", b & 0xFF));
        String boundary = boundaryBuilder.toString();
        ArrayList<byte[]> parts = new ArrayList<>(formData.entries.size() * 3 + 1);
        long[] total = {0};
        for (Map.Entry<String, Object> entry : formData.entries) {
            String name = entry.getKey();
            Object value = entry.getValue();
            StringBuilder head = new StringBuilder("--").append(boundary)
                    .append("\r\nContent-Disposition: form-data; name=\"")
                    .append(escapeName(crlf(name))).append('"');
            if (value instanceof Blob.JSFile file) {
                head.append("; filename=\"").append(escapeName(file.name))
                        .append("\"\r\nContent-Type: ")
                        .append(file.type.isEmpty() ? "application/octet-stream" : file.type)
                        .append("\r\n\r\n");
                add(parts, total, WebGlobals.utf8(head.toString()));
                add(parts, total, file.bytes());
                add(parts, total, CRLF);
            } else {
                head.append("\r\n\r\n").append(crlf((String) value)).append("\r\n");
                add(parts, total, WebGlobals.utf8(head.toString()));
            }
        }
        add(parts, total, WebGlobals.utf8("--" + boundary + "--\r\n"));
        realm.agent.reserveAllocation(total[0]);
        byte[] out = new byte[(int) total[0]];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return new Multipart(out, "multipart/form-data; boundary=" + boundary);
    }

    private static void add(List<byte[]> parts, long[] total, byte[] bytes) {
        total[0] += bytes.length;
        if (total[0] > Integer.MAX_VALUE - 8) {
            throw JSException.rangeError("FormData: the body is too large");
        }
        parts.add(bytes);
    }

    private static final byte[] CRLF = {13, 10};

    private static int indexOf(byte[] data, byte[] needle, int from) {
        if (needle.length == 0) return from;
        byte first = needle[0];
        int i = from;
        int last = data.length - needle.length;
        outer:
        while (i <= last) {
            if (data[i] != first) {
                i++;
                continue;
            }
            for (int k = 1; k < needle.length; k++) {
                if (data[i + k] != needle[k]) {
                    i++;
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** A parameter of a Content-Disposition (or Content-Type) header value, unquoted, or null. */
    static String headerParam(String value, String parameter) {
        int i = value.indexOf(';');
        while (i >= 0 && i < value.length()) {
            int j = i + 1;
            while (j < value.length() && (value.charAt(j) == ' ' || value.charAt(j) == '\t')) j++;
            int eq = value.indexOf('=', j);
            if (eq < 0) return null;
            String key = value.substring(j, eq).strip();
            int k = eq + 1;
            String result;
            if (k < value.length() && value.charAt(k) == '"') {
                StringBuilder builder = new StringBuilder();
                k++;
                while (k < value.length() && value.charAt(k) != '"') {
                    if (value.charAt(k) == '\\' && k + 1 < value.length()) k++;
                    builder.append(value.charAt(k));
                    k++;
                }
                result = builder.toString();
                k++;
            } else {
                int end = value.indexOf(';', k);
                if (end < 0) end = value.length();
                result = value.substring(k, end).strip();
                k = end;
            }
            if (key.equalsIgnoreCase(parameter)) return result;
            i = value.indexOf(';', k);
        }
        return null;
    }

    /** Parses a multipart/form-data body with {@code boundary} into a new FormData. */
    static JSFormData parseMultipart(Realm realm, byte[] data, String boundary) {
        JSFormData formData = newFormData(realm);
        byte[] delimiter = WebGlobals.utf8("--" + boundary);
        int at = indexOf(data, delimiter, 0);
        if (at < 0) throw bad();
        while (true) {
            at += delimiter.length;
            if (at + 1 < data.length && data[at] == (byte) '-' && data[at + 1] == (byte) '-') return formData;
            if (at + 1 >= data.length || data[at] != 13 || data[at + 1] != 10) throw bad();
            at += 2;
            int headersEnd = indexOf(data, new byte[] {13, 10, 13, 10}, at);
            if (headersEnd < 0) throw bad();
            String name = null;
            String filename = null;
            String type = "";
            String headerBlock = new String(data, at, headersEnd - at, StandardCharsets.UTF_8);
            for (String line : headerBlock.split("\r\n", -1)) {
                int colon = line.indexOf(':');
                if (colon <= 0) throw bad();
                String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).strip();
                if (key.equals("content-disposition")) {
                    if (!value.toLowerCase(Locale.ROOT).startsWith("form-data")) throw bad();
                    name = headerParam(value, "name");
                    filename = headerParam(value, "filename");
                } else if (key.equals("content-type")) {
                    type = value;
                }
            }
            if (name == null) throw bad();
            int bodyStart = headersEnd + 4;
            int next = indexOf(data, WebGlobals.utf8("\r\n--" + boundary), bodyStart);
            if (next < 0) throw bad();
            byte[] bytes = java.util.Arrays.copyOfRange(data, bodyStart, next);
            Object value = filename != null
                    ? Blob.newFile(realm, bytes, type, filename, Math.floor(realm.agent.currentTimeMillis()))
                    : WebGlobals.utf8Decode(realm, bytes);
            formData.entries.add(new AbstractMap.SimpleImmutableEntry<>(name, value));
            at = next + 2;
            if ((formData.entries.size() & 255) == 0) realm.agent.checkInterrupt();
        }
    }

    private static JSException bad() {
        return typeErr("FormData: the body is not valid multipart/form-data");
    }

    static void install(Realm realm) {
        WebInterface iface = WebInterface.define(realm, "FormData", 0, null, null, (arguments, prototype) -> {
            if (arguments.length > 0 && arguments[0] != Undefined.INSTANCE) {
                throw typeErr("FormData constructor: the argument is not an HTMLFormElement");
            }
            return new JSFormData(prototype);
        });
        iface.operation("append", 2, (function, receiver, arguments, newTarget) -> {
            JSFormData formData = Idl.self(JSFormData.class, receiver, "FormData", "append");
            formData.entries.add(entryArgs(function.realm, arguments, "FormData.append"));
            return Undefined.INSTANCE;
        });
        iface.operation("delete", 1, (function, receiver, arguments, newTarget) -> {
            JSFormData formData = Idl.self(JSFormData.class, receiver, "FormData", "delete");
            Idl.required(arguments, 1, "FormData.delete");
            String name = Idl.usvString(arguments[0]);
            formData.entries.removeIf(entry -> entry.getKey().equals(name));
            return Undefined.INSTANCE;
        });
        iface.operation("get", 1, (function, receiver, arguments, newTarget) -> {
            JSFormData formData = Idl.self(JSFormData.class, receiver, "FormData", "get");
            Idl.required(arguments, 1, "FormData.get");
            String name = Idl.usvString(arguments[0]);
            for (Map.Entry<String, Object> entry : formData.entries) {
                if (entry.getKey().equals(name)) return entry.getValue();
            }
            return Null.INSTANCE;
        });
        iface.operation("getAll", 1, (function, receiver, arguments, newTarget) -> {
            JSFormData formData = Idl.self(JSFormData.class, receiver, "FormData", "getAll");
            Idl.required(arguments, 1, "FormData.getAll");
            String name = Idl.usvString(arguments[0]);
            ArrayList<Object> values = new ArrayList<>();
            for (Map.Entry<String, Object> entry : formData.entries) {
                if (entry.getKey().equals(name)) values.add(entry.getValue());
            }
            return Builtins.arrayOf(function.realm, values);
        });
        iface.operation("has", 1, (function, receiver, arguments, newTarget) -> {
            JSFormData formData = Idl.self(JSFormData.class, receiver, "FormData", "has");
            Idl.required(arguments, 1, "FormData.has");
            String name = Idl.usvString(arguments[0]);
            for (Map.Entry<String, Object> entry : formData.entries) {
                if (entry.getKey().equals(name)) return true;
            }
            return false;
        });
        iface.operation("set", 2, (function, receiver, arguments, newTarget) -> {
            JSFormData formData = Idl.self(JSFormData.class, receiver, "FormData", "set");
            Map.Entry<String, Object> entry = entryArgs(function.realm, arguments, "FormData.set");
            int first = -1;
            for (int i = 0; i < formData.entries.size(); i++) {
                if (formData.entries.get(i).getKey().equals(entry.getKey())) {
                    first = i;
                    break;
                }
            }
            if (first < 0) {
                formData.entries.add(entry);
            } else {
                formData.entries.set(first, entry);
                final int keep = first;
                final int[] index = {-1};
                formData.entries.removeIf(item -> {
                    index[0]++;
                    return index[0] > keep && item.getKey().equals(entry.getKey());
                });
            }
            return Undefined.INSTANCE;
        });
        iface.pairIterable(JSFormData.class, formData -> formData.entries);
    }
}
