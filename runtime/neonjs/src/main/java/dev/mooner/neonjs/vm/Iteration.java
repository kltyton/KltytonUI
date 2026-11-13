package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PropertyMap;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.TerminationException;
import dev.mooner.neonjs.runtime.Undefined;
import java.util.ArrayList;
import static dev.mooner.neonjs.runtime.Values.EMPTY_ARGS;

/** Synchronous and asynchronous iterator protocols; derived from NeonJS and adapted to Java. */
public final class Iteration {
    private Iteration() {
    }

    public static IteratorRecord getIterator(Realm realm, Object value, boolean async) {
        if (async) {
            Object method = Ops.getMethod(value, JSSymbol.asyncIterator);
            if (method == Undefined.INSTANCE) {
                Object sync = Ops.getMethod(value, JSSymbol.iterator);
                if (sync == Undefined.INSTANCE) throw JSException.typeError(Ops.describe(value) + " is not async iterable");
                return AsyncFromSyncIterator.create(realm, fromMethod(value, sync));
            }
            return fromMethod(value, method);
        }
        Object method = Ops.getMethod(value, JSSymbol.iterator);
        if (method == Undefined.INSTANCE) throw JSException.typeError(Ops.describe(value) + " is not iterable");
        return fromMethod(value, method);
    }

    public static IteratorRecord fromMethod(Object value, Object method) {
        Object result = Ops.call(method, value, EMPTY_ARGS);
        if (!(result instanceof JSObject iterator)) {
            throw JSException.typeError("Result of the Symbol.iterator method is not an object");
        }
        return new IteratorRecord(iterator, iterator.get("next", iterator));
    }

    public static Object stepValue(IteratorRecord record) {
        boolean completed = false;
        try {
            Object result = Ops.call(record.nextMethod, record.iterator, EMPTY_ARGS);
            if (!(result instanceof JSObject object)) {
                record.done = true;
                throw JSException.typeError("Iterator result " + Ops.toDisplayString(result) + " is not an object");
            }
            if (Ops.toBoolean(object.get("done", object))) {
                record.done = true;
                completed = true;
                return NotFound.INSTANCE;
            }
            Object value = object.get("value", object);
            completed = true;
            return value;
        } finally {
            if (!completed) record.done = true;
        }
    }

    public static Object callNext(IteratorRecord record, Object value) {
        boolean completed = false;
        try {
            Object result = Ops.call(record.nextMethod, record.iterator,
                    value == Undefined.INSTANCE ? EMPTY_ARGS : new Object[] {value});
            completed = true;
            return result;
        } finally {
            if (!completed) record.done = true;
        }
    }

    public static boolean complete(IteratorRecord record, JSObject result) {
        boolean completed = false;
        try {
            boolean done = Ops.toBoolean(result.get("done", result));
            completed = true;
            return done;
        } finally {
            if (!completed) record.done = true;
        }
    }

    public static Object value(IteratorRecord record, JSObject result) {
        boolean completed = false;
        try {
            Object value = result.get("value", result);
            completed = true;
            return value;
        } finally {
            if (!completed) record.done = true;
        }
    }

    public static JSArray rest(Realm realm, IteratorRecord record) {
        JSArray array = new JSArray(realm.getArrayPrototype());
        while (!record.done) {
            Object value = stepValue(record);
            if (value == NotFound.INSTANCE) break;
            array.pushInit(value);
        }
        return array;
    }

    public static void closeNormal(IteratorRecord record) {
        if (record.done) return;
        record.done = true;
        Object method = Ops.getMethod(record.iterator, "return");
        if (method == Undefined.INSTANCE) return;
        Object result = Ops.call(method, record.iterator, EMPTY_ARGS);
        if (!(result instanceof JSObject)) {
            throw JSException.typeError("Iterator result " + Ops.toDisplayString(result) + " is not an object");
        }
    }

    public static void closeOnThrow(IteratorRecord record) {
        if (record.done) return;
        record.done = true;
        try {
            Object method = Ops.getMethod(record.iterator, "return");
            if (method == Undefined.INSTANCE) return;
            Ops.call(method, record.iterator, EMPTY_ARGS);
        } catch (JSException | StackOverflowError error) {
            // IteratorClose preserves the pending throw completion over a failure from return().
            return;
        }
    }

    @SuppressWarnings("unchecked")
    public static <T, E extends Throwable> T closeAndRethrow(IteratorRecord record, Throwable error) throws E {
        if (error instanceof TerminationException termination) throw termination;
        closeOnThrow(record);
        throw (E) error;
    }

    public static Object asyncCloseCall(IteratorRecord record, boolean throwing) {
        if (record.done && !throwing) return NotFound.INSTANCE;
        record.done = true;
        if (throwing) {
            try {
                Object method = Ops.getMethod(record.iterator, "return");
                return method == Undefined.INSTANCE ? NotFound.INSTANCE : Ops.call(method, record.iterator, EMPTY_ARGS);
            } catch (JSException error) {
                return NotFound.INSTANCE;
            }
        }
        Object method = Ops.getMethod(record.iterator, "return");
        return method == Undefined.INSTANCE ? NotFound.INSTANCE : Ops.call(method, record.iterator, EMPTY_ARGS);
    }

    public static Object yieldStarStep(IteratorRecord record, Object received, int mode) {
        JSObject iterator = record.iterator;
        Object method;
        boolean returning = mode == Frame.MODE_RETURN;
        if (mode == Frame.MODE_THROW) {
            method = Ops.getMethod(iterator, "throw");
            if (method == Undefined.INSTANCE) {
                record.done = false;
                closeNormal(record);
                throw JSException.typeError("The iterator does not provide a 'throw' method");
            }
        } else if (returning) {
            method = Ops.getMethod(iterator, "return");
            if (method == Undefined.INSTANCE) return new YStarDone(received, true);
        } else {
            method = record.nextMethod;
        }
        Object result = Ops.call(method, iterator, new Object[] {received});
        if (!(result instanceof JSObject object)) {
            throw JSException.typeError("Iterator result " + Ops.toDisplayString(result) + " is not an object");
        }
        return Ops.toBoolean(object.get("done", object))
                ? new YStarDone(object.get("value", object), returning) : object;
    }

    public static Object yieldStarAsyncCall(IteratorRecord record, Object received, int mode) {
        JSObject iterator = record.iterator;
        if (mode == Frame.MODE_THROW) {
            Object method = Ops.getMethod(iterator, "throw");
            return method == Undefined.INSTANCE ? new YStarDone(Undefined.INSTANCE, false)
                    : Ops.call(method, iterator, new Object[] {received});
        }
        if (mode == Frame.MODE_RETURN) {
            Object method = Ops.getMethod(iterator, "return");
            return method == Undefined.INSTANCE ? new YStarDone(received, true)
                    : Ops.call(method, iterator, new Object[] {received});
        }
        return Ops.call(record.nextMethod, iterator, new Object[] {received});
    }

    public static boolean isPristineArrayIteration(Realm realm, JSArray array) {
        if (array.proto != realm.getArrayPrototype()) return false;
        PropertyMap map = array.props;
        if (map != null && map.find(JSSymbol.iterator) >= 0) return false;
        PropertyMap prototype = realm.getArrayPrototype().props;
        if (prototype == null) return false;
        int slot = prototype.find(JSSymbol.iterator);
        if (slot < 0 || prototype.values[slot] != realm.getArrayProtoValues()
                || (prototype.flags[slot] & Attr.ACCESSOR) != 0) return false;
        PropertyMap iterator = realm.getArrayIteratorPrototype().props;
        if (iterator == null) return false;
        int next = iterator.find("next");
        return next >= 0 && iterator.values[next] == realm.getArrayIteratorNext() && array.protoChainClean();
    }

    public static ArrayList<Object> toList(Realm realm, Object iterable) {
        ArrayList<Object> values = new ArrayList<>();
        if (iterable instanceof JSArray array && !array.sparse && isPristineArrayIteration(realm, array)) {
            for (int i = 0, limit = (int) array.length; i < limit; i++) values.add(array.get(i, array));
            return values;
        }
        IteratorRecord record = getIterator(realm, iterable, false);
        while (true) {
            Object value = stepValue(record);
            if (value == NotFound.INSTANCE) break;
            values.add(value);
        }
        return values;
    }

    public static JSObject createIterResult(Realm realm, Object value, boolean done) {
        JSObject result = new JSObject(realm.getObjectPrototype());
        PropertyMap map = result.ensureProps();
        map.add("value", value, Attr.ALL);
        map.add("done", done, Attr.ALL);
        return result;
    }

    public static final class YStarDone {
        public final Object value;
        public final boolean isReturn;

        public YStarDone(Object value, boolean isReturn) {
            this.value = value;
            this.isReturn = isReturn;
        }
    }
}
