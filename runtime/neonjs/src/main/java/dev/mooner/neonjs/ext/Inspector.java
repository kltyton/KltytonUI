/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.ext;

import dev.mooner.neonjs.builtins.ErrorBuiltins;
import dev.mooner.neonjs.builtins.JSMapObject;
import dev.mooner.neonjs.interop.HostClassObject;
import dev.mooner.neonjs.interop.HostObject;
import dev.mooner.neonjs.runtime.Accessor;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSErrorObject;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSFunction;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSPrimitiveWrapper;
import dev.mooner.neonjs.runtime.JSStringObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.NumberConv;
import dev.mooner.neonjs.runtime.PropertyMap;
import dev.mooner.neonjs.runtime.ProxyObject;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.vm.JSClosure;
import dev.mooner.neonjs.vm.JSPromise;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Node-like value formatting for console output and the REPL; never invokes user code. */
public final class Inspector {
    private Inspector() {
    }

    public static String inspect(Object value) {
        return inspect(value, 2);
    }

    public static String inspect(Object value, int depth) {
        StringBuilder builder = new StringBuilder();
        format(value, builder, depth, new HashSet<>());
        return builder.toString();
    }

    /** Formatting for console.log arguments: strings are printed raw at top level. */
    public static String display(Object value) {
        return value instanceof CharSequence text ? text.toString() : inspect(value);
    }

    private static String quote(String text) {
        StringBuilder builder = new StringBuilder("'");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\'' -> builder.append("\\'");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\t' -> builder.append("\\t");
                case '\r' -> builder.append("\\r");
                default -> {
                    if (c < ' ') builder.append(String.format("\\x%02x", (int) c));
                    else builder.append(c);
                }
            }
        }
        return builder.append('\'').toString();
    }

    private static String keyText(Object key) {
        if (key instanceof JSSymbol symbol) return "[" + symbol + "]";
        if (key instanceof Integer) return key.toString();
        String text = key.toString();
        if (!text.isEmpty() && (Character.isLetter(text.charAt(0)) || text.charAt(0) == '_' || text.charAt(0) == '$')) {
            boolean valid = true;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '_' || c == '$')) {
                    valid = false;
                    break;
                }
            }
            if (valid) return text;
        }
        return quote(text);
    }

    private static Object ownData(JSObject object, Object key) {
        PropertyMap map = object.props;
        if (map == null) return NotFound.INSTANCE;
        int index = map.find(key);
        return index < 0 ? NotFound.INSTANCE : map.values[index];
    }

    private static String accessorText(Accessor accessor) {
        boolean getter = accessor.getter != Undefined.INSTANCE && accessor.getter != null;
        boolean setter = accessor.setter != Undefined.INSTANCE && accessor.setter != null;
        if (getter && setter) return "[Getter/Setter]";
        return getter ? "[Getter]" : "[Setter]";
    }

    private static void format(Object value, StringBuilder builder, int depth, Set<Object> seen) {
        if (value == Undefined.INSTANCE || value == null) {
            builder.append("undefined");
        } else if (value == Null.INSTANCE) {
            builder.append("null");
        } else if (value instanceof Accessor accessor) {
            builder.append(accessorText(accessor));
        } else if (value instanceof Boolean) {
            builder.append(value);
        } else if (value instanceof Double number) {
            builder.append(number == 0.0 && 1.0 / number < 0 ? "-0" : NumberConv.toString(number));
        } else if (value instanceof CharSequence text) {
            builder.append(quote(text.toString()));
        } else if (value instanceof BigInteger integer) {
            builder.append(integer).append('n');
        } else if (value instanceof JSSymbol symbol) {
            builder.append(symbol);
        } else if (value instanceof HostObject host) {
            builder.append("[Host ").append(host.target.getClass().getSimpleName())
                    .append(": ").append(safeToString(host.target)).append(']');
        } else if (value instanceof HostClassObject host) {
            builder.append("[HostClass ").append(host.cls.getName()).append(']');
        } else if (value instanceof ProxyObject) {
            builder.append("Proxy {}");
        } else if (value instanceof JSObject object) {
            formatObject(object, builder, depth, seen);
        } else {
            builder.append(value);
        }
    }

    private static String safeToString(Object object) {
        try {
            String text = object.toString();
            return text.length() <= 200 ? text : text.substring(0, 200);
        } catch (Exception error) {
            return "?";
        }
    }

    private static void formatObject(JSObject object, StringBuilder builder, int depth, Set<Object> seen) {
        if (!seen.add(object)) {
            builder.append("[Circular]");
            return;
        }
        try {
            if (object.isCallable()) {
                String name = object instanceof JSFunction function ? function.debugName() : "";
                boolean isClass = object instanceof JSClosure closure && closure.code.isClassConstructor();
                builder.append(isClass ? "[class " : "[Function");
                if (isClass) builder.append(name.isEmpty() ? "(anonymous)" : name);
                else if (!name.isEmpty()) builder.append(": ").append(name);
                else builder.append(" (anonymous)");
                builder.append(']');
                return;
            }
            if (object instanceof JSErrorObject error) {
                builder.append(ErrorBuiltins.stackString(error));
                return;
            }
            if (object instanceof JSPromise promise) {
                builder.append("Promise { ");
                if (promise.state == JSPromise.PENDING) {
                    builder.append("<pending>");
                } else if (promise.state == JSPromise.FULFILLED) {
                    format(promise.result, builder, depth - 1, seen);
                } else {
                    builder.append("<rejected> ");
                    format(promise.result, builder, depth - 1, seen);
                }
                builder.append(" }");
                return;
            }
            if (object instanceof JSPrimitiveWrapper wrapper) {
                builder.append('[').append(wrapper.primitive instanceof Double ? "Number"
                        : wrapper.primitive instanceof Boolean ? "Boolean" : "Object").append(": ");
                format(wrapper.primitive, builder, depth, seen);
                builder.append(']');
                return;
            }
            if (object instanceof JSStringObject string) {
                builder.append("[String: ").append(quote(string.value)).append(']');
                return;
            }
            if (object instanceof JSMapObject map) {
                builder.append(map.isSet ? "Set(" : "Map(").append(map.table.size()).append(") {");
                if (depth < 0) {
                    builder.append(" ... }");
                    return;
                }
                boolean[] first = {true};
                int[] count = {0};
                map.table.forEachLive((key, value) -> {
                    if (count[0]++ >= 100) return;
                    builder.append(first[0] ? " " : ", ");
                    first[0] = false;
                    format(key, builder, depth - 1, seen);
                    if (!map.isSet) {
                        builder.append(" => ");
                        format(value, builder, depth - 1, seen);
                    }
                });
                builder.append(first[0] ? "}" : " }");
                return;
            }
            if (object instanceof JSArray array) {
                if (depth < 0) {
                    builder.append("[Array]");
                    return;
                }
                builder.append('[');
                long n = array.length;
                int holes = 0;
                int shown = 0;
                for (int i = 0; i < Math.min(n, 100); i++) {
                    Object value = ownData(array, i);
                    if (value == NotFound.INSTANCE) value = array.getOwnValue(i, array);
                    if (value == NotFound.INSTANCE) {
                        holes++;
                        continue;
                    }
                    if (shown++ > 0 || holes > 0) builder.append(", ");
                    else builder.append(' ');
                    if (holes > 0) {
                        builder.append("<").append(holes).append(" empty item")
                                .append(holes > 1 ? "s" : "").append(">, ");
                        holes = 0;
                    }
                    format(value, builder, depth - 1, seen);
                }
                if (holes > 0) {
                    builder.append(shown > 0 ? ", " : " ").append("<").append(holes)
                            .append(" empty item").append(holes > 1 ? "s" : "").append(">");
                }
                if (n > 100) builder.append(", ... ").append(n - 100).append(" more items");
                builder.append(n == 0 ? "]" : " ]");
                return;
            }
            if (depth < 0) {
                builder.append("[Object]");
                return;
            }
            Object tagValue = ownData(object, JSSymbol.toStringTag);
            String tag = tagValue instanceof CharSequence text ? text.toString() : null;
            if (object.proto == null) builder.append("[Object: null prototype] ");
            else if (tag != null) builder.append("Object [").append(tag).append("] ");
            builder.append('{');
            boolean first = true;
            int count = 0;
            List<Object> keys;
            try {
                keys = object.ownPropertyKeys();
            } catch (JSException error) {
                keys = List.of();
            }
            for (Object key : keys) {
                var descriptor = object.getOwnProperty(key);
                if (descriptor == null || !descriptor.enumerable) continue;
                if (count++ >= 100) {
                    builder.append(", ...");
                    break;
                }
                builder.append(first ? " " : ", ");
                first = false;
                builder.append(keyText(key)).append(": ");
                if (descriptor.isAccessor()) {
                    boolean getter = descriptor.getter != Undefined.INSTANCE;
                    boolean setter = descriptor.setter != Undefined.INSTANCE;
                    builder.append(getter && setter ? "[Getter/Setter]" : getter ? "[Getter]" : "[Setter]");
                } else {
                    format(descriptor.value, builder, depth - 1, seen);
                }
            }
            builder.append(first ? "}" : " }");
        } finally {
            seen.remove(object);
        }
    }

}
