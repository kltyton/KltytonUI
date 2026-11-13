package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.compiler.CodeBlock;
import dev.mooner.neonjs.compiler.Op;
import dev.mooner.neonjs.runtime.ErrorKind;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.TerminationException;
import dev.mooner.neonjs.runtime.Undefined;

/** Generator and async execution state machines; derived from NeonJS and adapted to Java. */
public final class Generators {
    private Generators() {
    }

    public static Object start(Frame frame) {
        CodeBlock code = frame.code;
        Realm realm = frame.realm;
        if (code.isAsync() && !code.isGenerator()) return asyncFunctionStart(frame);
        Object result = Interpreter.execute(frame);
        if (result != Interpreter.SUSPENDED) throw new IllegalStateException("generator prologue did not suspend");
        JSClosure function = frame.fn;
        if (code.isAsync()) {
            JSAsyncGenerator generator = new JSAsyncGenerator(
                    prototypeOf(function, realm.getAsyncGeneratorPrototype()), frame);
            frame.setGenerator(generator);
            return generator;
        }
        JSGenerator generator = new JSGenerator(prototypeOf(function, realm.getGeneratorPrototype()), frame);
        frame.setGenerator(generator);
        return generator;
    }

    private static JSObject prototypeOf(JSObject function, JSObject fallback) {
        Object prototype = function.get("prototype", function);
        return prototype instanceof JSObject object ? object : fallback;
    }

    private static JSPromise asyncFunctionStart(Frame frame) {
        Realm realm = frame.realm;
        JSPromise promise = Promises.newPromise(realm);
        asyncStep(frame, realm, promise);
        return promise;
    }

    private static void asyncStep(Frame frame, Realm realm, JSPromise promise) {
        Object result;
        try {
            result = Interpreter.execute(frame);
        } catch (JSException error) {
            Promises.rejectPromise(realm, promise, error.value);
            return;
        } catch (TerminationException error) {
            throw error;
        } catch (Exception | StackOverflowError | OutOfMemoryError error) {
            Promises.rejectPromise(realm, promise, Rt.catchValue(error, realm, null, 0));
            return;
        }
        if (result == Interpreter.SUSPENDED) {
            awaitThen(frame, realm, () -> asyncStep(frame, realm, promise));
            return;
        }
        Promises.resolvePromise(realm, promise, result);
    }

    public static void runAsyncBody(Frame frame, Realm realm, PromiseCapability capability) {
        Object result;
        try {
            result = Interpreter.execute(frame);
        } catch (JSException error) {
            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error.value});
            return;
        } catch (TerminationException error) {
            throw error;
        } catch (Exception | StackOverflowError | OutOfMemoryError error) {
            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {Rt.catchValue(error, realm, null, 0)});
            return;
        }
        if (result == Interpreter.SUSPENDED) {
            awaitThen(frame, realm, () -> runAsyncBody(frame, realm, capability));
            return;
        }
        Ops.call(capability.resolve, Undefined.INSTANCE, new Object[] {Undefined.INSTANCE});
    }

    public static void awaitThen(Frame frame, Realm realm, Runnable continuation) {
        JSObject value = (JSObject) frame.getSuspendValue();
        frame.setSuspendValue(null);
        JSPromise promise = value instanceof JSPromise existing ? existing : Promises.resolved(realm, value);
        Promises.thenHost(realm, promise, result -> {
            prepareAwaitResume(frame, result, false);
            continuation.run();
            return Undefined.INSTANCE;
        }, error -> {
            prepareAwaitResume(frame, error, true);
            continuation.run();
            return Undefined.INSTANCE;
        });
    }

    public static void prepareAwaitResume(Frame frame, Object value, boolean throwing) {
        int[] code = frame.code.code;
        int opcode = code[frame.getAwaitPc()];
        if (opcode == Op.AWAIT_IGNORE) {
            frame.slots[frame.sp++] = throwing ? Undefined.INSTANCE : value;
            frame.setResumeMode(Frame.MODE_NEXT);
        } else if (opcode == Op.AWAIT_CATCH) {
            if (throwing) frame.slots[code[frame.getAwaitPc() + 1]] = Ops.num(Frame.MODE_THROW);
            frame.slots[frame.sp++] = value;
            frame.setResumeMode(Frame.MODE_NEXT);
        } else {
            frame.slots[frame.sp++] = value;
            frame.setResumeMode(throwing ? Frame.MODE_THROW : Frame.MODE_NEXT);
        }
        frame.setSuspendKind(Frame.SUSPEND_AWAIT);
    }

    public static Object asyncGenEnqueue(Realm realm, Object value, int mode, Object received) {
        PromiseCapability capability = Promises.newPromiseCapability(realm, realm.getPromiseConstructor());
        if (!(value instanceof JSAsyncGenerator generator)) {
            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {realm.newError(ErrorKind.TYPE, "not an AsyncGenerator")});
            return capability.promise;
        }
        int state = generator.state;
        if (mode == Frame.MODE_NEXT) {
            if (state == JSAsyncGenerator.COMPLETED) {
                Ops.call(capability.resolve, Undefined.INSTANCE,
                        new Object[] {Iteration.createIterResult(realm, Undefined.INSTANCE, true)});
                return capability.promise;
            }
            generator.queue.addLast(new AsyncGenRequest(mode, received, capability));
            if (state == JSAsyncGenerator.SUSPENDED_START || state == JSAsyncGenerator.SUSPENDED_YIELD) {
                asyncGenResume(realm, generator, mode, received);
            }
        } else if (mode == Frame.MODE_RETURN) {
            generator.queue.addLast(new AsyncGenRequest(mode, received, capability));
            if (state == JSAsyncGenerator.SUSPENDED_START || state == JSAsyncGenerator.COMPLETED) {
                generator.state = JSAsyncGenerator.AWAITING_RETURN;
                asyncGenAwaitReturn(realm, generator);
            } else if (state == JSAsyncGenerator.SUSPENDED_YIELD) {
                asyncGenResume(realm, generator, mode, received);
            }
        } else {
            if (state == JSAsyncGenerator.SUSPENDED_START) {
                generator.state = JSAsyncGenerator.COMPLETED;
                generator.frame = null;
                state = JSAsyncGenerator.COMPLETED;
            }
            if (state == JSAsyncGenerator.COMPLETED) {
                Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {received});
                return capability.promise;
            }
            generator.queue.addLast(new AsyncGenRequest(mode, received, capability));
            if (state == JSAsyncGenerator.SUSPENDED_YIELD) asyncGenResume(realm, generator, mode, received);
        }
        return capability.promise;
    }

    private static void asyncGenResume(Realm realm, JSAsyncGenerator generator, int mode, Object value) {
        Frame frame = generator.frame;
        if (generator.state == JSAsyncGenerator.SUSPENDED_YIELD) {
            frame.slots[frame.sp++] = value;
            frame.setResumeMode(mode);
        }
        generator.state = JSAsyncGenerator.EXECUTING;
        asyncGenStep(realm, generator, frame);
    }

    private static void asyncGenStep(Realm realm, JSAsyncGenerator generator, Frame frame) {
        while (true) {
            Object result;
            try {
                result = Interpreter.execute(frame);
            } catch (TerminationException error) {
                throw error;
            } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                Object thrown = error instanceof JSException guest ? guest.value : Rt.catchValue(error, realm, null, 0);
                generator.state = JSAsyncGenerator.COMPLETED;
                generator.frame = null;
                completeStep(realm, generator, true, thrown, true);
                drainQueue(realm, generator);
                return;
            }
            if (result == Interpreter.SUSPENDED) {
                if (frame.getSuspendKind() == Frame.SUSPEND_AWAIT) {
                    awaitThen(frame, realm, () -> asyncGenStep(realm, generator, frame));
                    return;
                }
                Object yielded = frame.getSuspendValue();
                frame.setSuspendValue(null);
                generator.state = JSAsyncGenerator.SUSPENDED_YIELD;
                completeStep(realm, generator, false, yielded, false);
                AsyncGenRequest next = generator.queue.peekFirst();
                if (next == null) return;
                frame.slots[frame.sp++] = next.value;
                frame.setResumeMode(next.mode);
                generator.state = JSAsyncGenerator.EXECUTING;
                continue;
            }
            generator.state = JSAsyncGenerator.COMPLETED;
            generator.frame = null;
            completeStep(realm, generator, false, result, true);
            drainQueue(realm, generator);
            return;
        }
    }

    private static void completeStep(Realm realm, JSAsyncGenerator generator, boolean throwing,
                                     Object value, boolean done) {
        AsyncGenRequest next = generator.queue.pollFirst();
        if (next == null) return;
        if (throwing) Ops.call(next.capability.reject, Undefined.INSTANCE, new Object[] {value});
        else Ops.call(next.capability.resolve, Undefined.INSTANCE, new Object[] {Iteration.createIterResult(realm, value, done)});
    }

    private static void drainQueue(Realm realm, JSAsyncGenerator generator) {
        while (true) {
            AsyncGenRequest next = generator.queue.peekFirst();
            if (next == null) return;
            if (next.mode == Frame.MODE_RETURN) {
                generator.state = JSAsyncGenerator.AWAITING_RETURN;
                asyncGenAwaitReturn(realm, generator);
                return;
            }
            if (next.mode == Frame.MODE_THROW) completeStep(realm, generator, true, next.value, true);
            else completeStep(realm, generator, false, Undefined.INSTANCE, true);
        }
    }

    private static void asyncGenAwaitReturn(Realm realm, JSAsyncGenerator generator) {
        AsyncGenRequest next = generator.queue.getFirst();
        JSObject promise;
        try {
            promise = Promises.promiseResolve(realm, realm.getPromiseConstructor(), next.value);
        } catch (JSException error) {
            generator.state = JSAsyncGenerator.COMPLETED;
            completeStep(realm, generator, true, error.value, true);
            drainQueue(realm, generator);
            return;
        }
        Promises.thenHost(realm, (JSPromise) promise, value -> {
            generator.state = JSAsyncGenerator.COMPLETED;
            completeStep(realm, generator, false, value, true);
            drainQueue(realm, generator);
            return Undefined.INSTANCE;
        }, error -> {
            generator.state = JSAsyncGenerator.COMPLETED;
            completeStep(realm, generator, true, error, true);
            drainQueue(realm, generator);
            return Undefined.INSTANCE;
        });
    }
}
