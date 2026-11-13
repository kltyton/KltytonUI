/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.TerminationException;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.vm.Iteration;
import dev.mooner.neonjs.vm.IteratorRecord;
import dev.mooner.neonjs.vm.JSPromise;
import dev.mooner.neonjs.vm.PromiseCapability;
import dev.mooner.neonjs.vm.Promises;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import static dev.mooner.neonjs.builtins.BuiltinsKt.accessor;
import static dev.mooner.neonjs.builtins.BuiltinsKt.global;
import static dev.mooner.neonjs.builtins.BuiltinsKt.makeCtor;
import static dev.mooner.neonjs.builtins.BuiltinsKt.method;
import static dev.mooner.neonjs.builtins.BuiltinsKt.typeErr;
import static dev.mooner.neonjs.builtins.BuiltinsKt.value;
import static dev.mooner.neonjs.runtime.Values.EMPTY_ARGS;
import static dev.mooner.neonjs.runtime.Values.arg;

final class IteratorBuiltins {
    private static final double MAX_SAFE_INTEGER = 9007199254740991.0;

    private static final String ZIP_SHORTEST = "shortest";
    private static final String ZIP_LONGEST = "longest";
    private static final String ZIP_STRICT = "strict";

    private IteratorBuiltins() {
    }

    private record ZipOptions(String mode, Object padding) {
    }

    private record ConcatIterable(JSObject object, Object iteratorMethod) {
    }

    static void install(Realm realm) {
        JSObject iteratorPrototype = new JSObject(realm.getObjectPrototype());
        realm.setIteratorPrototype(iteratorPrototype);
        method(iteratorPrototype, realm, JSSymbol.iterator, 0, (function, receiver, arguments, newTarget) -> receiver);
        JSObject asyncIteratorPrototype = new JSObject(realm.getObjectPrototype());
        realm.setAsyncIteratorPrototype(asyncIteratorPrototype);
        method(asyncIteratorPrototype, realm, JSSymbol.asyncIterator, 0,
                (function, receiver, arguments, newTarget) -> receiver);

        // Iterator constructor (abstract)
        NativeFunction constructor = makeCtor(realm, "Iterator", 0, iteratorPrototype,
                (function, receiver, arguments, newTarget) -> {
                    if (newTarget == null || newTarget == function) {
                        throw typeErr("Abstract class Iterator not directly constructable");
                    }
                    return new JSObject(Ops.getPrototypeFromConstructor(newTarget,
                            Realm::getIteratorPrototype));
                });
        global(realm, "Iterator", constructor);
        realm.getIntrinsics().put("%Iterator%", constructor);
        // constructor / @@toStringTag are accessors per the iterator helpers spec
        accessor(iteratorPrototype, realm, "constructor",
                (function, receiver, arguments, newTarget) -> constructor,
                (function, receiver, arguments, newTarget) -> {
                    setterThatIgnoresPrototypeProperties(receiver, function.realm.getIteratorPrototype(),
                            "constructor", arg(arguments, 0));
                    return Undefined.INSTANCE;
                });
        accessor(iteratorPrototype, realm, JSSymbol.toStringTag,
                (function, receiver, arguments, newTarget) -> "Iterator",
                (function, receiver, arguments, newTarget) -> {
                    setterThatIgnoresPrototypeProperties(receiver, function.realm.getIteratorPrototype(),
                            JSSymbol.toStringTag, arg(arguments, 0));
                    return Undefined.INSTANCE;
                });
        method(iteratorPrototype, realm, JSSymbol.dispose, 0, (function, receiver, arguments, newTarget) -> {
            Object returnMethod = Ops.getMethod(receiver, "return");
            if (returnMethod != Undefined.INSTANCE) Ops.call(returnMethod, receiver, EMPTY_ARGS);
            return Undefined.INSTANCE;
        });
        method(asyncIteratorPrototype, realm, JSSymbol.asyncDispose, 0,
                (function, receiver, arguments, newTarget) -> {
                    PromiseCapability capability = Promises.newPromiseCapability(function.realm,
                            function.realm.getPromiseConstructor());
                    try {
                        Object returnMethod = Ops.getMethod(receiver, "return");
                        if (returnMethod == Undefined.INSTANCE) {
                            Ops.call(capability.resolve, Undefined.INSTANCE,
                                    new Object[] {Undefined.INSTANCE});
                        } else {
                            Object result = Ops.call(returnMethod, receiver, EMPTY_ARGS);
                            JSObject promise = Promises.promiseResolve(function.realm,
                                    function.realm.getPromiseConstructor(), result);
                            NativeFunction onFulfilled = new NativeFunction(function.realm, "", 1,
                                    (fn, recv, args, target) -> Undefined.INSTANCE);
                            Promises.performThen(function.realm, (JSPromise) promise, onFulfilled,
                                    Undefined.INSTANCE, capability);
                        }
                    } catch (JSException error) {
                        Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error.value});
                    }
                    return capability.promise;
                });

        // helper prototype
        JSObject helperPrototype = new JSObject(iteratorPrototype);
        realm.getIntrinsics().put("%IteratorHelperPrototype%", helperPrototype);
        method(helperPrototype, realm, "next", 0, (function, receiver, arguments, newTarget) -> {
            if (!(receiver instanceof IteratorHelperObject helper)) {
                throw typeErr("Iterator Helper next called on incompatible receiver");
            }
            return helperNext(function.realm, helper);
        });
        method(helperPrototype, realm, "return", 0, (function, receiver, arguments, newTarget) -> {
            if (!(receiver instanceof IteratorHelperObject helper)) {
                throw typeErr("Iterator Helper return called on incompatible receiver");
            }
            if (helper.state == 2) {
                throw typeErr("Generator is already running");
            } else if (helper.state == 3) {
                // already completed
            } else if (helper.state == 0) {
                // suspended-start: the helper completes before its underlying iterators are closed
                helper.state = 3;
                closeUnderlying(helper);
            } else {
                // suspended-yield: resume with a return completion; the closure is executing while it closes
                helper.state = 2;
                try {
                    closeUnderlying(helper);
                } finally {
                    helper.state = 3;
                }
            }
            return Iteration.createIterResult(function.realm, Undefined.INSTANCE, true);
        });
        value(helperPrototype, JSSymbol.toStringTag, "Iterator Helper", Attr.CONFIGURABLE);

        // wrap-for-valid-iterator prototype
        JSObject wrapPrototype = new JSObject(iteratorPrototype);
        method(wrapPrototype, realm, "next", 0, (function, receiver, arguments, newTarget) -> {
            if (!(receiver instanceof WrappedIteratorObject wrapped)) {
                throw typeErr("incompatible receiver");
            }
            return Ops.call(wrapped.rec.nextMethod, wrapped.rec.iterator, EMPTY_ARGS);
        });
        method(wrapPrototype, realm, "return", 0, (function, receiver, arguments, newTarget) -> {
            if (!(receiver instanceof WrappedIteratorObject wrapped)) {
                throw typeErr("incompatible receiver");
            }
            JSObject iterator = wrapped.rec.iterator;
            Object returnMethod = Ops.getMethod(iterator, "return");
            if (returnMethod == Undefined.INSTANCE) {
                return Iteration.createIterResult(function.realm, Undefined.INSTANCE, true);
            }
            return Ops.call(returnMethod, iterator, EMPTY_ARGS);
        });

        method(constructor, realm, "from", 1, (function, receiver, arguments, newTarget) -> {
            Object object = arg(arguments, 0);
            IteratorRecord record = getIteratorFlattenable(object, true);
            if (Ops.ordinaryHasInstance(constructor, record.iterator)) return record.iterator;
            return new WrappedIteratorObject(wrapPrototype, record);
        });
        method(constructor, realm, "concat", 0, (function, receiver, arguments, newTarget) -> {
            ArrayList<ConcatIterable> iterables = new ArrayList<>();
            for (Object value : arguments) {
                if (!(value instanceof JSObject object)) {
                    throw typeErr("Iterator.concat requires iterable objects");
                }
                Object iteratorMethod = Ops.getMethod(object, JSSymbol.iterator);
                if (iteratorMethod == Undefined.INSTANCE) {
                    throw typeErr(Ops.describe(object) + " is not iterable");
                }
                iterables.add(new ConcatIterable(object, iteratorMethod));
            }
            int[] index = {0};
            IteratorRecord dummy = new IteratorRecord(new JSObject(null), Undefined.INSTANCE, true);
            return new IteratorHelperObject(helperPrototype, dummy, self -> {
                Object result = NotFound.INSTANCE;
                while (true) {
                    IteratorRecord current = self.inner;
                    if (current == null) {
                        if (index[0] >= iterables.size()) break;
                        ConcatIterable entry = iterables.get(index[0]++);
                        Object iterator = Ops.call(entry.iteratorMethod(), entry.object(), EMPTY_ARGS);
                        if (!(iterator instanceof JSObject object)) {
                            throw typeErr("Result of the Symbol.iterator method is not an object");
                        }
                        self.inner = new IteratorRecord(object, object.get("next", object));
                        continue;
                    }
                    Object raw = Ops.call(current.nextMethod, current.iterator, EMPTY_ARGS);
                    if (!(raw instanceof JSObject stepResult)) {
                        throw typeErr("Iterator result " + Ops.toDisplayString(raw) + " is not an object");
                    }
                    if (Ops.toBoolean(stepResult.get("done", stepResult))) {
                        self.inner = null;
                        continue;
                    }
                    result = stepResult.get("value", stepResult);
                    break;
                }
                return result;
            });
        });

        method(iteratorPrototype, realm, "map", 1, (function, receiver, arguments, newTarget) -> {
            JSObject object = requireIterObj(receiver);
            Object mapper = arg(arguments, 0);
            if (!Ops.isCallable(mapper)) {
                throw closeAndThrow(object, Ops.describe(mapper) + " is not a function");
            }
            IteratorRecord record = getIteratorDirect(object);
            return new IteratorHelperObject(helperPrototype, record, helper -> {
                Object value = Iteration.stepValue(record);
                if (value == NotFound.INSTANCE) return NotFound.INSTANCE;
                try {
                    return ((JSObject) mapper).call(Undefined.INSTANCE,
                            new Object[] {value, helper.counter++});
                } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                    throw closeAndRethrow(record, error);
                }
            });
        });
        method(iteratorPrototype, realm, "filter", 1, (function, receiver, arguments, newTarget) -> {
            JSObject object = requireIterObj(receiver);
            Object predicate = arg(arguments, 0);
            if (!Ops.isCallable(predicate)) {
                throw closeAndThrow(object, Ops.describe(predicate) + " is not a function");
            }
            IteratorRecord record = getIteratorDirect(object);
            return new IteratorHelperObject(helperPrototype, record, helper -> {
                Object result = NotFound.INSTANCE;
                while (true) {
                    Object value = Iteration.stepValue(record);
                    if (value == NotFound.INSTANCE) break;
                    boolean selected;
                    try {
                        selected = Ops.toBoolean(((JSObject) predicate).call(Undefined.INSTANCE,
                                new Object[] {value, helper.counter++}));
                    } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                        throw closeAndRethrow(record, error);
                    }
                    if (selected) {
                        result = value;
                        break;
                    }
                }
                return result;
            });
        });
        method(iteratorPrototype, realm, "take", 1, (function, receiver, arguments, newTarget) -> {
            JSObject object = requireIterObj(receiver);
            double limit = numericLimit(object, arg(arguments, 0));
            IteratorRecord record = getIteratorDirect(object);
            double[] remaining = {limit};
            return new IteratorHelperObject(helperPrototype, record, helper -> {
                if (remaining[0] == 0.0) {
                    Iteration.closeNormal(record);
                    return NotFound.INSTANCE;
                }
                if (remaining[0] != Double.POSITIVE_INFINITY) remaining[0]--;
                return Iteration.stepValue(record);
            });
        });
        method(iteratorPrototype, realm, "drop", 1, (function, receiver, arguments, newTarget) -> {
            JSObject object = requireIterObj(receiver);
            double limit = numericLimit(object, arg(arguments, 0));
            IteratorRecord record = getIteratorDirect(object);
            double[] remaining = {limit};
            return new IteratorHelperObject(helperPrototype, record, helper -> {
                Object result = NotFound.INSTANCE;
                boolean done = false;
                while (remaining[0] > 0) {
                    if (remaining[0] != Double.POSITIVE_INFINITY) remaining[0]--;
                    Object value = Iteration.stepValue(record);
                    if (value == NotFound.INSTANCE) {
                        done = true;
                        break;
                    }
                }
                if (!done) result = Iteration.stepValue(record);
                return result;
            });
        });
        method(iteratorPrototype, realm, "flatMap", 1, (function, receiver, arguments, newTarget) -> {
            JSObject object = requireIterObj(receiver);
            Object mapper = arg(arguments, 0);
            if (!Ops.isCallable(mapper)) {
                throw closeAndThrow(object, Ops.describe(mapper) + " is not a function");
            }
            IteratorRecord record = getIteratorDirect(object);
            return new IteratorHelperObject(helperPrototype, record, helper -> {
                Object result = NotFound.INSTANCE;
                while (true) {
                    IteratorRecord inner = helper.inner;
                    if (inner != null) {
                        Object value;
                        try {
                            value = Iteration.stepValue(inner);
                        } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                            throw closeAndRethrow(record, error);
                        }
                        if (value == NotFound.INSTANCE) {
                            helper.inner = null;
                            continue;
                        }
                        result = value;
                        break;
                    }
                    Object value = Iteration.stepValue(record);
                    if (value == NotFound.INSTANCE) break;
                    try {
                        Object mapped = ((JSObject) mapper).call(Undefined.INSTANCE,
                                new Object[] {value, helper.counter++});
                        helper.inner = getIteratorFlattenable(mapped, false);
                    } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                        throw closeAndRethrow(record, error);
                    }
                }
                return result;
            });
        });
        method(iteratorPrototype, realm, "reduce", 1, (function, receiver, arguments, newTarget) -> {
            JSObject object = requireIterObj(receiver);
            Object reducer = arg(arguments, 0);
            if (!Ops.isCallable(reducer)) {
                throw closeAndThrow(object, Ops.describe(reducer) + " is not a function");
            }
            IteratorRecord record = getIteratorDirect(object);
            Object accumulator;
            double counter;
            if (arguments.length < 2) {
                Object first = Iteration.stepValue(record);
                if (first == NotFound.INSTANCE) {
                    throw typeErr("Reduce of empty iterator with no initial value");
                }
                accumulator = first;
                counter = 1.0;
            } else {
                accumulator = arguments[1];
                counter = 0.0;
            }
            while (true) {
                Object value = Iteration.stepValue(record);
                if (value == NotFound.INSTANCE) break;
                try {
                    accumulator = ((JSObject) reducer).call(Undefined.INSTANCE,
                            new Object[] {accumulator, value, counter});
                } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                    throw closeAndRethrow(record, error);
                }
                counter++;
            }
            return accumulator;
        });
        method(iteratorPrototype, realm, "toArray", 0, (function, receiver, arguments, newTarget) -> {
            JSObject object = requireIterObj(receiver);
            IteratorRecord record = getIteratorDirect(object);
            ArrayList<Object> items = new ArrayList<>();
            while (true) {
                Object value = Iteration.stepValue(record);
                if (value == NotFound.INSTANCE) break;
                items.add(value);
            }
            return Builtins.arrayOf(function.realm, items);
        });
        method(iteratorPrototype, realm, "forEach", 1, (function, receiver, arguments, newTarget) -> {
            JSObject object = requireIterObj(receiver);
            Object callback = arg(arguments, 0);
            if (!Ops.isCallable(callback)) {
                throw closeAndThrow(object, Ops.describe(callback) + " is not a function");
            }
            IteratorRecord record = getIteratorDirect(object);
            double counter = 0.0;
            while (true) {
                Object value = Iteration.stepValue(record);
                if (value == NotFound.INSTANCE) break;
                try {
                    ((JSObject) callback).call(Undefined.INSTANCE, new Object[] {value, counter++});
                } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                    throw closeAndRethrow(record, error);
                }
            }
            return Undefined.INSTANCE;
        });
        String[] iteratorMethods = {"some", "every", "find"};
        for (int kind = 0; kind < iteratorMethods.length; kind++) {
            int methodKind = kind;
            method(iteratorPrototype, realm, iteratorMethods[kind], 1,
                    (function, receiver, arguments, newTarget) -> {
                        JSObject object = requireIterObj(receiver);
                        Object predicate = arg(arguments, 0);
                        if (!Ops.isCallable(predicate)) {
                            throw closeAndThrow(object, Ops.describe(predicate) + " is not a function");
                        }
                        IteratorRecord record = getIteratorDirect(object);
                        double counter = 0.0;
                        Object result = switch (methodKind) {
                            case 0 -> Boolean.FALSE;
                            case 1 -> Boolean.TRUE;
                            default -> Undefined.INSTANCE;
                        };
                        while (true) {
                            Object value = Iteration.stepValue(record);
                            if (value == NotFound.INSTANCE) break;
                            boolean matched;
                            try {
                                matched = Ops.toBoolean(((JSObject) predicate).call(Undefined.INSTANCE,
                                        new Object[] {value, counter++}));
                            } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                                throw closeAndRethrow(record, error);
                            }
                            if (methodKind == 0 && matched) {
                                Iteration.closeNormal(record);
                                result = Boolean.TRUE;
                                break;
                            }
                            if (methodKind == 1 && !matched) {
                                Iteration.closeNormal(record);
                                result = Boolean.FALSE;
                                break;
                            }
                            if (methodKind == 2 && matched) {
                                Iteration.closeNormal(record);
                                result = value;
                                break;
                            }
                        }
                        return result;
                    });
        }
        method(iteratorPrototype, realm, "chunks", 1, (function, receiver, arguments, newTarget) -> {
            JSObject object = requireIterObj(receiver);
            long chunkSize = chunkingSize(object, arg(arguments, 0), "chunkSize");
            IteratorRecord record = getIteratorDirect(object);
            ArrayList<Object> buffer = new ArrayList<>();
            return new IteratorHelperObject(helperPrototype, record, helper -> {
                Object result = NotFound.INSTANCE;
                // once the final partial chunk was yielded the closure returns without stepping the iterator again
                while (!record.done) {
                    Object value = Iteration.stepValue(record);
                    if (value == NotFound.INSTANCE) {
                        if (!buffer.isEmpty()) result = Builtins.arrayOf(function.realm, buffer);
                        break;
                    }
                    buffer.add(value);
                    if (buffer.size() == chunkSize) {
                        result = Builtins.arrayOf(function.realm, new ArrayList<>(buffer));
                        buffer.clear();
                        break;
                    }
                }
                return result;
            });
        });
        method(iteratorPrototype, realm, "windows", 1, (function, receiver, arguments, newTarget) -> {
            JSObject object = requireIterObj(receiver);
            long windowSize = chunkingSize(object, arg(arguments, 0), "windowSize");
            Object undersized = arg(arguments, 1);
            boolean allowPartial;
            if (undersized == Undefined.INSTANCE) {
                allowPartial = false;
            } else if (undersized instanceof CharSequence text && text.toString().equals("only-full")) {
                allowPartial = false;
            } else if (undersized instanceof CharSequence text && text.toString().equals("allow-partial")) {
                allowPartial = true;
            } else {
                throw closeAndThrow(object, "undersized must be \"only-full\" or \"allow-partial\"");
            }
            IteratorRecord record = getIteratorDirect(object);
            ArrayDeque<Object> buffer = new ArrayDeque<>();
            return new IteratorHelperObject(helperPrototype, record, helper -> {
                Object result = NotFound.INSTANCE;
                while (!record.done) {
                    Object value = Iteration.stepValue(record);
                    if (value == NotFound.INSTANCE) {
                        if (allowPartial && !buffer.isEmpty() && buffer.size() < windowSize) {
                            result = Builtins.arrayOf(function.realm, new ArrayList<>(buffer));
                        }
                        break;
                    }
                    if (buffer.size() == windowSize) buffer.removeFirst();
                    buffer.addLast(value);
                    if (buffer.size() == windowSize) {
                        result = Builtins.arrayOf(function.realm, new ArrayList<>(buffer));
                        break;
                    }
                }
                return result;
            });
        });
        method(iteratorPrototype, realm, "includes", 1, (function, receiver, arguments, newTarget) -> {
            JSObject object = requireIterObj(receiver);
            Object searchElement = arg(arguments, 0);
            Object skippedElements = arg(arguments, 1);
            double toSkip;
            if (skippedElements == Undefined.INSTANCE) {
                toSkip = 0.0;
            } else if (skippedElements instanceof Double d
                    && (Double.isInfinite(d) || Ops.isIntegral(d))) {
                toSkip = d;
            } else {
                throw closeAndThrow(object, "skippedElements must be an integral number");
            }
            if (toSkip < 0.0 || (Double.isFinite(toSkip) && toSkip > MAX_SAFE_INTEGER)) {
                throw closeAndThrow(object,
                        JSException.rangeError("skippedElements must be a non-negative safe integer"));
            }
            IteratorRecord record = getIteratorDirect(object);
            double skipped = 0.0;
            boolean found = false;
            while (true) {
                Object value = Iteration.stepValue(record);
                if (value == NotFound.INSTANCE) break;
                if (skipped < toSkip) {
                    skipped++;
                } else if (Ops.sameValueZero(value, searchElement)) {
                    found = true;
                    Iteration.closeNormal(record);
                    break;
                }
            }
            return found;
        });
        method(iteratorPrototype, realm, "join", 1, (function, receiver, arguments, newTarget) -> {
            JSObject object = requireIterObj(receiver);
            Object separator = arg(arguments, 0);
            String separatorText;
            if (separator == Undefined.INSTANCE) {
                separatorText = ",";
            } else {
                try {
                    separatorText = Ops.toString(separator);
                } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                    throw closeAndRethrow(new IteratorRecord(object, Undefined.INSTANCE), error);
                }
            }
            IteratorRecord record = getIteratorDirect(object);
            StringBuilder builder = new StringBuilder();
            boolean first = true;
            while (true) {
                Object value = Iteration.stepValue(record);
                if (value == NotFound.INSTANCE) break;
                try {
                    if (!first) builder.append(separatorText);
                    first = false;
                    if (value != Undefined.INSTANCE && value != Null.INSTANCE) {
                        builder.append(Ops.toString(value));
                    }
                    if (builder.length() > 65536) {
                        function.realm.agent.checkStringLength(builder.length());
                    }
                } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                    throw closeAndRethrow(record, error);
                }
            }
            return builder.toString();
        });

        method(constructor, realm, "zip", 1, (function, receiver, arguments, newTarget) -> {
            if (!(arg(arguments, 0) instanceof JSObject iterables)) {
                throw typeErr("Iterator.zip requires an iterable object");
            }
            ZipOptions options = zipOptions(arg(arguments, 1));
            ArrayList<IteratorRecord> iterators = new ArrayList<>();
            IteratorRecord inputIterator = Iteration.getIterator(function.realm, iterables, false);
            while (true) {
                Object next;
                try {
                    next = Iteration.stepValue(inputIterator);
                } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                    throw closeAllAndThrow(iterators, error);
                }
                if (next == NotFound.INSTANCE) break;
                IteratorRecord iterator;
                try {
                    iterator = getIteratorFlattenable(next, false);
                } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                    ArrayList<IteratorRecord> all = new ArrayList<>();
                    all.add(inputIterator);
                    all.addAll(iterators);
                    throw closeAllAndThrow(all, error);
                }
                iterators.add(iterator);
            }
            ArrayList<Object> padding = new ArrayList<>(iterators.size());
            if (options.mode().equals(ZIP_LONGEST)) {
                if (options.padding() == Undefined.INSTANCE) {
                    for (int i = 0; i < iterators.size(); i++) padding.add(Undefined.INSTANCE);
                } else {
                    IteratorRecord paddingIterator;
                    try {
                        paddingIterator = Iteration.getIterator(function.realm, options.padding(), false);
                    } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                        throw closeAllAndThrow(iterators, error);
                    }
                    boolean[] usingIterator = {true};
                    for (int i = 0; i < iterators.size(); i++) {
                        if (usingIterator[0]) {
                            Object next;
                            try {
                                next = Iteration.stepValue(paddingIterator);
                            } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                                throw closeAllAndThrow(iterators, error);
                            }
                            if (next == NotFound.INSTANCE) {
                                usingIterator[0] = false;
                            } else {
                                padding.add(next);
                            }
                        }
                        if (!usingIterator[0]) padding.add(Undefined.INSTANCE);
                    }
                    if (usingIterator[0]) {
                        try {
                            Iteration.closeNormal(paddingIterator);
                        } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                            throw closeAllAndThrow(iterators, error);
                        }
                    }
                }
            }
            return iteratorZip(helperPrototype, iterators, options.mode(), padding,
                    results -> Builtins.arrayOf(function.realm, results));
        });
        method(constructor, realm, "zipKeyed", 1, (function, receiver, arguments, newTarget) -> {
            if (!(arg(arguments, 0) instanceof JSObject iterables)) {
                throw typeErr("Iterator.zipKeyed requires an object");
            }
            ZipOptions options = zipOptions(arg(arguments, 1));
            ArrayList<IteratorRecord> iterators = new ArrayList<>();
            ArrayList<Object> keys = new ArrayList<>();
            for (Object key : iterables.ownPropertyKeys()) {
                PropertyDescriptor descriptor;
                try {
                    descriptor = iterables.getOwnProperty(key);
                } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                    throw closeAllAndThrow(iterators, error);
                }
                if (descriptor == null || !descriptor.enumerable) continue;
                Object value;
                try {
                    value = iterables.get(key, iterables);
                } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                    throw closeAllAndThrow(iterators, error);
                }
                if (value == Undefined.INSTANCE) continue;
                IteratorRecord iterator;
                try {
                    iterator = getIteratorFlattenable(value, false);
                } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                    throw closeAllAndThrow(iterators, error);
                }
                keys.add(key);
                iterators.add(iterator);
            }
            ArrayList<Object> padding = new ArrayList<>(iterators.size());
            if (options.mode().equals(ZIP_LONGEST)) {
                if (options.padding() == Undefined.INSTANCE) {
                    for (int i = 0; i < iterators.size(); i++) padding.add(Undefined.INSTANCE);
                } else {
                    for (Object key : keys) {
                        Object value;
                        try {
                            value = ((JSObject) options.padding()).get(key, options.padding());
                        } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                            throw closeAllAndThrow(iterators, error);
                        }
                        padding.add(value);
                    }
                }
            }
            return iteratorZip(helperPrototype, iterators, options.mode(), padding, results -> {
                JSObject object = new JSObject(null);
                for (int i = 0; i < results.size(); i++) {
                    object.createDataPropertyOrThrow(keys.get(i), results.get(i));
                }
                return object;
            });
        });
    }

    /** GetOptionsObject + the "mode" / "padding" options of Iterator.zip and Iterator.zipKeyed. */
    private static ZipOptions zipOptions(Object options) {
        JSObject opts;
        if (options == Undefined.INSTANCE) {
            opts = new JSObject(null);
        } else if (options instanceof JSObject object) {
            opts = object;
        } else {
            throw typeErr("options must be an object");
        }
        Object rawMode = opts.get("mode", opts);
        String mode;
        if (rawMode == Undefined.INSTANCE) {
            mode = ZIP_SHORTEST;
        } else if (rawMode instanceof CharSequence text) {
            String value = text.toString();
            if (value.equals(ZIP_SHORTEST) || value.equals(ZIP_LONGEST) || value.equals(ZIP_STRICT)) {
                mode = value;
            } else {
                throw typeErr("mode must be \"shortest\", \"longest\", or \"strict\"");
            }
        } else {
            throw typeErr("mode must be \"shortest\", \"longest\", or \"strict\"");
        }
        Object padding = Undefined.INSTANCE;
        if (mode.equals(ZIP_LONGEST)) {
            padding = opts.get("padding", opts);
            if (padding != Undefined.INSTANCE && !(padding instanceof JSObject)) {
                throw typeErr("padding must be an object");
            }
        }
        return new ZipOptions(mode, padding);
    }

    /** IteratorZip(iters, mode, padding, finishResults) */
    private static IteratorHelperObject iteratorZip(JSObject helperPrototype,
                                                    List<IteratorRecord> iteratorList,
                                                    String mode, List<Object> padding,
                                                    Function<List<Object>, Object> finishResults) {
        ArrayList<IteratorRecord> iterators = new ArrayList<>(iteratorList);
        ArrayList<IteratorRecord> openIterators = new ArrayList<>(iteratorList);
        IteratorHelperObject helper = new IteratorHelperObject(helperPrototype,
                new IteratorRecord(new JSObject(null), Undefined.INSTANCE, true),
                self -> zipStep(iterators, openIterators, mode, padding, finishResults));
        helper.openIters = openIterators;
        return helper;
    }

    /** One resumption of the IteratorZip closure: the next results, or NotFound when it returns. */
    private static Object zipStep(ArrayList<IteratorRecord> iterators, List<IteratorRecord> openIterators,
                                  String mode, List<Object> padding,
                                  Function<List<Object>, Object> finishResults) {
        int iteratorCount = iterators.size();
        if (iteratorCount == 0) return NotFound.INSTANCE;
        ArrayList<Object> results = new ArrayList<>(iteratorCount);
        for (int i = 0; i < iteratorCount; i++) {
            IteratorRecord iterator = iterators.get(i);
            if (iterator == null) {
                results.add(padding.get(i));
                continue;
            }
            Object result;
            try {
                result = Iteration.stepValue(iterator);
            } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                openIterators.remove(iterator);
                throw closeAllAndThrow(openIterators, error);
            }
            if (result != NotFound.INSTANCE) {
                results.add(result);
                continue;
            }
            openIterators.remove(iterator);
            switch (mode) {
                case ZIP_SHORTEST -> {
                    closeAll(openIterators);
                    return NotFound.INSTANCE;
                }
                case ZIP_STRICT -> {
                    if (i != 0) {
                        throw closeAllAndThrow(openIterators,
                                JSException.typeError("Iterator.zip: iterators have different lengths in strict mode"));
                    }
                    for (int k = 1; k < iteratorCount; k++) {
                        IteratorRecord other = iterators.get(k);
                        boolean done;
                        try {
                            Object raw = Iteration.callNext(other, Undefined.INSTANCE);
                            if (!(raw instanceof JSObject stepResult)) {
                                other.done = true;
                                throw JSException.typeError("Iterator result " + Ops.toDisplayString(raw)
                                        + " is not an object");
                            }
                            done = Iteration.complete(other, stepResult);
                        } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                            openIterators.remove(other);
                            throw closeAllAndThrow(openIterators, error);
                        }
                        if (!done) {
                            throw closeAllAndThrow(openIterators,
                                    JSException.typeError("Iterator.zip: iterators have different lengths in strict mode"));
                        }
                        openIterators.remove(other);
                    }
                    return NotFound.INSTANCE;
                }
                default -> {
                    if (openIterators.isEmpty()) return NotFound.INSTANCE;
                    iterators.set(i, null);
                    results.add(padding.get(i));
                }
            }
        }
        return finishResults.apply(results);
    }

    private static Object helperNext(Realm realm, IteratorHelperObject helper) {
        if (helper.state == 2) throw typeErr("Generator is already running");
        if (helper.state == 3) return Iteration.createIterResult(realm, Undefined.INSTANCE, true);
        helper.state = 2;
        boolean completed = false;
        try {
            Object value = helper.step.apply(helper);
            if (value == NotFound.INSTANCE) {
                helper.state = 3;
                completed = true;
                return Iteration.createIterResult(realm, Undefined.INSTANCE, true);
            }
            helper.state = 1;
            completed = true;
            return Iteration.createIterResult(realm, value, false);
        } finally {
            if (!completed) helper.state = 3;
        }
    }

    private static JSObject requireIterObj(Object receiver) {
        if (receiver instanceof JSObject object) return object;
        throw typeErr("Iterator.prototype method called on non-object");
    }

    /** IteratorClose(iterated, ThrowCompletion(err)) for an iterator whose next method has not been read yet. */
    private static RuntimeException closeAndThrow(JSObject object, JSException error) {
        return closeAndRethrow(new IteratorRecord(object, Undefined.INSTANCE), error);
    }

    private static RuntimeException closeAndThrow(JSObject object, String message) {
        return closeAndThrow(object, JSException.typeError(message));
    }

    private static double numericLimit(JSObject object, Object value) {
        double number;
        try {
            number = Ops.toNumber(value);
        } catch (Exception | StackOverflowError | OutOfMemoryError error) {
            throw closeAndRethrow(new IteratorRecord(object, Undefined.INSTANCE), error);
        }
        if (Double.isNaN(number) || (Double.isFinite(number) && number > MAX_SAFE_INTEGER)) {
            throw closeAndThrow(object,
                    JSException.rangeError(Ops.toDisplayString(value) + " must be a non-negative safe integer"));
        }
        double limit = Ops.integerPart(number);
        if (limit < 0) {
            throw closeAndThrow(object,
                    JSException.rangeError(Ops.toDisplayString(value) + " must be positive"));
        }
        return limit;
    }

    /** Validates the size argument of chunks/windows: an integral Number in [1, 2^32 - 1], without coercion. */
    private static long chunkingSize(JSObject object, Object value, String what) {
        if (!(value instanceof Double number) || !Ops.isIntegral(number)) {
            throw closeAndThrow(object, what + " must be an integral number");
        }
        if (number < 1.0 || number > 4294967295.0) {
            throw closeAndThrow(object,
                    JSException.rangeError(what + " must be between 1 and 2^32 - 1"));
        }
        return (long) number.doubleValue();
    }

    /** Closes the underlying iterators of {@code helper} when it is resumed with a return completion. */
    private static void closeUnderlying(IteratorHelperObject helper) {
        List<IteratorRecord> open = helper.openIters;
        if (open != null) {
            closeAll(open);
            return;
        }
        IteratorRecord inner = helper.inner;
        if (inner != null) {
            try {
                Iteration.closeNormal(inner);
            } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                throw closeAndRethrow(helper.underlying, error);
            }
        }
        Iteration.closeNormal(helper.underlying);
    }

    /** IteratorCloseAll(iters, NormalCompletion): closes in reverse order; the first error from a return() wins. */
    static void closeAll(List<IteratorRecord> iterators) {
        Throwable pending = null;
        for (int i = iterators.size() - 1; i >= 0; i--) {
            IteratorRecord iterator = iterators.get(i);
            if (pending != null) {
                Iteration.closeOnThrow(iterator);
                continue;
            }
            try {
                Iteration.closeNormal(iterator);
            } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                if (error instanceof TerminationException termination) throw termination;
                pending = error;
            }
        }
        if (pending != null) throw rethrow(pending);
    }

    /** IteratorCloseAll(iters, ThrowCompletion(t)): errors from return() are ignored. */
    static RuntimeException closeAllAndThrow(List<IteratorRecord> iterators, Throwable error) {
        if (error instanceof TerminationException termination) throw termination;
        for (int i = iterators.size() - 1; i >= 0; i--) Iteration.closeOnThrow(iterators.get(i));
        return rethrow(error);
    }

    /** GetIteratorDirect */
    static IteratorRecord getIteratorDirect(JSObject object) {
        Object next = object.get("next", object);
        return new IteratorRecord(object, next);
    }

    /** GetIteratorFlattenable(obj, primitiveHandling) */
    static IteratorRecord getIteratorFlattenable(Object object, boolean iterateStrings) {
        if (!(object instanceof JSObject)) {
            if (!(iterateStrings && object instanceof CharSequence)) {
                throw typeErr(Ops.describe(object) + " is not an object");
            }
        }
        Object iteratorMethod = Ops.getMethod(object, JSSymbol.iterator);
        Object iterator = iteratorMethod == Undefined.INSTANCE
                ? object : Ops.call(iteratorMethod, object, EMPTY_ARGS);
        if (!(iterator instanceof JSObject iteratorObject)) {
            throw typeErr(Ops.describe(iterator) + " is not an object");
        }
        return getIteratorDirect(iteratorObject);
    }

    /** SetterThatIgnoresPrototypeProperties */
    static void setterThatIgnoresPrototypeProperties(Object thisValue, JSObject home, Object property,
                                                     Object value) {
        if (!(thisValue instanceof JSObject object)) throw typeErr("setter called on non-object");
        if (object == home) throw typeErr("Cannot assign to read only property");
        PropertyDescriptor descriptor = object.getOwnProperty(property);
        if (descriptor == null) {
            object.createDataPropertyOrThrow(property, value);
        } else {
            object.setOrThrow(property, value);
        }
    }

    private static RuntimeException closeAndRethrow(IteratorRecord record, Throwable error) {
        Iteration.<Object, RuntimeException>closeAndRethrow(record, error);
        throw new AssertionError("unreachable");
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable error) throws E {
        throw (E) error;
    }

    private static RuntimeException rethrow(Throwable error) {
        return IteratorBuiltins.<RuntimeException>sneakyThrow(error);
    }
}
