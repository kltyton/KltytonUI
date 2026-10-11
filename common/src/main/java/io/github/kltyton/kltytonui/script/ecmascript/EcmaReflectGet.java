package io.github.kltyton.kltytonui.script.ecmascript;

import dev.latvian.mods.rhino.BaseFunction;
import dev.latvian.mods.rhino.Callable;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.NativeArray;
import dev.latvian.mods.rhino.NativeObject;
import dev.latvian.mods.rhino.NativeSymbol;
import dev.latvian.mods.rhino.ScriptRuntime;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.latvian.mods.rhino.Symbol;
import dev.latvian.mods.rhino.SymbolScriptable;

/** Uses Rhino's receiver-aware property slots without allocating JavaScript descriptors. */
public final class EcmaReflectGet extends BaseFunction {
    private final Callable descriptorGetter;

    private EcmaReflectGet(Context context, Scriptable scope, Callable descriptorGetter) {
        this.descriptorGetter = descriptorGetter;
        ScriptRuntime.setFunctionProtoAndParent(context, scope, this);
    }

    public static void install(Context context, Scriptable scope) {
        Object reflect = ScriptableObject.getProperty(scope, "Reflect", context);
        if (!(reflect instanceof Scriptable object)) return;
        Object getter = ScriptableObject.getProperty(object, "get", context);
        if (getter instanceof Callable callable && !(getter instanceof EcmaReflectGet)) {
            ScriptableObject.putProperty(object, "get", new EcmaReflectGet(context, scope, callable), context);
        }
    }

    @Override
    public String getFunctionName() {
        return "get";
    }

    @Override
    public int getLength() {
        return 2;
    }

    @Override
    public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
        Object target = args.length == 0 ? Context.getUndefinedValue() : args[0];
        Object receiver = args.length < 3 ? target : args[2];
        // Java host descriptors and primitive receivers use the existing host conversion contract.
        if (!(target instanceof NativeObject || target instanceof NativeArray || target instanceof EcmaProxyObject)
                || !(receiver instanceof Scriptable start)) {
            return descriptorGetter.call(context, scope, thisObj, args);
        }
        Object key = args.length < 2 ? Context.getUndefinedValue() : args[1];
        return getProperty(context, (Scriptable) target, key, start);
    }

    static Object getProperty(Context context, Scriptable target, Object key, Scriptable receiver) {
        boolean symbolKey = key instanceof Symbol && (!(key instanceof NativeSymbol symbol) || symbol.isSymbol());
        String name = symbolKey ? null : ScriptRuntime.toString(context, key);
        long index = symbolKey ? -1 : ScriptRuntime.indexFromString(name);
        for (Scriptable object = target; object != null; object = object.getPrototype(context)) {
            Object value;
            if (symbolKey) {
                value = object instanceof SymbolScriptable symbols
                        ? symbols.get(context, (Symbol) key, receiver) : Scriptable.NOT_FOUND;
            } else if (index >= 0) {
                value = object.get(context, (int) index, receiver);
            } else {
                value = object.get(context, name, receiver);
            }
            if (value != Scriptable.NOT_FOUND) return value;
        }
        return Context.getUndefinedValue();
    }
}
