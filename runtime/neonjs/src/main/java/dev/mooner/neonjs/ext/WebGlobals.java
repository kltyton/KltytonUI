/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.ext;

import dev.mooner.neonjs.builtins.ElementType;
import dev.mooner.neonjs.builtins.JSTypedArray;
import dev.mooner.neonjs.builtins.TypedArrayBuiltins;
import dev.mooner.neonjs.runtime.Agent;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSErrorObject;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static dev.mooner.neonjs.ext.Idl.typeErr;
import static dev.mooner.neonjs.runtime.Values.arg;

/**
 * Opt-in web platform globals: queueMicrotask, timers, structuredClone, DOMException, atob/btoa, UTF-8
 * TextEncoder/TextDecoder, Events, Abort, performance, crypto, Blob/File, FormData, Headers, Request/Response,
 * URL/URLSearchParams.
 */
public final class WebGlobals {
    public static final WebGlobals INSTANCE = new WebGlobals();

    private WebGlobals() {
    }

    public void install(Realm realm, int maxTimers) {
        Performance.startClock(realm);
        WebInterface.globalOperation(realm, "queueMicrotask", 1, (function, receiver, arguments, newTarget) -> {
            Object callback = arg(arguments, 0);
            if (!Ops.isCallable(callback)) throw typeErr("queueMicrotask requires a function");
            realm.agent.enqueueJob(() -> Ops.call(callback, Undefined.INSTANCE, Values.EMPTY_ARGS));
            return Undefined.INSTANCE;
        });
        installDOMException(realm);
        WebInterface.globalOperation(realm, "structuredClone", 1, (function, receiver, arguments, newTarget) -> {
            if (arguments.length == 0) throw typeErr("structuredClone requires 1 argument");
            return new StructuredClone(function.realm).run(arguments[0],
                    StructuredClone.transferList(function.realm, arg(arguments, 1)));
        });
        installTimers(realm, maxTimers);
        Events.install(realm);
        Abort.install(realm);
        Performance.install(realm);
        Crypto.install(realm);
        Blob.install(realm);
        FormData.install(realm);
        Headers.install(realm);
        Fetch.install(realm);
        Url.install(realm);
        installBase64(realm);
        installTextEncoder(realm);
        installTextDecoder(realm);
    }

    // ------------------------------------------------------------------ DOMException

    /** A DOMException: an error object whose name and message are internal slots. */
    static class JSDOMException extends JSErrorObject {
        final String excName;
        final String excMessage;

        JSDOMException(JSObject prototype, String name, String message) {
            super(prototype);
            this.excName = name;
            this.excMessage = message;
        }

        @Override
        public String getClassName() {
            return "DOMException";
        }

        @Override
        public String getSlotName() {
            return excName;
        }

        @Override
        public String getSlotMessage() {
            return excMessage;
        }
    }

    record LegacyCode(String name, int code) {
    }

    private static final LinkedHashMap<String, LegacyCode> LEGACY_CODES = new LinkedHashMap<>();
    private static final List<LegacyCode> EXTRA_CONSTANTS = List.of(
            new LegacyCode("DOMSTRING_SIZE_ERR", 2), new LegacyCode("NO_DATA_ALLOWED_ERR", 6),
            new LegacyCode("VALIDATION_ERR", 16));

    static {
        LEGACY_CODES.put("IndexSizeError", new LegacyCode("INDEX_SIZE_ERR", 1));
        LEGACY_CODES.put("HierarchyRequestError", new LegacyCode("HIERARCHY_REQUEST_ERR", 3));
        LEGACY_CODES.put("WrongDocumentError", new LegacyCode("WRONG_DOCUMENT_ERR", 4));
        LEGACY_CODES.put("InvalidCharacterError", new LegacyCode("INVALID_CHARACTER_ERR", 5));
        LEGACY_CODES.put("NoModificationAllowedError", new LegacyCode("NO_MODIFICATION_ALLOWED_ERR", 7));
        LEGACY_CODES.put("NotFoundError", new LegacyCode("NOT_FOUND_ERR", 8));
        LEGACY_CODES.put("NotSupportedError", new LegacyCode("NOT_SUPPORTED_ERR", 9));
        LEGACY_CODES.put("InUseAttributeError", new LegacyCode("INUSE_ATTRIBUTE_ERR", 10));
        LEGACY_CODES.put("InvalidStateError", new LegacyCode("INVALID_STATE_ERR", 11));
        LEGACY_CODES.put("SyntaxError", new LegacyCode("SYNTAX_ERR", 12));
        LEGACY_CODES.put("InvalidModificationError", new LegacyCode("INVALID_MODIFICATION_ERR", 13));
        LEGACY_CODES.put("NamespaceError", new LegacyCode("NAMESPACE_ERR", 14));
        LEGACY_CODES.put("InvalidAccessError", new LegacyCode("INVALID_ACCESS_ERR", 15));
        LEGACY_CODES.put("TypeMismatchError", new LegacyCode("TYPE_MISMATCH_ERR", 17));
        LEGACY_CODES.put("SecurityError", new LegacyCode("SECURITY_ERR", 18));
        LEGACY_CODES.put("NetworkError", new LegacyCode("NETWORK_ERR", 19));
        LEGACY_CODES.put("AbortError", new LegacyCode("ABORT_ERR", 20));
        LEGACY_CODES.put("URLMismatchError", new LegacyCode("URL_MISMATCH_ERR", 21));
        LEGACY_CODES.put("QuotaExceededError", new LegacyCode("QUOTA_EXCEEDED_ERR", 22));
        LEGACY_CODES.put("TimeoutError", new LegacyCode("TIMEOUT_ERR", 23));
        LEGACY_CODES.put("InvalidNodeTypeError", new LegacyCode("INVALID_NODE_TYPE_ERR", 24));
        LEGACY_CODES.put("DataCloneError", new LegacyCode("DATA_CLONE_ERR", 25));
    }

    static JSDOMException newDOMException(Realm realm, String message, String name) {
        JSDOMException error = new JSDOMException(realm.intrinsic("%DOMException.prototype%"), name, message);
        if (realm.agent.topFrame != null) error.stackTrace = realm.agent.captureStack();
        return error;
    }

    static final class JSQuotaExceededError extends JSDOMException {
        final Double quota;
        final Double requested;

        JSQuotaExceededError(JSObject prototype, String message, Double quota, Double requested) {
            super(prototype, "QuotaExceededError", message);
            this.quota = quota;
            this.requested = requested;
        }

        @Override
        public String getClassName() {
            return "QuotaExceededError";
        }
    }

    static JSQuotaExceededError newQuotaExceededError(Realm realm, String message) {
        JSQuotaExceededError error = new JSQuotaExceededError(
                realm.intrinsic("%QuotaExceededError.prototype%"), message, null, null);
        if (realm.agent.topFrame != null) error.stackTrace = realm.agent.captureStack();
        return error;
    }

    static JSException domException(Realm realm, String message, String name) {
        return new JSException(newDOMException(realm, message, name));
    }

    private static void installDOMException(Realm realm) {
        WebInterface iface = WebInterface.define(realm, "DOMException", 0, null, realm.getErrorPrototype(),
                (arguments, prototype) -> {
                    String message = arg(arguments, 0) == Undefined.INSTANCE
                            ? "" : Idl.domString(arg(arguments, 0));
                    String name = arg(arguments, 1) == Undefined.INSTANCE
                            ? "Error" : Idl.domString(arg(arguments, 1));
                    JSDOMException error = new JSDOMException(prototype, name, message);
                    if (realm.agent.topFrame != null) error.stackTrace = realm.agent.captureStack();
                    return error;
                });
        iface.attribute("name", (function, receiver, arguments, newTarget) ->
                Idl.self(JSDOMException.class, receiver, "DOMException", "name").excName);
        iface.attribute("message", (function, receiver, arguments, newTarget) ->
                Idl.self(JSDOMException.class, receiver, "DOMException", "message").excMessage);
        iface.attribute("code", (function, receiver, arguments, newTarget) -> {
            JSDOMException error = Idl.self(JSDOMException.class, receiver, "DOMException", "code");
            LegacyCode code = LEGACY_CODES.get(error.excName);
            return (double) (code == null ? 0 : code.code());
        });
        ArrayList<LegacyCode> constants = new ArrayList<>(LEGACY_CODES.values());
        constants.addAll(EXTRA_CONSTANTS);
        constants.sort((a, b) -> Integer.compare(a.code(), b.code()));
        for (LegacyCode constant : constants) iface.constant(constant.name(), constant.code());

        WebInterface quota = WebInterface.define(realm, "QuotaExceededError", 0, "DOMException",
                (arguments, prototype) -> {
                    String message = arg(arguments, 0) == Undefined.INSTANCE
                            ? "" : Idl.domString(arg(arguments, 0));
                    JSObject options = Idl.dictionary(arg(arguments, 1), "QuotaExceededErrorOptions");
                    Object quotaValue = Idl.member(options, "quota");
                    Double quotaSize = quotaValue == Undefined.INSTANCE
                            ? null : Idl.doubleValue(quotaValue, "quota");
                    Object requestedValue = Idl.member(options, "requested");
                    Double requested = requestedValue == Undefined.INSTANCE
                            ? null : Idl.doubleValue(requestedValue, "requested");
                    if (quotaSize != null && quotaSize < 0) {
                        throw JSException.rangeError("QuotaExceededError: quota is negative");
                    }
                    if (requested != null && requested < 0) {
                        throw JSException.rangeError("QuotaExceededError: requested is negative");
                    }
                    if (quotaSize != null && requested != null && requested < quotaSize) {
                        throw JSException.rangeError("QuotaExceededError: requested is less than quota");
                    }
                    JSQuotaExceededError error = new JSQuotaExceededError(prototype, message, quotaSize, requested);
                    if (realm.agent.topFrame != null) error.stackTrace = realm.agent.captureStack();
                    return error;
                });
        quota.attribute("quota", (function, receiver, arguments, newTarget) -> {
            JSQuotaExceededError error = Idl.self(JSQuotaExceededError.class, receiver,
                    "QuotaExceededError", "quota");
            return error.quota == null ? Null.INSTANCE : error.quota;
        });
        quota.attribute("requested", (function, receiver, arguments, newTarget) -> {
            JSQuotaExceededError error = Idl.self(JSQuotaExceededError.class, receiver,
                    "QuotaExceededError", "requested");
            return error.requested == null ? Null.INSTANCE : error.requested;
        });
    }

    // ------------------------------------------------------------------ timers

    private static final class SchedulerHolder {
        static final ScheduledThreadPoolExecutor SCHEDULER = createScheduler();
    }

    private static ScheduledThreadPoolExecutor createScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "neonjs-timers");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static ScheduledThreadPoolExecutor scheduler() {
        return SchedulerHolder.SCHEDULER;
    }

    static int scheduledTaskCount() {
        return scheduler().getQueue().size();
    }

    /** A task scheduled with {@link Timers#task}; cancelling it withdraws it. */
    public static final class ScheduledTask implements Agent.ExternalSource {
        volatile ScheduledFuture<?> future;
        volatile boolean cancelled;
        boolean done;

        ScheduledTask() {
        }

        @Override
        public void cancel() {
            cancelled = true;
            ScheduledFuture<?> pending = future;
            if (pending != null) pending.cancel(false);
        }
    }

    public static Timers timersOf(Realm realm) {
        Object timers = realm.intrinsicsAny.get("%Timers%");
        return timers instanceof Timers value ? value : null;
    }

    public static String domainToAscii(String domain) {
        return dev.mooner.neonjs.unicode.Idna.domainToAscii(domain);
    }

    public static String domainToUnicode(String domain) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (String label : domain.split("\\.", -1)) {
            if (!first) builder.append('.');
            first = false;
            if (label.length() > 4 && label.regionMatches(true, 0, "xn--", 0, 4)) {
                String decoded = dev.mooner.neonjs.unicode.Punycode.decode(label.substring(4));
                builder.append(decoded == null ? label : decoded);
            } else {
                builder.append(label);
            }
        }
        return builder.toString();
    }

    public static boolean isEventTarget(Object value) {
        return value instanceof Events.JSEventTarget;
    }

    public static List<JSObject> eventListeners(Object target, String type) {
        if (!(target instanceof Events.JSEventTarget eventTarget)) return null;
        ArrayList<JSObject> callbacks = new ArrayList<>();
        for (Events.Listener listener : eventTarget.listeners) {
            if (!listener.removed && listener.type.equals(type) && listener.callback != null) {
                callbacks.add(listener.callback);
            }
        }
        return callbacks;
    }

    private static final class Timer implements Agent.ExternalSource {
        final int id;
        final Object function;
        final Object[] arguments;
        final long interval;
        volatile ScheduledFuture<?> future;
        volatile boolean cancelled;

        Timer(int id, Object function, Object[] arguments, long interval) {
            this.id = id;
            this.function = function;
            this.arguments = arguments;
            this.interval = interval;
        }

        @Override
        public void cancel() {
            cancelled = true;
            ScheduledFuture<?> pending = future;
            if (pending != null) pending.cancel(false);
        }
    }

    /** The timers of a realm. */
    public static final class Timers {
        final Realm realm;
        final int max;
        final HashMap<Integer, Timer> active = new HashMap<>();
        private int nextId = 1;
        private int tasks;

        Timers(Realm realm, int max) {
            this.realm = realm;
            this.max = max;
        }

        public ScheduledTask task(long milliseconds, boolean keepsAlive, Runnable job) {
            if (active.size() + tasks >= max) {
                throw JSException.rangeError("Too many active timers (limit " + max + ")");
            }
            Agent agent = realm.agent;
            ScheduledTask task = new ScheduledTask();
            if (!agent.addExternalSource(task, keepsAlive)) return null;
            tasks++;
            task.future = scheduler().schedule(() -> agent.postExternalJob(() -> {
                if (finish(task) && !task.cancelled) job.run();
            }), milliseconds, TimeUnit.MILLISECONDS);
            if (task.cancelled && task.future != null) task.future.cancel(false);
            return task;
        }

        public void cancel(ScheduledTask task) {
            if (!finish(task)) return;
            task.cancelled = true;
            if (task.future != null) task.future.cancel(false);
        }

        private boolean finish(ScheduledTask task) {
            if (task.done) return false;
            task.done = true;
            tasks--;
            realm.agent.removeExternalSource(task);
            return true;
        }

        Object start(String name, Object function, Object delay, Object[] arguments, boolean repeat) {
            if (!Ops.isCallable(function)) {
                throw typeErr(name + " requires a function (string callbacks are not supported)");
            }
            long milliseconds = delayOf(delay);
            if (active.size() + tasks >= max) {
                throw JSException.rangeError("Too many active timers (limit " + max + ")");
            }
            int id = nextId;
            while (active.containsKey(id)) id = id == Integer.MAX_VALUE ? 1 : id + 1;
            nextId = id == Integer.MAX_VALUE ? 1 : id + 1;
            Timer timer = new Timer(id, function, arguments, repeat ? milliseconds : -1L);
            if (!realm.agent.addExternalSource(timer)) return (double) id;
            active.put(id, timer);
            schedule(timer, milliseconds);
            return (double) id;
        }

        private void schedule(Timer timer, long milliseconds) {
            Agent agent = realm.agent;
            timer.future = scheduler().schedule(() -> agent.postExternalJob(() -> fire(timer)),
                    milliseconds, TimeUnit.MILLISECONDS);
            if (timer.cancelled && timer.future != null) timer.future.cancel(false);
        }

        private void stop(Timer timer) {
            timer.cancel();
            if (active.get(timer.id) == timer) active.remove(timer.id);
            realm.agent.removeExternalSource(timer);
        }

        private void fire(Timer timer) {
            if (timer.cancelled) return;
            if (timer.interval < 0) {
                stop(timer);
                Ops.call(timer.function, Undefined.INSTANCE, timer.arguments);
                return;
            }
            try {
                Ops.call(timer.function, Undefined.INSTANCE, timer.arguments);
            } catch (JSException error) {
                if (!timer.cancelled) schedule(timer, timer.interval);
                throw error;
            } catch (RuntimeException | Error error) {
                stop(timer);
                throw error;
            }
            if (!timer.cancelled) schedule(timer, timer.interval);
        }

        void clear(Object handle) {
            if (handle == Undefined.INSTANCE || handle == Null.INSTANCE) return;
            double value = Ops.toNumber(handle);
            if (Double.isNaN(value) || value < 1 || value > Integer.MAX_VALUE) return;
            Timer timer = active.get((int) value);
            if (timer == null) return;
            stop(timer);
        }

        private long delayOf(Object value) {
            double delay = value == Undefined.INSTANCE ? 0.0 : Ops.toNumber(value);
            return Double.isNaN(delay) || delay < 1 ? 0L
                    : (long) Math.min(delay, Integer.MAX_VALUE);
        }
    }

    private static void installTimers(Realm realm, int maxTimers) {
        Timers timers = new Timers(realm, maxTimers);
        realm.intrinsicsAny.put("%Timers%", timers);
        List<NativeFunction> functions = List.of(
                starter(realm, timers, "setTimeout", false),
                starter(realm, timers, "setInterval", true),
                clearer(realm, timers, "clearTimeout"),
                clearer(realm, timers, "clearInterval"));
        for (NativeFunction function : functions) {
            realm.getGlobalObject().defineOwn(function.debugName(), function, Attr.ALL);
        }
    }

    private static NativeFunction starter(Realm realm, Timers timers, String name, boolean repeat) {
        return new NativeFunction(realm, name, 1, (function, receiver, arguments, newTarget) -> {
            Object[] rest = arguments.length > 2
                    ? java.util.Arrays.copyOfRange(arguments, 2, arguments.length) : Values.EMPTY_ARGS;
            return timers.start(name, arg(arguments, 0), arg(arguments, 1), rest, repeat);
        });
    }

    private static NativeFunction clearer(Realm realm, Timers timers, String name) {
        return new NativeFunction(realm, name, 0, (function, receiver, arguments, newTarget) -> {
            timers.clear(arg(arguments, 0));
            return Undefined.INSTANCE;
        });
    }

    // ------------------------------------------------------------------ atob / btoa

    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private static void installBase64(Realm realm) {
        WebInterface.globalOperation(realm, "btoa", 1, (function, receiver, arguments, newTarget) -> {
            Idl.required(arguments, 1, "btoa");
            String text = Idl.domString(arguments[0]);
            byte[] bytes = new byte[text.length()];
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c > 0xFF) {
                    throw domException(function.realm,
                            "The string to be encoded contains characters outside of the Latin1 range.",
                            "InvalidCharacterError");
                }
                bytes[i] = (byte) c;
            }
            function.realm.agent.checkStringLength((text.length() + 2L) / 3 * 4);
            return java.util.Base64.getEncoder().encodeToString(bytes);
        });
        WebInterface.globalOperation(realm, "atob", 1, (function, receiver, arguments, newTarget) -> {
            Idl.required(arguments, 1, "atob");
            String decoded = forgivingBase64Decode(Idl.domString(arguments[0]));
            if (decoded == null) {
                throw domException(function.realm,
                        "The string to be decoded is not correctly encoded.", "InvalidCharacterError");
            }
            return decoded;
        });
    }

    /** The forgiving-base64 decode of the Infra standard, to a binary string; null on failure. */
    static String forgivingBase64Decode(String input) {
        StringBuilder filtered = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\f' && c != '\r') filtered.append(c);
        }
        int length = filtered.length();
        if (length % 4 == 0) {
            if (length > 0 && filtered.charAt(length - 1) == '=') length--;
            if (length > 0 && filtered.charAt(length - 1) == '=') length--;
        }
        if (length % 4 == 1) return null;
        StringBuilder out = new StringBuilder(length * 3 / 4);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < length; i++) {
            int value = B64.indexOf(filtered.charAt(i));
            if (value < 0) return null;
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.append((char) ((buffer >> bits) & 0xFF));
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ TextEncoder

    private static final class JSTextEncoder extends JSObject {
        JSTextEncoder(JSObject prototype) {
            super(prototype);
        }

        @Override
        public String getClassName() {
            return "TextEncoder";
        }
    }

    /** UTF-8 encoding of {@code text}, unpaired surrogates encoded as U+FFFD. */
    static byte[] utf8(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            int codePoint = codePointOrReplacement(text, i);
            i += codePoint >= 0x10000 ? 2 : 1;
            writeUtf8(out, codePoint);
        }
        return out.toByteArray();
    }

    private static int codePointOrReplacement(String text, int index) {
        char c = text.charAt(index);
        if (Character.isHighSurrogate(c) && index + 1 < text.length()
                && Character.isLowSurrogate(text.charAt(index + 1))) {
            return Character.toCodePoint(c, text.charAt(index + 1));
        }
        return Character.isSurrogate(c) ? 0xFFFD : c;
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) return 1;
        if (codePoint < 0x800) return 2;
        if (codePoint < 0x10000) return 3;
        return 4;
    }

    private static void writeUtf8(ByteArrayOutputStream out, int codePoint) {
        switch (utf8Length(codePoint)) {
            case 1 -> out.write(codePoint);
            case 2 -> {
                out.write(0xC0 | (codePoint >> 6));
                out.write(0x80 | (codePoint & 0x3F));
            }
            case 3 -> {
                out.write(0xE0 | (codePoint >> 12));
                out.write(0x80 | ((codePoint >> 6) & 0x3F));
                out.write(0x80 | (codePoint & 0x3F));
            }
            default -> {
                out.write(0xF0 | (codePoint >> 18));
                out.write(0x80 | ((codePoint >> 12) & 0x3F));
                out.write(0x80 | ((codePoint >> 6) & 0x3F));
                out.write(0x80 | (codePoint & 0x3F));
            }
        }
    }

    private static void installTextEncoder(Realm realm) {
        WebInterface iface = WebInterface.define(realm, "TextEncoder", 0, null, null,
                (arguments, prototype) -> new JSTextEncoder(prototype));
        iface.operation("encode", 0, (function, receiver, arguments, newTarget) -> {
            Idl.self(JSTextEncoder.class, receiver, "TextEncoder", "encode");
            Object input = arg(arguments, 0);
            byte[] bytes = utf8(input == Undefined.INSTANCE ? "" : Idl.usvString(input));
            JSTypedArray array = TypedArrayBuiltins.allocate(function.realm, ElementType.UINT8,
                    TypedArrayBuiltins.protoOf(function.realm, ElementType.UINT8), bytes.length);
            System.arraycopy(bytes, 0, array.buffer.data, 0, bytes.length);
            return array;
        });
        iface.operation("encodeInto", 2, (function, receiver, arguments, newTarget) -> {
            Idl.self(JSTextEncoder.class, receiver, "TextEncoder", "encodeInto");
            Idl.required(arguments, 2, "TextEncoder.encodeInto");
            String text = Idl.usvString(arguments[0]);
            if (!(arg(arguments, 1) instanceof JSTypedArray array) || array.type != ElementType.UINT8) {
                throw typeErr("TextEncoder.prototype.encodeInto requires a Uint8Array destination");
            }
            int capacity = Math.max(array.lengthOrOOB(), 0);
            byte[] data = array.buffer.data;
            int read = 0;
            int written = 0;
            while (read < text.length()) {
                int codePoint = codePointOrReplacement(text, read);
                int needed = utf8Length(codePoint);
                if (written + needed > capacity) break;
                ByteArrayOutputStream temporary = new ByteArrayOutputStream(4);
                writeUtf8(temporary, codePoint);
                System.arraycopy(temporary.toByteArray(), 0, data, array.byteOffset + written, needed);
                written += needed;
                read += codePoint >= 0x10000 ? 2 : 1;
            }
            JSObject result = new JSObject(function.realm.getObjectPrototype());
            result.createDataProperty("read", (double) read);
            result.createDataProperty("written", (double) written);
            return result;
        });
        iface.attribute("encoding", (function, receiver, arguments, newTarget) -> {
            Idl.self(JSTextEncoder.class, receiver, "TextEncoder", "encoding");
            return "utf-8";
        });
    }

    // ------------------------------------------------------------------ TextDecoder

    private static final class JSTextDecoder extends JSObject {
        final boolean fatal;
        final boolean ignoreBOM;
        int codePoint;
        int bytesSeen;
        int bytesNeeded;
        int lower = 0x80;
        int upper = 0xBF;
        boolean bomHandled;

        JSTextDecoder(JSObject prototype, boolean fatal, boolean ignoreBOM) {
            super(prototype);
            this.fatal = fatal;
            this.ignoreBOM = ignoreBOM;
        }

        @Override
        public String getClassName() {
            return "TextDecoder";
        }

        void reset() {
            codePoint = 0;
            bytesSeen = 0;
            bytesNeeded = 0;
            lower = 0x80;
            upper = 0xBF;
            bomHandled = false;
        }
    }

    private static final Set<String> UTF8_LABELS = Set.of(
            "unicode-1-1-utf-8", "unicode11utf8", "unicode20utf8", "utf-8", "utf8",
            "x-unicode20utf8");

    private static void installTextDecoder(Realm realm) {
        WebInterface iface = WebInterface.define(realm, "TextDecoder", 0, null, null,
                (arguments, prototype) -> {
                    String label = arg(arguments, 0) == Undefined.INSTANCE ? "utf-8"
                            : Idl.domString(arg(arguments, 0)).strip().toLowerCase(Locale.ROOT);
                    JSObject options = Idl.dictionary(arg(arguments, 1), "TextDecoder options");
                    boolean fatal = Ops.toBoolean(Idl.member(options, "fatal"));
                    boolean ignoreBOM = Ops.toBoolean(Idl.member(options, "ignoreBOM"));
                    if (!UTF8_LABELS.contains(label)) {
                        throw JSException.rangeError("TextDecoder: unsupported encoding '" + label
                                + "' (only UTF-8 is available)");
                    }
                    return new JSTextDecoder(prototype, fatal, ignoreBOM);
                });
        iface.attribute("encoding", (function, receiver, arguments, newTarget) -> {
            Idl.self(JSTextDecoder.class, receiver, "TextDecoder", "encoding");
            return "utf-8";
        });
        iface.attribute("fatal", (function, receiver, arguments, newTarget) ->
                Idl.self(JSTextDecoder.class, receiver, "TextDecoder", "fatal").fatal);
        iface.attribute("ignoreBOM", (function, receiver, arguments, newTarget) ->
                Idl.self(JSTextDecoder.class, receiver, "TextDecoder", "ignoreBOM").ignoreBOM);
        iface.operation("decode", 0, (function, receiver, arguments, newTarget) -> {
            JSTextDecoder decoder = Idl.self(JSTextDecoder.class, receiver, "TextDecoder", "decode");
            byte[] bytes = arg(arguments, 0) == Undefined.INSTANCE
                    ? new byte[0] : Idl.bytes(arg(arguments, 0), "TextDecoder.decode input", true);
            JSObject options = Idl.dictionary(arg(arguments, 1), "TextDecoder.decode options");
            boolean stream = Ops.toBoolean(Idl.member(options, "stream"));
            return decode(function.realm, decoder, bytes, stream);
        });
    }

    /** UTF-8 decode (Encoding Standard): a leading BOM dropped, invalid sequences replaced by U+FFFD. */
    static String utf8Decode(Realm realm, byte[] bytes) {
        return decode(realm, new JSTextDecoder(null, false, false), bytes, false);
    }

    private static void emitCodePoint(JSTextDecoder decoder, StringBuilder out, int codePoint) {
        if (!decoder.bomHandled) {
            decoder.bomHandled = true;
            if (codePoint == 0xFEFF && !decoder.ignoreBOM) return;
        }
        out.appendCodePoint(codePoint);
    }

    private static void decodeError(Realm realm, JSTextDecoder decoder, StringBuilder out) {
        if (decoder.fatal) {
            decoder.reset();
            throw typeErr("TextDecoder: the encoded data is not valid UTF-8");
        }
        emitCodePoint(decoder, out, 0xFFFD);
    }

    private static String decode(Realm realm, JSTextDecoder decoder, byte[] bytes, boolean stream) {
        StringBuilder out = new StringBuilder(bytes.length);
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xFF;
            if (decoder.bytesNeeded == 0) {
                if (b >= 0x00 && b <= 0x7F) {
                    emitCodePoint(decoder, out, b);
                } else if (b >= 0xC2 && b <= 0xDF) {
                    decoder.bytesNeeded = 1;
                    decoder.codePoint = b & 0x1F;
                } else if (b >= 0xE0 && b <= 0xEF) {
                    if (b == 0xE0) decoder.lower = 0xA0;
                    if (b == 0xED) decoder.upper = 0x9F;
                    decoder.bytesNeeded = 2;
                    decoder.codePoint = b & 0xF;
                } else if (b >= 0xF0 && b <= 0xF4) {
                    if (b == 0xF0) decoder.lower = 0x90;
                    if (b == 0xF4) decoder.upper = 0x8F;
                    decoder.bytesNeeded = 3;
                    decoder.codePoint = b & 0x7;
                } else {
                    decodeError(realm, decoder, out);
                }
                i++;
                continue;
            }
            if (b < decoder.lower || b > decoder.upper) {
                decoder.codePoint = 0;
                decoder.bytesNeeded = 0;
                decoder.bytesSeen = 0;
                decoder.lower = 0x80;
                decoder.upper = 0xBF;
                decodeError(realm, decoder, out);
                continue;
            }
            decoder.lower = 0x80;
            decoder.upper = 0xBF;
            decoder.codePoint = (decoder.codePoint << 6) | (b & 0x3F);
            decoder.bytesSeen++;
            i++;
            if (decoder.bytesSeen == decoder.bytesNeeded) {
                int codePoint = decoder.codePoint;
                decoder.codePoint = 0;
                decoder.bytesNeeded = 0;
                decoder.bytesSeen = 0;
                emitCodePoint(decoder, out, codePoint);
            }
            if (out.length() > 65536) realm.agent.checkStringLength(out.length());
        }
        if (!stream) {
            if (decoder.bytesNeeded != 0) {
                decoder.codePoint = 0;
                decoder.bytesNeeded = 0;
                decoder.bytesSeen = 0;
                decoder.lower = 0x80;
                decoder.upper = 0xBF;
                decodeError(realm, decoder, out);
            }
            decoder.reset();
        }
        realm.agent.checkStringLength(out.length());
        return out.toString();
    }
}
