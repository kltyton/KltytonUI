/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.ext;

import dev.mooner.neonjs.builtins.Builtins;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import static dev.mooner.neonjs.ext.Idl.typeErr;

/** {@code Headers} (Fetch Standard): a case-insensitive header list with guards and ByteString values. */
final class Headers {
    private Headers() {
    }

    static final class JSHeaders extends JSObject {
        final ArrayList<Map.Entry<String, String>> list = new ArrayList<>();
        boolean immutable;
        boolean requestGuard;

        JSHeaders(JSObject prototype) {
            super(prototype);
        }

        @Override
        public String getClassName() {
            return "Headers";
        }

        boolean guards(String name) {
            return guards(name, null);
        }

        boolean guards(String name, String value) {
            return requestGuard && (isForbiddenRequestHeader(name)
                    || value != null && isForbiddenOverride(name, value));
        }

        boolean contains(String name) {
            for (Map.Entry<String, String> entry : list) {
                if (entry.getKey().equalsIgnoreCase(name)) return true;
            }
            return false;
        }

        String get(String name) {
            ArrayList<String> values = new ArrayList<>();
            for (Map.Entry<String, String> entry : list) {
                if (entry.getKey().equalsIgnoreCase(name)) values.add(entry.getValue());
            }
            return values.isEmpty() ? null : String.join(", ", values);
        }

        void append(String name, String value) {
            String existing = name;
            for (Map.Entry<String, String> entry : list) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    existing = entry.getKey();
                    break;
                }
            }
            list.add(new AbstractMap.SimpleImmutableEntry<>(existing, value));
        }

        void delete(String name) {
            list.removeIf(entry -> entry.getKey().equalsIgnoreCase(name));
        }

        void set(String name, String value) {
            int first = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).getKey().equalsIgnoreCase(name)) {
                    first = i;
                    break;
                }
            }
            if (first < 0) {
                list.add(new AbstractMap.SimpleImmutableEntry<>(name, value));
                return;
            }
            String existingName = list.get(first).getKey();
            list.set(first, new AbstractMap.SimpleImmutableEntry<>(existingName, value));
            final int keep = first;
            final int[] index = {-1};
            list.removeIf(entry -> {
                index[0]++;
                return index[0] > keep && entry.getKey().equalsIgnoreCase(name);
            });
        }

        List<Map.Entry<Object, Object>> sortedAndCombined() {
            TreeMap<String, ArrayList<String>> values = new TreeMap<>();
            for (Map.Entry<String, String> entry : list) {
                values.computeIfAbsent(entry.getKey().toLowerCase(Locale.ROOT),
                        key -> new ArrayList<>(1)).add(entry.getValue());
            }
            ArrayList<Map.Entry<Object, Object>> out = new ArrayList<>(values.size());
            for (Map.Entry<String, ArrayList<String>> entry : values.entrySet()) {
                String name = entry.getKey();
                if (name.equals("set-cookie")) {
                    for (String value : entry.getValue()) {
                        out.add(new AbstractMap.SimpleImmutableEntry<>(name, value));
                    }
                } else {
                    out.add(new AbstractMap.SimpleImmutableEntry<>(name,
                            String.join(", ", entry.getValue())));
                }
            }
            return out;
        }
    }

    private static final Set<String> FORBIDDEN_REQUEST_HEADERS = Set.of(
            "accept-encoding", "connection", "content-length", "expect", "host", "keep-alive", "te", "trailer",
            "transfer-encoding", "upgrade");

    static boolean isForbiddenRequestHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return FORBIDDEN_REQUEST_HEADERS.contains(lower)
                || lower.startsWith("proxy-") || lower.startsWith("sec-websocket-");
    }

    private static final Set<String> METHOD_OVERRIDES = Set.of(
            "x-http-method", "x-http-method-override", "x-method-override");
    private static final Set<String> FORBIDDEN_METHODS = Set.of("CONNECT", "TRACE", "TRACK");

    private static boolean isForbiddenOverride(String name, String value) {
        if (!METHOD_OVERRIDES.contains(name.toLowerCase(Locale.ROOT))) return false;
        for (String part : value.split(",", -1)) {
            if (FORBIDDEN_METHODS.contains(part.strip().toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean isTokenChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    static boolean isToken(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) if (!isTokenChar(text.charAt(i))) return false;
        return true;
    }

    static String name(Object value, String what) {
        String text = Idl.byteString(value, what);
        if (text.isEmpty() || !isToken(text)) {
            throw typeErr(what + ": '" + text + "' is not a valid header name");
        }
        return text;
    }

    private static boolean isHttpWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    static String value(Object value, String what) {
        String text = Idl.byteString(value, what);
        int start = 0;
        int end = text.length();
        while (start < end && isHttpWhitespace(text.charAt(start))) start++;
        while (end > start && isHttpWhitespace(text.charAt(end - 1))) end--;
        text = text.substring(start, end);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u0000' || c == '\n' || c == '\r') {
                throw typeErr(what + ": the header value contains NUL, CR or LF");
            }
        }
        return text;
    }

    private static void checkMutable(JSHeaders headers, String what) {
        if (headers.immutable) throw typeErr(what + ": the headers are immutable");
    }

    static void fill(Realm realm, JSHeaders headers, Object init, String what) {
        if (!(init instanceof JSObject object)) throw typeErr(what + ": the init is not an object");
        if (Ops.getMethod(object, JSSymbol.iterator) != Undefined.INSTANCE) {
            List<List<String>> pairs = Idl.sequence(realm, object, what,
                    inner -> Idl.sequence(realm, inner, what, value -> Idl.byteString(value, what)));
            for (List<String> pair : pairs) {
                if (pair.size() != 2) throw typeErr(what + ": a header pair must have exactly two items");
                String name = name(pair.get(0), what);
                String value = value(pair.get(1), what);
                if (!headers.guards(name, value)) headers.append(name, value);
            }
            return;
        }
        List<Map.Entry<String, String>> entries = Idl.record(object,
                key -> Idl.byteString(key, what), value -> Idl.byteString(value, what));
        for (Map.Entry<String, String> entry : entries) {
            String name = name(entry.getKey(), what);
            String value = value(entry.getValue(), what);
            if (!headers.guards(name, value)) headers.append(name, value);
        }
    }

    static void install(Realm realm) {
        WebInterface iface = WebInterface.define(realm, "Headers", 0, null, null, (arguments, prototype) -> {
            JSHeaders headers = new JSHeaders(prototype);
            if (arguments.length > 0 && arguments[0] != Undefined.INSTANCE) {
                fill(realm, headers, arguments[0], "Headers constructor");
            }
            return headers;
        });
        iface.operation("append", 2, (function, receiver, arguments, newTarget) -> {
            JSHeaders headers = Idl.self(JSHeaders.class, receiver, "Headers", "append");
            Idl.required(arguments, 2, "Headers.append");
            String name = name(arguments[0], "Headers.append");
            String value = value(arguments[1], "Headers.append");
            checkMutable(headers, "Headers.append");
            if (!headers.guards(name, value)) headers.append(name, value);
            return Undefined.INSTANCE;
        });
        iface.operation("delete", 1, (function, receiver, arguments, newTarget) -> {
            JSHeaders headers = Idl.self(JSHeaders.class, receiver, "Headers", "delete");
            Idl.required(arguments, 1, "Headers.delete");
            String name = name(arguments[0], "Headers.delete");
            checkMutable(headers, "Headers.delete");
            if (!headers.guards(name)) headers.delete(name);
            return Undefined.INSTANCE;
        });
        iface.operation("get", 1, (function, receiver, arguments, newTarget) -> {
            JSHeaders headers = Idl.self(JSHeaders.class, receiver, "Headers", "get");
            Idl.required(arguments, 1, "Headers.get");
            String value = headers.get(name(arguments[0], "Headers.get"));
            return value == null ? dev.mooner.neonjs.runtime.Null.INSTANCE : value;
        });
        iface.operation("getSetCookie", 0, (function, receiver, arguments, newTarget) -> {
            JSHeaders headers = Idl.self(JSHeaders.class, receiver, "Headers", "getSetCookie");
            ArrayList<Object> values = new ArrayList<>();
            for (Map.Entry<String, String> entry : headers.list) {
                if (entry.getKey().equalsIgnoreCase("set-cookie")) values.add(entry.getValue());
            }
            return Builtins.arrayOf(function.realm, values);
        });
        iface.operation("has", 1, (function, receiver, arguments, newTarget) -> {
            JSHeaders headers = Idl.self(JSHeaders.class, receiver, "Headers", "has");
            Idl.required(arguments, 1, "Headers.has");
            return headers.contains(name(arguments[0], "Headers.has"));
        });
        iface.operation("set", 2, (function, receiver, arguments, newTarget) -> {
            JSHeaders headers = Idl.self(JSHeaders.class, receiver, "Headers", "set");
            Idl.required(arguments, 2, "Headers.set");
            String name = name(arguments[0], "Headers.set");
            String value = value(arguments[1], "Headers.set");
            checkMutable(headers, "Headers.set");
            if (!headers.guards(name, value)) headers.set(name, value);
            return Undefined.INSTANCE;
        });
        iface.pairIterable(JSHeaders.class, JSHeaders::sortedAndCombined);
    }
}
