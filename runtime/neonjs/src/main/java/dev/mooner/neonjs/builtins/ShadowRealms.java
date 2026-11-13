/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.compiler.CodeBlock;
import dev.mooner.neonjs.compiler.Compiler;
import dev.mooner.neonjs.compiler.Source;
import dev.mooner.neonjs.parser.JSSyntaxError;
import dev.mooner.neonjs.parser.ParseOptions;
import dev.mooner.neonjs.parser.Parser;
import dev.mooner.neonjs.parser.Program;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.ErrorKind;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.TerminationException;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import dev.mooner.neonjs.vm.Evaluator;
import dev.mooner.neonjs.vm.Frame;
import dev.mooner.neonjs.vm.Interpreter;
import dev.mooner.neonjs.vm.JSPromise;
import dev.mooner.neonjs.vm.ModuleLoader;
import dev.mooner.neonjs.vm.Modules;
import dev.mooner.neonjs.vm.PromiseCapability;
import dev.mooner.neonjs.vm.Promises;
import dev.mooner.neonjs.vm.Rt;
import java.util.Collections;

/** ShadowRealm (proposal) intrinsics; derived from NeonJS and adapted to Java. */
public final class ShadowRealms {
    private ShadowRealms() {
    }

    /**
     * Converts any non-termination failure into a TypeError of {@code realm}. Termination and stack exhaustion are
     * rethrown unchanged, so the return type is always a runtime throwable.
     */
    public static RuntimeException typeError(Realm realm, Throwable error, String message) {
        if (error instanceof TerminationException termination) throw termination;
        if (error instanceof StackOverflowError overflow) throw overflow;
        return new JSException(realm.newError(ErrorKind.TYPE, message));
    }

    /** GetWrappedValue: wraps {@code value} for use in {@code targetRealm}. */
    public static Object wrap(Realm targetRealm, Object value) {
        return wrap(targetRealm, value, targetRealm);
    }

    /** GetWrappedValue: wraps {@code value} for use in {@code targetRealm}; errors are created in {@code errorRealm}. */
    public static Object wrap(Realm targetRealm, Object value, Realm errorRealm) {
        if (!(value instanceof JSObject object)) return value;
        if (!object.isCallable()) {
            throw new JSException(errorRealm.newError(ErrorKind.TYPE,
                    "Only primitives and callables can cross a ShadowRealm boundary"));
        }
        return wrappedFunctionCreate(targetRealm, object, errorRealm);
    }

    /** WrappedFunctionCreate (with CopyNameAndLength). */
    public static WrappedFunction wrappedFunctionCreate(Realm callerRealm, JSObject target) {
        return wrappedFunctionCreate(callerRealm, target, callerRealm);
    }

    /** WrappedFunctionCreate (with CopyNameAndLength). */
    public static WrappedFunction wrappedFunctionCreate(Realm callerRealm, JSObject target, Realm errorRealm) {
        WrappedFunction wrapped = new WrappedFunction(callerRealm, target);
        try {
            double length = 0.0;
            if (target.hasOwnProperty("length")) {
                Object targetLength = target.get("length", target);
                if (targetLength instanceof Double value) {
                    if (value == Double.POSITIVE_INFINITY) length = value;
                    else if (value == Double.NEGATIVE_INFINITY) length = 0.0;
                    else length = Math.max(Ops.toIntegerOrInfinity(value), 0.0);
                }
            }
            wrapped.defineOwn("length", length, Attr.CONFIGURABLE);
            Object name = target.get("name", target);
            wrapped.defineOwn("name", name instanceof CharSequence ? name.toString() : "",
                    Attr.CONFIGURABLE);
        } catch (Exception error) {
            throw typeError(errorRealm, error, "Cannot copy name and length of the wrapped function");
        }
        return wrapped;
    }

    public static void install(Realm realm) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        NativeFunction ctor = BuiltinsKt.makeCtor(realm, "ShadowRealm", 0, proto,
                (function, thisArg, args, newTarget) -> {
                    if (newTarget == null) throw BuiltinsKt.typeErr("Constructor ShadowRealm requires 'new'");
                    JSObject prototype = Ops.getPrototypeFromConstructor(newTarget,
                            r -> r.intrinsic("%ShadowRealm.prototype%"));
                    return new JSShadowRealm(prototype, createShadowRealm(function.realm));
                });
        realm.getIntrinsics().put("%ShadowRealm.prototype%", proto);
        proto.defineOwn(JSSymbol.toStringTag, "ShadowRealm", Attr.CONFIGURABLE);
        BuiltinsKt.method(proto, realm, "evaluate", 1, (function, thisArg, args, newTarget) -> {
            if (!(thisArg instanceof JSShadowRealm shadowRealm)) {
                throw BuiltinsKt.typeErr("ShadowRealm.prototype.evaluate called on incompatible receiver");
            }
            if (!(Values.arg(args, 0) instanceof CharSequence source)) {
                throw BuiltinsKt.typeErr("ShadowRealm.prototype.evaluate requires a string");
            }
            return performEval(source.toString(), function.realm, shadowRealm.shadowRealm);
        });
        BuiltinsKt.method(proto, realm, "importValue", 2, (function, thisArg, args, newTarget) -> {
            if (!(thisArg instanceof JSShadowRealm shadowRealm)) {
                throw BuiltinsKt.typeErr("ShadowRealm.prototype.importValue called on incompatible receiver");
            }
            String specifier = Ops.toString(Values.arg(args, 0));
            if (!(Values.arg(args, 1) instanceof CharSequence exportName)) {
                throw BuiltinsKt.typeErr(
                        "ShadowRealm.prototype.importValue requires a string export name");
            }
            return importValue(specifier, exportName.toString(), function.realm, shadowRealm.shadowRealm);
        });
        BuiltinsKt.global(realm, "ShadowRealm", ctor);
    }

    /** CreateRealm + SetDefaultGlobalBindings + HostInitializeShadowRealm (shares the caller's module loader). */
    private static Realm createShadowRealm(Realm caller) {
        Realm realm = new Realm(caller.agent);
        Builtins.install(realm);
        Object loader = caller.getIntrinsicsAny().get("%ModuleLoader%");
        if (loader instanceof ModuleLoader moduleLoader) Modules.setLoader(realm, moduleLoader);
        return realm;
    }

    /** PerformShadowRealmEval */
    private static Object performEval(String source, Realm callerRealm, Realm evalRealm) {
        if (!callerRealm.agent.config.allowCodeGeneration) {
            throw new JSException(callerRealm.newError(ErrorKind.EVAL,
                    "Code generation from strings is disallowed in this context"));
        }
        // like an indirect eval: lexical declarations go into a fresh environment per evaluation
        Program program;
        try {
            program = Parser.parse(source, new ParseOptions(false, false, false, false, false, false, true,
                    false, Collections.emptySet(), "<ShadowRealm>", false, true));
        } catch (JSSyntaxError error) {
            // early errors are reported as a SyntaxError of the caller's realm
            throw new JSException(callerRealm.newError(ErrorKind.SYNTAX, error.getRawMessage()));
        }
        CodeBlock code = Compiler.compileEval(program, new Source("<ShadowRealm>", source), false);
        Object result;
        try {
            var environment = evalRealm.getGlobalEnv();
            result = Interpreter.execute(new Frame(null, code, evalRealm, environment.thisValue,
                    Values.EMPTY_ARGS, Undefined.INSTANCE, environment));
        } catch (Exception error) {
            throw typeError(callerRealm, error, "ShadowRealm evaluation threw an exception");
        }
        return wrap(callerRealm, result);
    }

    /** ShadowRealmImportValue */
    private static Object importValue(String specifier, String exportName, Realm callerRealm,
                                      Realm evalRealm) {
        PromiseCapability capability =
                Promises.newPromiseCapability(callerRealm, callerRealm.getPromiseConstructor());
        Frame frame = new Frame(null, Evaluator.compileScript(evalRealm, "", "<ShadowRealm>"), evalRealm,
                Undefined.INSTANCE, Values.EMPTY_ARGS, Undefined.INSTANCE, evalRealm.getGlobalEnv());
        JSPromise inner = (JSPromise) evalRealm.enter(
                () -> Modules.dynamicImport(frame, specifier, Undefined.INSTANCE));
        Promises.thenHost(callerRealm, inner, namespace -> {
            try {
                JSObject object = (JSObject) namespace;
                Object key = PK.fromString(exportName);
                if (!object.hasOwnProperty(key)) {
                    throw new JSException(callerRealm.newError(ErrorKind.TYPE,
                            "Module '" + specifier + "' has no export named '" + exportName + "'"));
                }
                Ops.call(capability.resolve, Undefined.INSTANCE,
                        new Object[] {wrap(callerRealm, object.get(key, object))});
            } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                if (error instanceof TerminationException termination) throw termination;
                Object reason = error instanceof JSException jsError
                        ? jsError.value : Rt.catchValue(error, callerRealm, null, 0);
                Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {reason});
            }
            return Undefined.INSTANCE;
        }, reason -> {
            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {callerRealm.newError(
                    ErrorKind.TYPE, "Cannot import '" + specifier + "' into the ShadowRealm")});
            return Undefined.INSTANCE;
        });
        return capability.promise;
    }
}
