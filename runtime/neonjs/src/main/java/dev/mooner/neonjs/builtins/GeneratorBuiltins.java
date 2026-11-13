/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.vm.AsyncFromSyncIterator;
import dev.mooner.neonjs.vm.Evaluator;
import dev.mooner.neonjs.vm.Frame;
import dev.mooner.neonjs.vm.Generators;
import dev.mooner.neonjs.vm.JSGenerator;
import static dev.mooner.neonjs.builtins.BuiltinsKt.makeCtor;
import static dev.mooner.neonjs.builtins.BuiltinsKt.method;
import static dev.mooner.neonjs.builtins.BuiltinsKt.typeErr;
import static dev.mooner.neonjs.builtins.BuiltinsKt.value;
import static dev.mooner.neonjs.runtime.Values.arg;

final class GeneratorBuiltins {
    private GeneratorBuiltins() {
    }

    static void install(Realm realm) {
        // %GeneratorFunction%, %GeneratorFunction.prototype%, %GeneratorPrototype%
        JSObject generatorFunctionPrototype = new JSObject(realm.getFunctionPrototype());
        realm.setGeneratorFunctionPrototype(generatorFunctionPrototype);
        JSObject generatorPrototype = new JSObject(realm.getIteratorPrototype());
        realm.setGeneratorPrototype(generatorPrototype);
        var generatorFunction = makeCtor(realm, "GeneratorFunction", 1, null,
                realm.getFunctionConstructor(), (function, receiver, arguments, newTarget) ->
                        Evaluator.createDynamicFunction(function.realm,
                                newTarget == null ? function : newTarget, "generator", arguments));
        generatorFunction.defineOwn("prototype", generatorFunctionPrototype, Attr.NONE);
        generatorFunctionPrototype.defineOwn("constructor", generatorFunction, Attr.CONFIGURABLE);
        generatorFunctionPrototype.defineOwn("prototype", generatorPrototype, Attr.CONFIGURABLE);
        value(generatorFunctionPrototype, JSSymbol.toStringTag, "GeneratorFunction", Attr.CONFIGURABLE);
        generatorPrototype.defineOwn("constructor", generatorFunctionPrototype, Attr.CONFIGURABLE);
        value(generatorPrototype, JSSymbol.toStringTag, "Generator", Attr.CONFIGURABLE);
        realm.getIntrinsics().put("%GeneratorFunction%", generatorFunction);
        method(generatorPrototype, realm, "next", 1, (function, receiver, arguments, newTarget) ->
                generator(receiver).resume(function.realm, arg(arguments, 0), Frame.MODE_NEXT));
        method(generatorPrototype, realm, "return", 1, (function, receiver, arguments, newTarget) ->
                generator(receiver).resume(function.realm, arg(arguments, 0), Frame.MODE_RETURN));
        method(generatorPrototype, realm, "throw", 1, (function, receiver, arguments, newTarget) ->
                generator(receiver).resume(function.realm, arg(arguments, 0), Frame.MODE_THROW));

        // %AsyncFunction%
        JSObject asyncFunctionPrototype = new JSObject(realm.getFunctionPrototype());
        realm.setAsyncFunctionPrototype(asyncFunctionPrototype);
        var asyncFunction = makeCtor(realm, "AsyncFunction", 1, null,
                realm.getFunctionConstructor(), (function, receiver, arguments, newTarget) ->
                        Evaluator.createDynamicFunction(function.realm,
                                newTarget == null ? function : newTarget, "async", arguments));
        asyncFunction.defineOwn("prototype", asyncFunctionPrototype, Attr.NONE);
        asyncFunctionPrototype.defineOwn("constructor", asyncFunction, Attr.CONFIGURABLE);
        value(asyncFunctionPrototype, JSSymbol.toStringTag, "AsyncFunction", Attr.CONFIGURABLE);
        realm.getIntrinsics().put("%AsyncFunction%", asyncFunction);

        // %AsyncGeneratorFunction%
        JSObject asyncGeneratorFunctionPrototype = new JSObject(realm.getFunctionPrototype());
        realm.setAsyncGeneratorFunctionPrototype(asyncGeneratorFunctionPrototype);
        JSObject asyncGeneratorPrototype = new JSObject(realm.getAsyncIteratorPrototype());
        realm.setAsyncGeneratorPrototype(asyncGeneratorPrototype);
        var asyncGeneratorFunction = makeCtor(realm, "AsyncGeneratorFunction", 1, null,
                realm.getFunctionConstructor(), (function, receiver, arguments, newTarget) ->
                        Evaluator.createDynamicFunction(function.realm,
                                newTarget == null ? function : newTarget, "asyncGenerator", arguments));
        asyncGeneratorFunction.defineOwn("prototype", asyncGeneratorFunctionPrototype, Attr.NONE);
        asyncGeneratorFunctionPrototype.defineOwn("constructor", asyncGeneratorFunction,
                Attr.CONFIGURABLE);
        asyncGeneratorFunctionPrototype.defineOwn("prototype", asyncGeneratorPrototype,
                Attr.CONFIGURABLE);
        value(asyncGeneratorFunctionPrototype, JSSymbol.toStringTag, "AsyncGeneratorFunction",
                Attr.CONFIGURABLE);
        asyncGeneratorPrototype.defineOwn("constructor", asyncGeneratorFunctionPrototype,
                Attr.CONFIGURABLE);
        value(asyncGeneratorPrototype, JSSymbol.toStringTag, "AsyncGenerator", Attr.CONFIGURABLE);
        realm.getIntrinsics().put("%AsyncGeneratorFunction%", asyncGeneratorFunction);
        method(asyncGeneratorPrototype, realm, "next", 1, (function, receiver, arguments, newTarget) ->
                Generators.asyncGenEnqueue(function.realm, receiver, Frame.MODE_NEXT, arg(arguments, 0)));
        method(asyncGeneratorPrototype, realm, "return", 1, (function, receiver, arguments, newTarget) ->
                Generators.asyncGenEnqueue(function.realm, receiver, Frame.MODE_RETURN, arg(arguments, 0)));
        method(asyncGeneratorPrototype, realm, "throw", 1, (function, receiver, arguments, newTarget) ->
                Generators.asyncGenEnqueue(function.realm, receiver, Frame.MODE_THROW, arg(arguments, 0)));

        // %AsyncFromSyncIteratorPrototype%
        JSObject asyncFromSyncIteratorPrototype = new JSObject(realm.getAsyncIteratorPrototype());
        realm.setAsyncFromSyncIteratorPrototype(asyncFromSyncIteratorPrototype);
        method(asyncFromSyncIteratorPrototype, realm, "next", 1,
                (function, receiver, arguments, newTarget) ->
                        AsyncFromSyncIterator.method(function.realm, receiver, arguments, 0));
        method(asyncFromSyncIteratorPrototype, realm, "return", 1,
                (function, receiver, arguments, newTarget) ->
                        AsyncFromSyncIterator.method(function.realm, receiver, arguments, 1));
        method(asyncFromSyncIteratorPrototype, realm, "throw", 1,
                (function, receiver, arguments, newTarget) ->
                        AsyncFromSyncIterator.method(function.realm, receiver, arguments, 2));
    }

    private static JSGenerator generator(Object value) {
        if (value instanceof JSGenerator generator) return generator;
        throw typeErr("next method called on incompatible receiver " + Ops.describe(value));
    }
}
