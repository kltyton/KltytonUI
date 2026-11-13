package dev.mooner.neonjs.runtime;

import dev.mooner.neonjs.compiler.TemplateSite;
import java.util.HashMap;
import java.util.WeakHashMap;
import java.util.Objects;
import java.util.function.Supplier;

/** Realm intrinsics and global state; derived from NeonJS and adapted to Java. */
public final class Realm {
    public final Agent agent;
    public JSObject objectPrototype;
    public JSObject functionPrototype;
    public JSObject arrayPrototype;
    public JSObject stringPrototype;
    public JSObject numberPrototype;
    public JSObject booleanPrototype;
    public JSObject symbolPrototype;
    public JSObject bigintPrototype;
    public JSObject errorPrototype;
    public JSObject iteratorPrototype;
    public JSObject asyncIteratorPrototype;
    public JSObject arrayIteratorPrototype;
    public JSObject arrayIteratorNext;
    public JSObject arrayProtoValues;
    public JSObject generatorFunctionPrototype;
    public JSObject generatorPrototype;
    public JSObject asyncFunctionPrototype;
    public JSObject asyncGeneratorFunctionPrototype;
    public JSObject asyncGeneratorPrototype;
    public JSObject asyncFromSyncIteratorPrototype;
    public JSObject promisePrototype;
    public JSObject promiseConstructor;
    public JSObject objectConstructor;
    public JSObject functionConstructor;
    public JSObject arrayConstructor;
    public JSObject throwTypeError;
    public JSObject evalFunction;
    public JSObject globalObject;
    public GlobalEnv globalEnv;
    public final HashMap<ErrorKind, JSObject> errorPrototypes = new HashMap<>();
    public final HashMap<ErrorKind, JSObject> errorConstructors = new HashMap<>();
    public final HashMap<String, JSObject> intrinsics = new HashMap<>();
    public final HashMap<String, Object> intrinsicsAny = new HashMap<>();
    public final WeakHashMap<TemplateSite, JSArray> templateMap = new WeakHashMap<>();
    public Object hostData;

    public Realm(Agent agent) {
        this.agent = agent;
    }

    public JSObject getObjectPrototype() {
        JSObject value = objectPrototype;
        if (value == null) throw new IllegalStateException("property objectPrototype has not been initialized");
        return value;
    }

    public void setObjectPrototype(JSObject value) {
        objectPrototype = Objects.requireNonNull(value, "objectPrototype");
    }

    public JSObject getFunctionPrototype() {
        JSObject value = functionPrototype;
        if (value == null) throw new IllegalStateException("property functionPrototype has not been initialized");
        return value;
    }

    public void setFunctionPrototype(JSObject value) {
        functionPrototype = Objects.requireNonNull(value, "functionPrototype");
    }

    public JSObject getArrayPrototype() {
        JSObject value = arrayPrototype;
        if (value == null) throw new IllegalStateException("property arrayPrototype has not been initialized");
        return value;
    }

    public void setArrayPrototype(JSObject value) {
        arrayPrototype = Objects.requireNonNull(value, "arrayPrototype");
    }

    public JSObject getStringPrototype() {
        JSObject value = stringPrototype;
        if (value == null) throw new IllegalStateException("property stringPrototype has not been initialized");
        return value;
    }

    public void setStringPrototype(JSObject value) {
        stringPrototype = Objects.requireNonNull(value, "stringPrototype");
    }

    public JSObject getNumberPrototype() {
        JSObject value = numberPrototype;
        if (value == null) throw new IllegalStateException("property numberPrototype has not been initialized");
        return value;
    }

    public void setNumberPrototype(JSObject value) {
        numberPrototype = Objects.requireNonNull(value, "numberPrototype");
    }

    public JSObject getBooleanPrototype() {
        JSObject value = booleanPrototype;
        if (value == null) throw new IllegalStateException("property booleanPrototype has not been initialized");
        return value;
    }

    public void setBooleanPrototype(JSObject value) {
        booleanPrototype = Objects.requireNonNull(value, "booleanPrototype");
    }

    public JSObject getSymbolPrototype() {
        JSObject value = symbolPrototype;
        if (value == null) throw new IllegalStateException("property symbolPrototype has not been initialized");
        return value;
    }

    public void setSymbolPrototype(JSObject value) {
        symbolPrototype = Objects.requireNonNull(value, "symbolPrototype");
    }

    public JSObject getBigintPrototype() {
        JSObject value = bigintPrototype;
        if (value == null) throw new IllegalStateException("property bigintPrototype has not been initialized");
        return value;
    }

    public void setBigintPrototype(JSObject value) {
        bigintPrototype = Objects.requireNonNull(value, "bigintPrototype");
    }

    public JSObject getErrorPrototype() {
        JSObject value = errorPrototype;
        if (value == null) throw new IllegalStateException("property errorPrototype has not been initialized");
        return value;
    }

    public void setErrorPrototype(JSObject value) {
        errorPrototype = Objects.requireNonNull(value, "errorPrototype");
    }

    public JSObject getIteratorPrototype() {
        JSObject value = iteratorPrototype;
        if (value == null) throw new IllegalStateException("property iteratorPrototype has not been initialized");
        return value;
    }

    public void setIteratorPrototype(JSObject value) {
        iteratorPrototype = Objects.requireNonNull(value, "iteratorPrototype");
    }

    public JSObject getAsyncIteratorPrototype() {
        JSObject value = asyncIteratorPrototype;
        if (value == null) throw new IllegalStateException("property asyncIteratorPrototype has not been initialized");
        return value;
    }

    public void setAsyncIteratorPrototype(JSObject value) {
        asyncIteratorPrototype = Objects.requireNonNull(value, "asyncIteratorPrototype");
    }

    public JSObject getArrayIteratorPrototype() {
        JSObject value = arrayIteratorPrototype;
        if (value == null) throw new IllegalStateException("property arrayIteratorPrototype has not been initialized");
        return value;
    }

    public void setArrayIteratorPrototype(JSObject value) {
        arrayIteratorPrototype = Objects.requireNonNull(value, "arrayIteratorPrototype");
    }

    public JSObject getArrayIteratorNext() {
        JSObject value = arrayIteratorNext;
        if (value == null) throw new IllegalStateException("property arrayIteratorNext has not been initialized");
        return value;
    }

    public void setArrayIteratorNext(JSObject value) {
        arrayIteratorNext = Objects.requireNonNull(value, "arrayIteratorNext");
    }

    public JSObject getArrayProtoValues() {
        JSObject value = arrayProtoValues;
        if (value == null) throw new IllegalStateException("property arrayProtoValues has not been initialized");
        return value;
    }

    public void setArrayProtoValues(JSObject value) {
        arrayProtoValues = Objects.requireNonNull(value, "arrayProtoValues");
    }

    public JSObject getGeneratorFunctionPrototype() {
        JSObject value = generatorFunctionPrototype;
        if (value == null) throw new IllegalStateException("property generatorFunctionPrototype has not been initialized");
        return value;
    }

    public void setGeneratorFunctionPrototype(JSObject value) {
        generatorFunctionPrototype = Objects.requireNonNull(value, "generatorFunctionPrototype");
    }

    public JSObject getGeneratorPrototype() {
        JSObject value = generatorPrototype;
        if (value == null) throw new IllegalStateException("property generatorPrototype has not been initialized");
        return value;
    }

    public void setGeneratorPrototype(JSObject value) {
        generatorPrototype = Objects.requireNonNull(value, "generatorPrototype");
    }

    public JSObject getAsyncFunctionPrototype() {
        JSObject value = asyncFunctionPrototype;
        if (value == null) throw new IllegalStateException("property asyncFunctionPrototype has not been initialized");
        return value;
    }

    public void setAsyncFunctionPrototype(JSObject value) {
        asyncFunctionPrototype = Objects.requireNonNull(value, "asyncFunctionPrototype");
    }

    public JSObject getAsyncGeneratorFunctionPrototype() {
        JSObject value = asyncGeneratorFunctionPrototype;
        if (value == null) throw new IllegalStateException("property asyncGeneratorFunctionPrototype has not been initialized");
        return value;
    }

    public void setAsyncGeneratorFunctionPrototype(JSObject value) {
        asyncGeneratorFunctionPrototype = Objects.requireNonNull(value, "asyncGeneratorFunctionPrototype");
    }

    public JSObject getAsyncGeneratorPrototype() {
        JSObject value = asyncGeneratorPrototype;
        if (value == null) throw new IllegalStateException("property asyncGeneratorPrototype has not been initialized");
        return value;
    }

    public void setAsyncGeneratorPrototype(JSObject value) {
        asyncGeneratorPrototype = Objects.requireNonNull(value, "asyncGeneratorPrototype");
    }

    public JSObject getAsyncFromSyncIteratorPrototype() {
        JSObject value = asyncFromSyncIteratorPrototype;
        if (value == null) throw new IllegalStateException("property asyncFromSyncIteratorPrototype has not been initialized");
        return value;
    }

    public void setAsyncFromSyncIteratorPrototype(JSObject value) {
        asyncFromSyncIteratorPrototype = Objects.requireNonNull(value, "asyncFromSyncIteratorPrototype");
    }

    public JSObject getPromisePrototype() {
        JSObject value = promisePrototype;
        if (value == null) throw new IllegalStateException("property promisePrototype has not been initialized");
        return value;
    }

    public void setPromisePrototype(JSObject value) {
        promisePrototype = Objects.requireNonNull(value, "promisePrototype");
    }

    public JSObject getPromiseConstructor() {
        JSObject value = promiseConstructor;
        if (value == null) throw new IllegalStateException("property promiseConstructor has not been initialized");
        return value;
    }

    public void setPromiseConstructor(JSObject value) {
        promiseConstructor = Objects.requireNonNull(value, "promiseConstructor");
    }

    public JSObject getObjectConstructor() {
        JSObject value = objectConstructor;
        if (value == null) throw new IllegalStateException("property objectConstructor has not been initialized");
        return value;
    }

    public void setObjectConstructor(JSObject value) {
        objectConstructor = Objects.requireNonNull(value, "objectConstructor");
    }

    public JSObject getFunctionConstructor() {
        JSObject value = functionConstructor;
        if (value == null) throw new IllegalStateException("property functionConstructor has not been initialized");
        return value;
    }

    public void setFunctionConstructor(JSObject value) {
        functionConstructor = Objects.requireNonNull(value, "functionConstructor");
    }

    public JSObject getArrayConstructor() {
        JSObject value = arrayConstructor;
        if (value == null) throw new IllegalStateException("property arrayConstructor has not been initialized");
        return value;
    }

    public void setArrayConstructor(JSObject value) {
        arrayConstructor = Objects.requireNonNull(value, "arrayConstructor");
    }

    public JSObject getThrowTypeError() {
        JSObject value = throwTypeError;
        if (value == null) throw new IllegalStateException("property throwTypeError has not been initialized");
        return value;
    }

    public void setThrowTypeError(JSObject value) {
        throwTypeError = Objects.requireNonNull(value, "throwTypeError");
    }

    public JSObject getEvalFunction() {
        JSObject value = evalFunction;
        if (value == null) throw new IllegalStateException("property evalFunction has not been initialized");
        return value;
    }

    public void setEvalFunction(JSObject value) {
        evalFunction = Objects.requireNonNull(value, "evalFunction");
    }

    public JSObject getGlobalObject() {
        JSObject value = globalObject;
        if (value == null) throw new IllegalStateException("property globalObject has not been initialized");
        return value;
    }

    public void setGlobalObject(JSObject value) {
        globalObject = Objects.requireNonNull(value, "globalObject");
    }

    public GlobalEnv getGlobalEnv() {
        GlobalEnv value = globalEnv;
        if (value == null) throw new IllegalStateException("property globalEnv has not been initialized");
        return value;
    }

    public void setGlobalEnv(GlobalEnv value) {
        globalEnv = Objects.requireNonNull(value, "globalEnv");
    }

    public HashMap<ErrorKind, JSObject> getErrorPrototypes() {
        return errorPrototypes;
    }

    public HashMap<ErrorKind, JSObject> getErrorConstructors() {
        return errorConstructors;
    }

    public HashMap<String, JSObject> getIntrinsics() {
        return intrinsics;
    }

    public HashMap<String, Object> getIntrinsicsAny() {
        return intrinsicsAny;
    }

    public WeakHashMap<TemplateSite, JSArray> getTemplateMap() {
        return templateMap;
    }

    public JSObject intrinsic(String name) {
        JSObject value = intrinsics.get(name);
        if (value == null) throw new IllegalStateException("missing intrinsic " + name);
        return value;
    }

    public JSErrorObject newError(ErrorKind kind, String message) {
        JSObject prototype = errorPrototypes.get(kind);
        JSErrorObject error = new JSErrorObject(prototype == null ? getErrorPrototype() : prototype);
        error.defineOwn("message", message, Attr.WC);
        if (agent.topFrame != null) error.stackTrace = agent.captureStack();
        return error;
    }

    public JSException typeError(String message) {
        return new JSException(newError(ErrorKind.TYPE, message));
    }

    public JSException rangeError(String message) {
        return new JSException(newError(ErrorKind.RANGE, message));
    }

    public JSException syntaxError(String message) {
        return new JSException(newError(ErrorKind.SYNTAX, message));
    }

    public <R> R enter(Supplier<R> block) {
        return agent.enter(() -> {
            Realm previous = agent.currentRealm;
            agent.currentRealm = this;
            try {
                return block.get();
            } finally {
                agent.currentRealm = previous;
            }
        });
    }
}

