/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.ext;

import dev.mooner.neonjs.NeonConsole;
import dev.mooner.neonjs.NeonConsole.Level;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.NumberConv;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.ProxyObject;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import static dev.mooner.neonjs.runtime.Values.arg;

/** The {@code console} namespace object. */
public final class ConsoleBuiltins {
    public static final ConsoleBuiltins INSTANCE = new ConsoleBuiltins();

    /** Upper bound for a single message. */
    public static final int MAX_MESSAGE = 1 << 20;
    private static final String FORMATTER = "%ConsoleFormatter%";
    private static final String SINK = "%ConsoleSink%";

    private ConsoleBuiltins() {
    }

    /** How a realm's console turns its arguments into text. */
    public interface Formatter {
        String format(Object[] arguments);

        String inspect(Object value, Object options);
    }

    public static void setFormatter(Realm realm, Formatter formatter) {
        if (formatter == null) realm.intrinsicsAny.remove(FORMATTER);
        else realm.intrinsicsAny.put(FORMATTER, formatter);
    }

    public static NeonConsole sinkOf(Realm realm) {
        Object sink = realm.intrinsicsAny.get(SINK);
        return sink instanceof NeonConsole console ? console : null;
    }

    private static final class State {
        final Realm realm;
        final NeonConsole sink;
        int indent;
        final HashMap<String, Long> counts = new HashMap<>();
        final HashMap<String, Double> timers = new HashMap<>();

        State(Realm realm, NeonConsole sink) {
            this.realm = realm;
            this.sink = sink;
        }

        void emit(Level level, String text) {
            String message = text.length() > MAX_MESSAGE
                    ? text.substring(0, MAX_MESSAGE) + "... <truncated>" : text;
            if (indent != 0) {
                String pad = " ".repeat(indent * 2);
                StringBuilder builder = new StringBuilder();
                boolean first = true;
                for (String line : message.lines().toArray(String[]::new)) {
                    if (!first) builder.append('\n');
                    first = false;
                    builder.append(pad).append(line);
                }
                message = builder.toString();
            }
            sink.write(level, message);
        }

        String label(Object[] arguments) {
            return arguments.length == 0 || arguments[0] == Undefined.INSTANCE
                    ? "default" : Ops.toString(arguments[0]);
        }

        String format(Object[] arguments) {
            Object formatter = realm.intrinsicsAny.get(FORMATTER);
            return formatter instanceof Formatter custom
                    ? custom.format(arguments) : ConsoleBuiltins.format(arguments);
        }

        String inspect(Object value, Object options) {
            Object formatter = realm.intrinsicsAny.get(FORMATTER);
            return formatter instanceof Formatter custom
                    ? custom.inspect(value, options) : Inspector.inspect(value);
        }

        String elapsed(String label) {
            Double start = timers.get(label);
            if (start == null) {
                emit(Level.WARN, "Warning: No such label '" + label + "' for console.timeEnd()");
                return null;
            }
            return label + ": " + NumberConv.toString(realm.agent.currentTimeMillis() - start) + "ms";
        }
    }

    private static void def(Realm realm, JSObject console, String name, int length,
                            Consumer<Object[]> implementation) {
        console.defineOwn(name, new NativeFunction(realm, name, length,
                (function, receiver, arguments, newTarget) -> {
                    implementation.accept(arguments);
                    return Undefined.INSTANCE;
                }), Attr.ALL);
    }

    public void install(Realm realm, NeonConsole sink) {
        State state = new State(realm, sink);
        realm.intrinsicsAny.put(SINK, sink);
        JSObject console = new JSObject(realm.getObjectPrototype());
        def(realm, console, "log", 0, arguments -> state.emit(Level.LOG, state.format(arguments)));
        def(realm, console, "info", 0, arguments -> state.emit(Level.INFO, state.format(arguments)));
        def(realm, console, "debug", 0, arguments -> state.emit(Level.DEBUG, state.format(arguments)));
        def(realm, console, "warn", 0, arguments -> state.emit(Level.WARN, state.format(arguments)));
        def(realm, console, "error", 0, arguments -> state.emit(Level.ERROR, state.format(arguments)));
        def(realm, console, "trace", 0, arguments -> {
            String head = arguments.length == 0 ? "Trace" : "Trace: " + state.format(arguments);
            state.emit(Level.ERROR, head + "\n" + realm.agent.captureStack().stripTrailing());
        });
        def(realm, console, "dir", 0, arguments ->
                state.emit(Level.LOG, state.inspect(arg(arguments, 0), arg(arguments, 1))));
        def(realm, console, "assert", 0, arguments -> {
            if (!Ops.toBoolean(arg(arguments, 0))) {
                Object[] rest = java.util.Arrays.copyOfRange(arguments, Math.min(1, arguments.length),
                        arguments.length);
                state.emit(Level.ERROR, rest.length == 0 ? "Assertion failed"
                        : "Assertion failed: " + state.format(rest));
            }
        });
        def(realm, console, "count", 0, arguments -> {
            String label = state.label(arguments);
            long count = state.counts.getOrDefault(label, 0L) + 1;
            state.counts.put(label, count);
            state.emit(Level.INFO, label + ": " + count);
        });
        def(realm, console, "countReset", 0, arguments -> state.counts.remove(state.label(arguments)));
        def(realm, console, "time", 0, arguments -> {
            String label = state.label(arguments);
            if (state.timers.containsKey(label)) {
                state.emit(Level.WARN, "Warning: Label '" + label + "' already exists for console.time()");
            } else {
                state.timers.put(label, realm.agent.currentTimeMillis());
            }
        });
        def(realm, console, "timeEnd", 0, arguments -> {
            String label = state.label(arguments);
            String elapsed = state.elapsed(label);
            if (elapsed != null) state.emit(Level.INFO, elapsed);
            state.timers.remove(label);
        });
        def(realm, console, "timeLog", 0, arguments -> {
            String elapsed = state.elapsed(state.label(arguments));
            if (elapsed != null) {
                Object[] rest = java.util.Arrays.copyOfRange(arguments, Math.min(1, arguments.length),
                        arguments.length);
                state.emit(Level.INFO, rest.length == 0 ? elapsed : elapsed + " " + state.format(rest));
            }
        });
        def(realm, console, "group", 0, arguments -> {
            if (arguments.length != 0) state.emit(Level.LOG, state.format(arguments));
            state.indent++;
        });
        def(realm, console, "groupCollapsed", 0, arguments -> {
            if (arguments.length != 0) state.emit(Level.LOG, state.format(arguments));
            state.indent++;
        });
        def(realm, console, "groupEnd", 0, arguments -> {
            if (state.indent > 0) state.indent--;
        });
        def(realm, console, "table", 1, arguments -> {
            String table = table(arg(arguments, 0));
            state.emit(Level.LOG, table == null ? state.format(arguments) : table);
        });
        console.defineOwn(JSSymbol.toStringTag, "console", Attr.CONFIGURABLE);
        realm.getGlobalObject().defineOwn("console", console, Attr.WC);
    }

    /** Node-style util.format: {@code %s %d %i %f %j %o %O %c %%}. */
    public static String format(Object[] arguments) {
        if (arguments.length == 0) return "";
        StringBuilder builder = new StringBuilder();
        int next;
        Object first = arguments[0];
        if (first instanceof CharSequence && arguments.length > 1) {
            String format = first.toString();
            next = 1;
            int i = 0;
            while (i < format.length()) {
                char c = format.charAt(i);
                if (c == '%' && i + 1 < format.length()) {
                    char specifier = format.charAt(i + 1);
                    if (specifier == '%') {
                        builder.append('%');
                        i += 2;
                        continue;
                    }
                    if ("sdifjoOc".indexOf(specifier) >= 0 && next < arguments.length) {
                        Object value = arguments[next++];
                        switch (specifier) {
                            case 's' -> builder.append(value instanceof JSObject
                                    ? Inspector.inspect(value, 1)
                                    : value instanceof BigInteger integer
                                    ? integer + "n" : Inspector.display(value));
                            case 'd', 'i' -> builder.append(numberSpec(value, specifier == 'i'));
                            case 'f' -> builder.append(value instanceof JSObject || value instanceof JSSymbol
                                    ? "NaN" : NumberConv.toString(safeNumber(value)));
                            case 'j', 'o' -> builder.append(Inspector.inspect(value, 4));
                            case 'O' -> builder.append(Inspector.inspect(value));
                            default -> {
                            }
                        }
                        i += 2;
                        if (builder.length() > MAX_MESSAGE) return builder.toString();
                        continue;
                    }
                }
                builder.append(c);
                i++;
            }
        } else {
            builder.append(Inspector.display(first));
            next = 1;
        }
        while (next < arguments.length) {
            builder.append(' ').append(Inspector.display(arguments[next++]));
            if (builder.length() > MAX_MESSAGE) break;
        }
        return builder.toString();
    }

    private static double safeNumber(Object value) {
        if (value instanceof Double number) return number;
        if (value instanceof Boolean bool) return bool ? 1.0 : 0.0;
        if (value instanceof CharSequence text) return NumberConv.stringToNumber(text.toString());
        if (value == Null.INSTANCE) return 0.0;
        if (value instanceof BigInteger integer) return integer.doubleValue();
        return Double.NaN;
    }

    private static String numberSpec(Object value, boolean integer) {
        if (value instanceof BigInteger big) return big + "n";
        if (value instanceof JSObject || value instanceof JSSymbol) return "NaN";
        double number = safeNumber(value);
        if (integer && Double.isFinite(number)) number = number < 0 ? Math.ceil(number) : Math.floor(number);
        return NumberConv.toString(number);
    }

    /** Renders arrays / objects of rows as a box table using own enumerable data properties only. */
    private static String table(Object data) {
        if (!(data instanceof JSObject object) || data instanceof ProxyObject || object.isCallable()) return null;
        List<Object> rowKeys;
        try {
            rowKeys = new ArrayList<>();
            for (Object key : object.ownPropertyKeys()) {
                var descriptor = object.getOwnProperty(key);
                if (!(key instanceof JSSymbol) && descriptor != null && descriptor.enumerable) {
                    rowKeys.add(key);
                }
            }
        } catch (JSException error) {
            return null;
        }
        if (rowKeys.size() > 1000) return null;
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        boolean hasValues = false;
        ArrayList<Map.Entry<String, Map<String, String>>> rows = new ArrayList<>();
        for (Object rowKey : rowKeys) {
            var descriptor = object.getOwnProperty(rowKey);
            Object value = descriptor == null || descriptor.isAccessor()
                    ? Undefined.INSTANCE : descriptor.value;
            HashMap<String, String> cells = new HashMap<>();
            if (value instanceof JSObject child && !(child instanceof ProxyObject) && !child.isCallable()) {
                for (Object columnKey : child.ownPropertyKeys()) {
                    if (columnKey instanceof JSSymbol) continue;
                    var column = child.getOwnProperty(columnKey);
                    if (column == null || !column.enumerable) continue;
                    String name = PK.toValue(columnKey).toString();
                    columns.add(name);
                    cells.put(name, column.isAccessor() ? "[Getter]" : Inspector.inspect(column.value, 0));
                }
            } else {
                hasValues = true;
                cells.put("Values", Inspector.inspect(value, 0));
            }
            rows.add(new java.util.AbstractMap.SimpleImmutableEntry<>(
                    PK.toValue(rowKey).toString(), cells));
        }
        ArrayList<String> header = new ArrayList<>();
        header.add("(index)");
        header.addAll(columns);
        if (hasValues) header.add("Values");
        int[] widths = new int[header.size()];
        for (int i = 0; i < widths.length; i++) widths[i] = header.get(i).length() + 2;
        for (Map.Entry<String, Map<String, String>> row : rows) {
            widths[0] = Math.max(widths[0], row.getKey().length() + 2);
            for (int i = 1; i < header.size(); i++) {
                widths[i] = Math.max(widths[i], row.getValue().getOrDefault(header.get(i), "").length() + 2);
            }
        }
        StringBuilder builder = new StringBuilder();
        appendLine(builder, '┌', '┬', '┐', widths);
        appendRow(builder, header, widths);
        appendLine(builder, '├', '┼', '┤', widths);
        for (Map.Entry<String, Map<String, String>> row : rows) {
            ArrayList<String> cells = new ArrayList<>(header.size());
            cells.add(row.getKey());
            for (int i = 1; i < header.size(); i++) {
                cells.add(row.getValue().getOrDefault(header.get(i), ""));
            }
            appendRow(builder, cells, widths);
        }
        appendLine(builder, '└', '┴', '┘', widths);
        int end = builder.length();
        while (end > 0 && builder.charAt(end - 1) == '\n') end--;
        return builder.substring(0, end);
    }

    private static void appendLine(StringBuilder builder, char left, char middle, char right, int[] widths) {
        builder.append(left);
        for (int i = 0; i < widths.length; i++) {
            builder.append("─".repeat(widths[i]));
            builder.append(i == widths.length - 1 ? right : middle);
        }
        builder.append('\n');
    }

    private static void appendRow(StringBuilder builder, List<String> cells, int[] widths) {
        builder.append('│');
        for (int i = 0; i < widths.length; i++) {
            String text = cells.get(i);
            int total = widths[i] - text.length();
            int left = total / 2;
            builder.append(" ".repeat(left)).append(text).append(" ".repeat(total - left)).append('│');
        }
        builder.append('\n');
    }
}
