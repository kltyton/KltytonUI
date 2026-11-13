/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSPrimitiveWrapper;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.JSStringObject;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.NumberConv;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/** JSON built-ins; derived from NeonJS and adapted to Java. */
public final class JSONBuiltins {
    private JSONBuiltins() {
    }

    public static void install(Realm realm) {
        JSObject json = new JSObject(realm.getObjectPrototype());
        BuiltinsKt.global(realm, "JSON", json);
        BuiltinsKt.value(json, JSSymbol.toStringTag, "JSON", Attr.CONFIGURABLE);
        BuiltinsKt.method(json, realm, "parse", 2, (function, receiver, args, newTarget) -> {
            String text = Ops.toString(Values.arg(args, 0));
            JsonParser parser = new JsonParser(function.realm, text);
            Node root = parser.parseRoot();
            Object reviver = Values.arg(args, 1);
            if (Ops.isCallable(reviver)) {
                JSObject holder = new JSObject(function.realm.getObjectPrototype());
                holder.createDataPropertyOrThrow("", root.value);
                return internalize(function.realm, holder, "", (JSObject) reviver, root);
            }
            return root.value;
        });
        BuiltinsKt.method(json, realm, "stringify", 3, (function, receiver, args, newTarget) ->
                stringify(function.realm, Values.arg(args, 0), Values.arg(args, 1), Values.arg(args, 2)));
        BuiltinsKt.method(json, realm, "rawJSON", 1, (function, receiver, args, newTarget) -> {
            String s = Ops.toString(Values.arg(args, 0));
            if (s.isEmpty() || isJsonWs(s.charAt(0)) || isJsonWs(s.charAt(s.length() - 1))) {
                throw JSException.syntaxError("Invalid value for JSON.rawJSON");
            }
            Node node = new JsonParser(function.realm, s).parseRoot();
            if (node.value instanceof JSObject) {
                throw JSException.syntaxError("Invalid value for JSON.rawJSON");
            }
            RawJSONObject o = new RawJSONObject(s);
            o.createDataPropertyOrThrow("rawJSON", s);
            o.preventExtensions();
            Builtins.freeze(o);
            return o;
        });
        BuiltinsKt.method(json, realm, "isRawJSON", 1,
                (function, receiver, args, newTarget) -> Values.arg(args, 0) instanceof RawJSONObject);
    }

    private static boolean isJsonWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /** Parse tree node retaining source text of primitives (json-parse-with-source). */
    public static final class Node {
        public final Object value;
        public final String source;
        public final List<Node> elements;
        public final LinkedHashMap<String, Node> members;

        public Node(Object value, String source, List<Node> elements, LinkedHashMap<String, Node> members) {
            this.value = value;
            this.source = source;
            this.elements = elements;
            this.members = members;
        }
    }

    public static final class JsonParser {
        public final Realm realm;
        public final String s;
        public int i;

        public JsonParser(Realm realm, String s) {
            this.realm = realm;
            this.s = s;
        }

        private void err() {
            throw JSException.syntaxError("Unexpected token in JSON at position " + i);
        }

        private void err(String message) {
            throw JSException.syntaxError(message);
        }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        public Node parseRoot() {
            ws();
            Node v = value();
            ws();
            if (i != s.length()) err();
            return v;
        }

        private Node value() {
            if (i >= s.length()) err("Unexpected end of JSON input");
            return switch (s.charAt(i)) {
                case '{' -> obj();
                case '[' -> arr();
                case '"' -> {
                    int st = i;
                    String str = string();
                    yield new Node(str, s.substring(st, i), null, null);
                }
                case 't' -> lit("true", Boolean.TRUE);
                case 'f' -> lit("false", Boolean.FALSE);
                case 'n' -> lit("null", Null.INSTANCE);
                default -> num();
            };
        }

        private Node lit(String word, Object v) {
            if (!s.startsWith(word, i)) err();
            i += word.length();
            return new Node(v, word, null, null);
        }

        private Node num() {
            int st = i;
            if (i < s.length() && s.charAt(i) == '-') i++;
            if (i >= s.length()) err();
            if (s.charAt(i) == '0') i++;
            else if (s.charAt(i) >= '1' && s.charAt(i) <= '9') {
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            } else err();
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                if (i >= s.length() || s.charAt(i) < '0' || s.charAt(i) > '9') err();
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                if (i >= s.length() || s.charAt(i) < '0' || s.charAt(i) > '9') err();
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            }
            String text = s.substring(st, i);
            return new Node(Double.parseDouble(text), text, null, null);
        }

        private String string() {
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) err("Unterminated string in JSON at position " + i);
                char c = s.charAt(i);
                if (c == '"') {
                    i++;
                    break;
                }
                if (c < ' ') err("Bad control character in string literal in JSON at position " + i);
                if (c == '\\') {
                    i++;
                    if (i >= s.length()) err();
                    switch (s.charAt(i)) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 >= s.length()) err();
                            int v = 0;
                            for (int j = 1; j <= 4; j++) {
                                int h = NumberConv.digitVal(s.charAt(i + j), 16);
                                if (h < 0) err();
                                v = v * 16 + h;
                            }
                            sb.append((char) v);
                            i += 4;
                        }
                        default -> err("Bad escaped character in JSON at position " + i);
                    }
                    i++;
                    continue;
                }
                sb.append(c);
                i++;
            }
            return sb.toString();
        }

        private Node arr() {
            i++;
            JSArray a = new JSArray(realm.getArrayPrototype());
            ArrayList<Node> nodes = new ArrayList<>();
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return new Node(a, null, nodes, null);
            }
            while (true) {
                ws();
                Node v = value();
                nodes.add(v);
                a.pushInit(v.value);
                ws();
                if (i >= s.length()) err("Unexpected end of JSON input");
                if (s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (s.charAt(i) == ']') {
                    i++;
                    break;
                }
                err();
            }
            return new Node(a, null, nodes, null);
        }

        private Node obj() {
            i++;
            JSObject o = new JSObject(realm.getObjectPrototype());
            LinkedHashMap<String, Node> members = new LinkedHashMap<>();
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return new Node(o, null, null, members);
            }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') err();
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') err();
                i++;
                ws();
                Node v = value();
                o.createDataProperty(PK.fromString(k), v.value);
                members.put(k, v);
                ws();
                if (i >= s.length()) err("Unexpected end of JSON input");
                if (s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (s.charAt(i) == '}') {
                    i++;
                    break;
                }
                err();
            }
            return new Node(o, null, null, members);
        }
    }

    /** InternalizeJSONProperty with source text context. */
    private static Object internalize(Realm realm, JSObject holder, Object name, JSObject reviver, Node node) {
        Object v = holder.get(PK.fromString(PK.toStringKey(name)), holder);
        Node n = node != null && Ops.sameValue(node.value, v) ? node : null;
        if (v instanceof JSObject object) {
            if (Ops.isArray(object)) {
                long len = Ops.lengthOfArrayLike(object);
                long k = 0L;
                while (k < len) {
                    if ((k & 1023L) == 1023L) realm.agent.checkInterrupt();
                    Node child = null;
                    if (n != null && n.elements != null) {
                        int index = (int) k;
                        if (index >= 0 && index < n.elements.size()) child = n.elements.get(index);
                    }
                    Object newEl = internalize(realm, object, Long.toString(k), reviver, child);
                    Object key = PK.fromIndex(k);
                    if (newEl == Undefined.INSTANCE) object.delete(key);
                    else object.createDataProperty(key, newEl);
                    k++;
                }
            } else {
                List<Object> keys = new ArrayList<>();
                for (Object key : object.ownPropertyKeys()) {
                    if (key instanceof JSSymbol) continue;
                    PropertyDescriptor descriptor = object.getOwnProperty(key);
                    if (descriptor != null && descriptor.enumerable) keys.add(key);
                }
                for (Object key : keys) {
                    String ks = PK.toStringKey(key);
                    Node child = n != null && n.members != null ? n.members.get(ks) : null;
                    Object newEl = internalize(realm, object, ks, reviver, child);
                    if (newEl == Undefined.INSTANCE) object.delete(key);
                    else object.createDataProperty(key, newEl);
                }
            }
        }
        JSObject ctx = new JSObject(realm.getObjectPrototype());
        if (n != null && n.source != null && !(v instanceof JSObject)) {
            ctx.createDataProperty("source", n.source);
        }
        Object keyValue = name instanceof Integer ? name.toString() : name;
        return reviver.call(holder, new Object[]{keyValue, v, ctx});
    }

    // ------------------------------------------------------------------ stringify

    private static final class State {
        final Realm realm;
        final JSObject replacer;
        final List<String> propertyList;
        final String gap;
        final ArrayList<JSObject> stack = new ArrayList<>();
        String indent = "";

        State(Realm realm, JSObject replacer, List<String> propertyList, String gap) {
            this.realm = realm;
            this.replacer = replacer;
            this.propertyList = propertyList;
            this.gap = gap;
        }
    }

    public static Object stringify(Realm realm, Object value, Object replacerArg, Object spaceArg) {
        JSObject replacerFn = null;
        ArrayList<String> propList = null;
        if (replacerArg instanceof JSObject object) {
            if (Ops.isCallable(object)) replacerFn = object;
            else if (Ops.isArray(object)) {
                propList = new ArrayList<>();
                HashSet<String> seen = new HashSet<>();
                long len = Ops.lengthOfArrayLike(object);
                long k = 0L;
                while (k < len) {
                    if ((k & 1023L) == 1023L) realm.agent.checkInterrupt();
                    Object v = object.get(PK.fromIndex(k), object);
                    String item = null;
                    if (v instanceof CharSequence text) item = text.toString();
                    else if (v instanceof Double number) item = NumberConv.toString(number);
                    else if (v instanceof JSPrimitiveWrapper wrapper) {
                        if (wrapper.primitive instanceof Double) item = Ops.toString(wrapper);
                    } else if (v instanceof JSStringObject) item = Ops.toString(v);
                    if (item != null && seen.add(item)) propList.add(item);
                    k++;
                }
            }
        }
        Object space = spaceArg;
        if (space instanceof JSPrimitiveWrapper wrapper && wrapper.primitive instanceof Double) {
            space = Ops.toNumber(space);
        } else if (space instanceof JSStringObject) {
            space = Ops.toString(space);
        }
        String gap;
        if (space instanceof Double number) {
            double n = Math.min(10.0, Ops.integerPart(number));
            gap = n >= 1 ? " ".repeat((int) n) : "";
        } else if (space instanceof CharSequence text) {
            String s = text.toString();
            gap = s.length() > 10 ? s.substring(0, 10) : s;
        } else {
            gap = "";
        }
        State st = new State(realm, replacerFn, propList, gap);
        JSObject wrapper = new JSObject(realm.getObjectPrototype());
        wrapper.createDataPropertyOrThrow("", value);
        StringBuilder sb = new StringBuilder();
        boolean ok = serializeProperty(st, "", wrapper, sb);
        return ok ? sb.toString() : Undefined.INSTANCE;
    }

    private static boolean serializeProperty(State st, Object key, JSObject holder, StringBuilder sb) {
        Object value = holder.get(key, holder);
        if (value instanceof JSObject || value instanceof BigInteger) {
            Object toJSON = Ops.getV(st.realm, value, "toJSON");
            if (Ops.isCallable(toJSON)) value = ((JSObject) toJSON).call(value, new Object[]{PK.toValue(key)});
        }
        if (st.replacer != null) value = st.replacer.call(holder, new Object[]{PK.toValue(key), value});
        if (value instanceof JSObject) {
            if (value instanceof JSPrimitiveWrapper wrapper) {
                Object primitive = wrapper.primitive;
                if (primitive instanceof Double) value = Ops.toNumber(value);
                else if (primitive instanceof Boolean) value = primitive;
                else if (primitive instanceof BigInteger) value = primitive;
            } else if (value instanceof JSStringObject) {
                value = Ops.toString(value);
            }
        }
        if (value instanceof RawJSONObject raw) {
            sb.append(raw.raw);
            return true;
        }
        if (value == Null.INSTANCE) {
            sb.append("null");
            return true;
        }
        if (Boolean.TRUE.equals(value)) {
            sb.append("true");
            return true;
        }
        if (Boolean.FALSE.equals(value)) {
            sb.append("false");
            return true;
        }
        if (value instanceof CharSequence text) {
            quote(text, sb);
            return true;
        }
        if (value instanceof Double number) {
            if (number.isNaN() || number.isInfinite()) sb.append("null");
            else sb.append(NumberConv.toString(number));
            return true;
        }
        if (value instanceof BigInteger) {
            throw JSException.typeError("Do not know how to serialize a BigInt");
        }
        if (value instanceof JSObject object) {
            if (Ops.isCallable(object)) return false;
            if (Ops.isArray(object)) serializeArray(st, object, sb);
            else serializeObject(st, object, sb);
            return true;
        }
        return false;
    }

    static void quote(CharSequence v, StringBuilder sb) {
        String s = v.toString();
        sb.append('"');
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\b') sb.append("\\b");
            else if (c == '\f') sb.append("\\f");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < ' ') sb.append(String.format("\\u%04x", (int) c));
            else if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c).append(s.charAt(i + 1));
                    i++;
                } else {
                    sb.append(String.format("\\u%04x", (int) c));
                }
            } else if (Character.isLowSurrogate(c)) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
            i++;
        }
        sb.append('"');
    }

    private static void enter(State st, JSObject o) {
        for (JSObject x : st.stack) {
            if (x == o) throw JSException.typeError("Converting circular structure to JSON");
        }
        if (st.stack.size() > 5000) throw JSException.rangeError("Maximum call stack size exceeded");
        st.stack.add(o);
    }

    private static void serializeObject(State st, JSObject o, StringBuilder sb) {
        enter(st, o);
        String stepback = st.indent;
        st.indent += st.gap;
        List<Object> keys;
        if (st.propertyList != null) {
            keys = new ArrayList<>(st.propertyList.size());
            for (String name : st.propertyList) keys.add(PK.fromString(name));
        } else {
            keys = new ArrayList<>();
            for (Object key : o.ownPropertyKeys()) {
                if (key instanceof JSSymbol) continue;
                PropertyDescriptor descriptor = o.getOwnProperty(key);
                if (descriptor != null && descriptor.enumerable) keys.add(key);
            }
        }
        sb.append('{');
        boolean first = true;
        for (Object k : keys) {
            int mark = sb.length();
            if (!first) sb.append(',');
            if (!st.gap.isEmpty()) sb.append('\n').append(st.indent);
            quote(PK.toStringKey(k), sb);
            sb.append(':');
            if (!st.gap.isEmpty()) sb.append(' ');
            if (!serializeProperty(st, k, o, sb)) sb.setLength(mark);
            else first = false;
        }
        if (!first && !st.gap.isEmpty()) sb.append('\n').append(stepback);
        sb.append('}');
        st.stack.remove(st.stack.size() - 1);
        st.indent = stepback;
    }

    private static void serializeArray(State st, JSObject a, StringBuilder sb) {
        enter(st, a);
        String stepback = st.indent;
        st.indent += st.gap;
        long len = Ops.lengthOfArrayLike(a);
        sb.append('[');
        long i = 0L;
        while (i < len) {
            if (i > 0) sb.append(',');
            if (!st.gap.isEmpty()) sb.append('\n').append(st.indent);
            if (!serializeProperty(st, PK.fromIndex(i), a, sb)) sb.append("null");
            i++;
        }
        if (len > 0 && !st.gap.isEmpty()) sb.append('\n').append(stepback);
        sb.append(']');
        st.stack.remove(st.stack.size() - 1);
        st.indent = stepback;
    }
}

