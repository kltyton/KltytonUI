package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.builtins.Builtins;
import dev.mooner.neonjs.builtins.JSTypedArray;
import dev.mooner.neonjs.compiler.CodeBlock;
import dev.mooner.neonjs.compiler.DeclInfo;
import dev.mooner.neonjs.compiler.Op;
import dev.mooner.neonjs.compiler.RegExpSite;
import dev.mooner.neonjs.compiler.Source;
import dev.mooner.neonjs.compiler.TemplateSite;
import dev.mooner.neonjs.parser.FunctionKind;
import dev.mooner.neonjs.parser.JSSyntaxError;
import dev.mooner.neonjs.runtime.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import static dev.mooner.neonjs.runtime.Values.EMPTY_ARGS;

/** Shared interpreter and compiled-code operations; derived from NeonJS and adapted to Java. */
public final class Rt {
    private Rt() {
    }

    public static Object loadGlobal(Realm realm, String name, boolean forTypeof) {
        GlobalEnv environment = realm.getGlobalEnv();
        GlobalBinding binding = environment.lexical.get(name);
        if (binding != null) {
            if (binding.value == Uninitialized.INSTANCE) throw JSException.referenceError("Cannot access '" + name + "' before initialization");
            return binding.value;
        }
        JSObject global = environment.global;
        PropertyMap map = global.props;
        if (map != null) {
            int slot = map.find(name);
            if (slot >= 0 && (map.flags[slot] & Attr.ACCESSOR) == 0) return map.values[slot];
        }
        Object value = global.getOwnValue(name, global);
        if (value != NotFound.INSTANCE) return value;
        if (!global.hasProperty(name)) {
            if (forTypeof) return Undefined.INSTANCE;
            throw JSException.referenceError(name + " is not defined");
        }
        return global.get(name, global);
    }

    public static void storeGlobal(Realm realm, String name, Object value, boolean strict) {
        GlobalEnv environment = realm.getGlobalEnv();
        GlobalBinding binding = environment.lexical.get(name);
        if (binding != null) {
            if (binding.value == Uninitialized.INSTANCE) throw JSException.referenceError("Cannot access '" + name + "' before initialization");
            if (!binding.mutable) throw JSException.typeError("Assignment to constant variable.");
            binding.value = value;
            return;
        }
        JSObject global = environment.global;
        PropertyMap map = global.props;
        if (map != null) {
            int slot = map.find(name);
            if (slot >= 0 && (map.flags[slot] & (Attr.ACCESSOR | Attr.WRITABLE)) == Attr.WRITABLE) {
                map.values[slot] = value;
                return;
            }
        }
        if (strict && !global.hasProperty(name)) throw JSException.referenceError(name + " is not defined");
        if (!global.set(name, value, global) && strict) throw JSException.typeError("Cannot assign to read only property '" + name + "' of object '#<Object>'");
    }

    public static void initGlobalLexical(Realm realm, String name, Object value) {
        GlobalBinding binding = realm.getGlobalEnv().lexical.get(name);
        if (binding == null) throw new IllegalStateException("global lexical " + name + " not declared");
        binding.value = value;
    }

    public static void storeAnnexBVar(Env environment, String name, Object value, Realm realm) {
        for (Env current = environment; current != null; current = current.parent) {
            if (current instanceof DeclEnv declared && declared.info.evalVarTarget) {
                if (declared.extension != null && declared.extension.containsKey(name)) {
                    declared.extension.put(name, value);
                    return;
                }
                int slot = declared.info.lookup(name);
                if (slot >= 0) {
                    declared.slots[slot] = value;
                    return;
                }
            }
            if (current instanceof GlobalEnv global) {
                global.global.set(name, value, global.global);
                return;
            }
        }
        JSObject global = realm.getGlobalEnv().global;
        global.set(name, value, global);
    }

    public static void initThisDynamic(Env environment, Object value) {
        for (Env current = environment; current != null; current = current.parent) {
            if (current instanceof DeclEnv declared) {
                int slot = declared.info.lookup("this");
                if (slot >= 0) {
                    if (declared.slots[slot] != Uninitialized.INSTANCE) throw JSException.referenceError("Super constructor may only be called once");
                    declared.slots[slot] = value;
                    return;
                }
            }
        }
        throw JSException.syntaxError("'super' keyword unexpected here");
    }

    public static Object getPrimitiveProp(Realm realm, Object object, Object key) {
        return Ops.getV(realm, object, key);
    }

    public static JSException readOnlyError(Object key, Object object) {
        return JSException.typeError("Cannot assign to read only property '" + Ops.describeKey(key) + "' of " + Ops.describe(object));
    }

    public static void putPrimitive(Realm realm, Object object, Object key, Object value, boolean strict) {
        if (object == Undefined.INSTANCE || object == Null.INSTANCE) {
            throw JSException.typeError("Cannot set properties of " + Ops.toDisplayString(object) + " (setting '" + Ops.describeKey(key) + "')");
        }
        if (!Ops.putV(realm, object, key, value) && strict) {
            throw JSException.typeError("Cannot create property '" + Ops.describeKey(key) + "' on "
                    + Ops.typeOf(object) + " '" + Ops.toDisplayString(object) + "'");
        }
    }

    public static Object keyForBase(Object object, Object key) {
        if (object == Undefined.INSTANCE || object == Null.INSTANCE) {
            throw JSException.typeError("Cannot read properties of " + Ops.toDisplayString(object) + " (reading '"
                    + (key instanceof JSSymbol ? key.toString() : Ops.toDisplayString(key)) + "')");
        }
        return key instanceof Double ? key : Ops.toPropertyKey(key);
    }

    public static Object getElem(Realm realm, Object object, Object key) {
        if (key instanceof Integer index && index >= 0) {
            if (object instanceof JSArray array) return array.getIndexFast(index);
            if (object instanceof JSTypedArray array) return array.getIndex(index);
        }
        if (object instanceof JSArray array && key instanceof Double number) {
            int index = number.intValue();
            if (index >= 0 && (double) index == number) return array.getIndexFast(index);
        }
        if (object instanceof JSTypedArray array && key instanceof Double number) {
            int index = number.intValue();
            if (index >= 0 && (double) index == number) return array.getIndex(index);
        }
        if (object instanceof JSObject value) return value.get(Ops.toPropertyKey(key), value);
        if (object == Undefined.INSTANCE || object == Null.INSTANCE) {
            throw JSException.typeError("Cannot read properties of " + Ops.toDisplayString(object) + " (reading '"
                    + (key instanceof JSSymbol ? key.toString() : Ops.toDisplayString(key)) + "')");
        }
        if (object instanceof CharSequence text && key instanceof Double number) {
            int index = number.intValue();
            if (index >= 0 && (double) index == number && index < text.length()) return String.valueOf(text.charAt(index));
        }
        return Ops.getV(realm, object, Ops.toPropertyKey(key));
    }

    public static void putElem(Realm realm, Object object, Object key, Object value, boolean strict) {
        if (key instanceof Integer index && index >= 0) {
            if (object instanceof JSArray array && array.trySetIndexFast(index, value)) return;
            if (object instanceof JSTypedArray array && !array.buffer.immutable) {
                array.setIndex(index, value);
                return;
            }
        }
        if (object instanceof JSArray array && key instanceof Double number) {
            int index = number.intValue();
            if (index >= 0 && (double) index == number && array.trySetIndexFast(index, value)) return;
        }
        if (object instanceof JSTypedArray array && key instanceof Double number && !array.buffer.immutable) {
            int index = number.intValue();
            if (index >= 0 && (double) index == number) {
                array.setIndex(index, value);
                return;
            }
        }
        if (object instanceof JSObject target) {
            Object property = Ops.toPropertyKey(key);
            if (!target.set(property, value, target) && strict) throw readOnlyError(property, object);
            return;
        }
        if (object == Undefined.INSTANCE || object == Null.INSTANCE) {
            throw JSException.typeError("Cannot set properties of " + Ops.toDisplayString(object) + " (setting '"
                    + (key instanceof JSSymbol ? key.toString() : Ops.toDisplayString(key)) + "')");
        }
        putPrimitive(realm, object, Ops.toPropertyKey(key), value, strict);
    }

    public static boolean deleteProp(Realm realm, Object object, Object key, boolean strict) {
        JSObject target = object instanceof JSObject value ? value : Ops.toObject(realm, object);
        boolean deleted = target.delete(key);
        if (!deleted && strict) throw JSException.typeError("Cannot delete property '" + Ops.describeKey(key) + "' of " + Ops.describe(object));
        return deleted;
    }

    public static Object superBase(Object home) {
        if (!(home instanceof JSObject object)) return Undefined.INSTANCE;
        JSObject prototype = object.getPrototypeOf();
        return prototype == null ? Null.INSTANCE : prototype;
    }

    public static Object getSuper(Realm realm, Object receiver, Object key, Object base) {
        if (base == Undefined.INSTANCE || base == Null.INSTANCE) {
            throw JSException.typeError("Cannot read properties of " + Ops.toDisplayString(base) + " (reading '" + Ops.describeKey(key) + "')");
        }
        return Ops.toObject(realm, base).get(key, receiver);
    }

    public static void putSuper(Realm realm, Object receiver, Object key, Object base, Object value, boolean strict) {
        if (base == Undefined.INSTANCE || base == Null.INSTANCE) {
            throw JSException.typeError("Cannot set properties of " + Ops.toDisplayString(base) + " (setting '" + Ops.describeKey(key) + "')");
        }
        if (!Ops.toObject(realm, base).set(key, value, receiver) && strict) {
            throw JSException.typeError("Cannot assign to read only property '" + Ops.describeKey(key) + "' of object");
        }
    }

    public static Object privateGet(Object receiver, PrivateName name) {
        if (!(receiver instanceof JSObject object) || object.privateElements == null || !object.privateElements.containsKey(name)) {
            throw JSException.typeError("Cannot read private member " + name.description + " from an object whose class did not declare it");
        }
        Object value = object.privateElements.get(name);
        if (value instanceof PrivateElement element) {
            if (element.kind == PrivateElement.METHOD) return element.value;
            if (!(element.getter instanceof JSObject getter)) throw JSException.typeError("'" + name.description + "' was defined without a getter");
            return getter.call(object, EMPTY_ARGS);
        }
        return value;
    }

    public static void privateSet(Object receiver, PrivateName name, Object value) {
        if (!(receiver instanceof JSObject object) || object.privateElements == null || !object.privateElements.containsKey(name)) {
            throw JSException.typeError("Cannot write private member " + name.description + " to an object whose class did not declare it");
        }
        Object stored = object.privateElements.get(name);
        if (stored instanceof PrivateElement element) {
            if (element.kind == PrivateElement.METHOD) throw JSException.typeError("Private method " + name.description + " is not writable");
            if (!(element.setter instanceof JSObject setter)) throw JSException.typeError("'" + name.description + "' was defined without a setter");
            setter.call(object, new Object[] {value});
            return;
        }
        object.privateElements.put(name, value);
    }

    public static boolean privateIn(PrivateName name, Object receiver) {
        if (!(receiver instanceof JSObject object)) {
            throw JSException.typeError("Cannot use 'in' operator to search for '" + name.description + "' in " + Ops.toDisplayString(receiver));
        }
        return object.privateElements != null && object.privateElements.containsKey(name);
    }

    public static void privateFieldAdd(JSObject object, PrivateName name, Object value) {
        if (object.privateElements == null) object.privateElements = new IdentityHashMap<>();
        if (object.privateElements.containsKey(name)) throw JSException.typeError("Cannot initialize " + name.description + " twice on the same object");
        if (!object.isExtensible()) throw JSException.typeError("Cannot define private field " + name.description + " on a non-extensible object");
        object.privateElements.put(name, value);
    }

    public static void privateMethodAdd(JSObject object, PrivateElement element) {
        if (object.privateElements == null) object.privateElements = new IdentityHashMap<>();
        if (object.privateElements.containsKey(element.key)) throw JSException.typeError("Cannot initialize private methods of class twice on the same object");
        if (!object.isExtensible()) throw JSException.typeError("Cannot add private methods to a non-extensible object");
        object.privateElements.put(element.key, element);
    }

    public static void addPrivateMethod(JSClosure constructor, PrivateName name, Object function, int kind) {
        addPrivateMethod(constructor, name, function, kind, true);
    }

    public static void addPrivateMethod(JSClosure constructor, PrivateName name, Object function, int kind, boolean rename) {
        if (constructor.privateMethods == null) constructor.privateMethods = new ArrayList<>();
        mergePrivate(constructor.privateMethods, name, function, kind, rename);
    }

    private static void mergePrivate(List<PrivateElement> elements, PrivateName name, Object function, int kind, boolean rename) {
        if (rename && function instanceof JSFunction value) {
            String label = kind == 1 ? "get " + name.description : kind == 2 ? "set " + name.description : name.description;
            value.defineOwn("name", label, Attr.CONFIGURABLE);
        }
        for (PrivateElement element : elements) {
            if (element.key == name) {
                if (kind == 1) element.getter = function;
                else if (kind == 2) element.setter = function;
                return;
            }
        }
        elements.add(kind == 0 ? new PrivateElement(name, PrivateElement.METHOD, function, null, null)
                : kind == 1 ? new PrivateElement(name, PrivateElement.ACCESSOR, null, function, null)
                : new PrivateElement(name, PrivateElement.ACCESSOR, null, null, function));
    }

    public static void staticPrivateMethod(JSObject object, PrivateName name, Object function, int kind) {
        staticPrivateMethod(object, name, function, kind, true);
    }

    public static void staticPrivateMethod(JSObject object, PrivateName name, Object function, int kind, boolean rename) {
        if (object.privateElements == null) object.privateElements = new IdentityHashMap<>();
        Object existing = object.privateElements.get(name);
        ArrayList<PrivateElement> elements = new ArrayList<>();
        if (existing instanceof PrivateElement element) {
            elements.add(element);
            mergePrivate(elements, name, function, kind, rename);
        } else {
            mergePrivate(elements, name, function, kind, rename);
            object.privateElements.put(name, elements.get(0));
        }
    }

    public static void initializeInstanceElements(JSObject object, JSClosure constructor) {
        if (constructor.privateMethods != null) {
            for (PrivateElement element : constructor.privateMethods) privateMethodAdd(object, element);
        }
        if (constructor.extraInitializers != null) {
            for (JSObject initializer : constructor.extraInitializers) initializer.call(object, EMPTY_ARGS);
        }
        if (constructor.fields != null) for (FieldRecord field : constructor.fields) defineField(object, field);
    }

    public static void defineField(JSObject object, FieldRecord field) {
        Object key = field.key;
        Object value = field.initializer instanceof JSObject initializer
                ? initializer.call(object, new Object[] {key instanceof PrivateName name ? name.description : PK.toValue(key)})
                : Undefined.INSTANCE;
        if (field.decoratorInitializers != null) {
            for (JSObject initializer : field.decoratorInitializers) value = initializer.call(object, new Object[] {value});
        }
        if (key instanceof PrivateName name) privateFieldAdd(object, name, value);
        else object.createDataPropertyOrThrow(key, value);
        if (field.extraInitializers != null) {
            for (JSObject initializer : field.extraInitializers) initializer.call(object, EMPTY_ARGS);
        }
    }

    public static Object superCall(Object function, Object[] arguments, Object newTarget) {
        if (!Ops.isConstructor(function)) throw JSException.typeError("Super constructor " + Ops.describe(function) + " is not a constructor");
        return ((JSObject) function).construct(arguments, (JSObject) newTarget);
    }

    public static JSClosure makeClosureIn(Frame frame, CodeBlock code) {
        JSClosure closure = makeClosure(frame.realm, code, frame.env, null);
        if ((code.flags & CodeBlock.ARROW) != 0) closure.lexicalThis = frame.thisValue;
        return closure;
    }

    public static JSClosure makeClosure(Realm realm, CodeBlock code, Env environment, JSObject home) {
        JSObject prototype = code.isGenerator()
                ? code.isAsync() ? realm.getAsyncGeneratorFunctionPrototype() : realm.getGeneratorFunctionPrototype()
                : code.isAsync() ? realm.getAsyncFunctionPrototype() : realm.getFunctionPrototype();
        JSClosure function = new JSClosure(realm, code, environment, prototype);
        function.homeObject = home;
        function.defineOwn("length", (double) code.length, Attr.CONFIGURABLE);
        if (code.kind != FunctionKind.CLASS_FIELD_INIT && code.kind != FunctionKind.STATIC_BLOCK) {
            String name = code.kind == FunctionKind.GETTER ? "get " + code.name
                    : code.kind == FunctionKind.SETTER ? "set " + code.name : code.name;
            function.defineOwn("name", name, Attr.CONFIGURABLE);
        }
        if (code.kind == FunctionKind.NORMAL
                && (code.flags & (CodeBlock.STRICT | CodeBlock.ARROW | CodeBlock.GENERATOR | CodeBlock.ASYNC)) == 0) {
            function.defineOwn("arguments", Undefined.INSTANCE, Attr.NONE);
            function.defineOwn("caller", Undefined.INSTANCE, Attr.NONE);
        }
        if (code.isGenerator()) {
            function.defineOwn("prototype", new JSObject(code.isAsync() ? realm.getAsyncGeneratorPrototype() : realm.getGeneratorPrototype()), Attr.WRITABLE);
        } else if ((code.flags & CodeBlock.CONSTRUCTOR) != 0 && (code.flags & CodeBlock.CLASS_CTOR) == 0) {
            JSObject object = new JSObject(realm.getObjectPrototype());
            object.defineOwn("constructor", function, Attr.WC);
            function.defineOwn("prototype", object, Attr.WRITABLE);
        }
        return function;
    }

    public static void setFunctionName(JSObject object, Object key, String prefix) {
        if (!(object instanceof JSFunction function)) return;
        PropertyMap map = function.props;
        if (map != null && map.find("name") >= 0 && function instanceof JSClosure closure && closure.code.isClassConstructor()) return;
        function.setFunctionName(key, prefix);
    }

    public static void defineMethod(JSObject object, Object key, JSFunction function, int opcode, boolean enumerable) {
        if (opcode == Op.DEFINE_GETTER) {
            function.setFunctionName(key, "get");
            object.definePropertyOrThrow(key, new PropertyDescriptor().getter(function).enumerable(enumerable).configurable(true));
        } else if (opcode == Op.DEFINE_SETTER) {
            function.setFunctionName(key, "set");
            object.definePropertyOrThrow(key, new PropertyDescriptor().setter(function).enumerable(enumerable).configurable(true));
        } else {
            function.setFunctionName(key, null);
            object.definePropertyOrThrow(key, PropertyDescriptor.data(function,
                    Attr.WRITABLE | Attr.CONFIGURABLE | (enumerable ? Attr.ENUMERABLE : 0)));
        }
    }

    public static Object[] makeClass(Realm realm, CodeBlock code, Env environment, Object heritage, Object name) {
        JSObject parentPrototype;
        JSObject constructorParent;
        if (heritage == NotFound.INSTANCE) {
            parentPrototype = realm.getObjectPrototype();
            constructorParent = realm.getFunctionPrototype();
        } else if (heritage == Null.INSTANCE) {
            parentPrototype = null;
            constructorParent = realm.getFunctionPrototype();
        } else {
            if (!Ops.isConstructor(heritage)) throw JSException.typeError("Class extends value " + Ops.describe(heritage) + " is not a constructor or null");
            JSObject parent = (JSObject) heritage;
            if (parent instanceof JSClosure closure && closure.code.isGenerator()) throw JSException.typeError("Class extends value is a generator");
            Object prototype = parent.get("prototype", parent);
            if (prototype instanceof JSObject object) parentPrototype = object;
            else if (prototype == Null.INSTANCE) parentPrototype = null;
            else throw JSException.typeError("Class extends value does not have valid prototype property " + Ops.toDisplayString(prototype));
            constructorParent = parent;
        }
        JSObject prototype = new JSObject(parentPrototype);
        JSClosure constructor = new JSClosure(realm, code, environment, constructorParent);
        constructor.homeObject = prototype;
        constructor.defineOwn("length", (double) code.length, Attr.CONFIGURABLE);
        constructor.defineOwn("name", name == null ? code.name : PK.functionName(name), Attr.CONFIGURABLE);
        constructor.defineOwn("prototype", prototype, Attr.NONE);
        prototype.defineOwn("constructor", constructor, Attr.WC);
        return new Object[] {prototype, constructor};
    }

    public static void copyDataProperties(JSObject target, Object source, Set<Object> excluded) {
        if (source == Undefined.INSTANCE || source == Null.INSTANCE) return;
        JSObject object = Ops.toObject(source);
        for (Object key : object.ownPropertyKeys()) {
            if (excluded != null && excluded.contains(key)) continue;
            PropertyDescriptor descriptor = object.getOwnProperty(key);
            if (descriptor != null && descriptor.enumerable) target.createDataPropertyOrThrow(key, object.get(key, object));
        }
    }

    public static void arraySpread(Realm realm, JSArray target, Object value) {
        if (value instanceof JSArray array && (array.special & JSObject.SPECIAL_ALL) == JSObject.EXOTIC_OWN
                && !array.sparse && Iteration.isPristineArrayIteration(realm, array)) {
            for (int i = 0, count = (int) array.length; i < count; i++) target.pushInit(array.get(i, array));
            return;
        }
        IteratorRecord iterator = Iteration.getIterator(realm, value, false);
        while (true) {
            Object item = Iteration.stepValue(iterator);
            if (item == NotFound.INSTANCE) break;
            target.pushInit(item);
        }
    }

    public static Object[] arrayToArgs(JSArray array) {
        int count = (int) array.length;
        if (count == 0) return EMPTY_ARGS;
        Object[] result = new Object[count];
        for (int i = 0; i < count; i++) result[i] = array.get(i, array);
        return result;
    }

    public static JSObject createUnmappedArguments(Realm realm, Object[] arguments) {
        JSArgumentsObject object = new JSArgumentsObject(realm.getObjectPrototype());
        object.defineOwn("length", (double) arguments.length, Attr.WC);
        for (int i = 0; i < arguments.length; i++) object.defineOwn(i, arguments[i], Attr.ALL);
        object.defineOwn(JSSymbol.iterator, realm.getArrayProtoValues(), Attr.WC);
        object.defineAccessor("callee", realm.getThrowTypeError(), realm.getThrowTypeError(), Attr.NONE);
        return object;
    }

    public static JSObject createMappedArguments(JSClosure function, Object[] arguments, DeclEnv environment, int[] slots) {
        Realm realm = function.realm;
        if (environment == null || slots.length == 0) {
            JSArgumentsObject object = new JSArgumentsObject(realm.getObjectPrototype());
            object.defineOwn("length", (double) arguments.length, Attr.WC);
            for (int i = 0; i < arguments.length; i++) object.defineOwn(i, arguments[i], Attr.ALL);
            object.defineOwn(JSSymbol.iterator, realm.getArrayProtoValues(), Attr.WC);
            object.defineOwn("callee", function, Attr.WC);
            return object;
        }
        JSMappedArguments object = new JSMappedArguments(realm.getObjectPrototype(), environment);
        object.defineOwn("length", (double) arguments.length, Attr.WC);
        for (int i = 0; i < arguments.length; i++) object.defineOwn(i, arguments[i], Attr.ALL);
        HashSet<String> mapped = new HashSet<>();
        for (int i = slots.length - 1; i >= 0; i--) {
            int slot = slots[i];
            String name = environment.info.names[slot];
            if (mapped.add(name) && i < arguments.length) object.map(i, slot);
        }
        object.defineOwn(JSSymbol.iterator, realm.getArrayProtoValues(), Attr.WC);
        object.defineOwn("callee", function, Attr.WC);
        return object;
    }

    public static String calleeText(CodeBlock code, int pc, Object value) {
        Source source = code.source;
        int[] sites = code.callSites;
        int lo = 0;
        int hi = sites.length / 3 - 1;
        while (source != null && lo <= hi) {
            int middle = (lo + hi) >>> 1;
            int at = sites[3 * middle];
            if (at < pc) lo = middle + 1;
            else if (at > pc) hi = middle - 1;
            else {
                int start = sites[3 * middle + 1];
                int end = sites[3 * middle + 2];
                if (start >= 0 && start < end && end <= source.text.length()) {
                    String text = compactCallee(source.text, start, end);
                    if (!text.isEmpty() && text.length() <= 120) return text;
                }
                return Ops.describe(value);
            }
        }
        int position = code.positionAt(pc);
        if (source != null && position >= 0 && position < source.text.length()) {
            String text = source.text;
            int end = position;
            int depth = 0;
            while (end < text.length()) {
                char c = text.charAt(end);
                if (c == '(' && depth == 0) break;
                if (c == '[' || c == '{') depth++;
                if (c == ']' || c == '}') depth--;
                if (c == '\n' || c == ';') break;
                end++;
            }
            String expression = trimCallee(text.substring(position, end));
            if (!expression.isEmpty() && expression.length() < 80 && !expression.startsWith("new ")) return expression;
            if (expression.startsWith("new ")) return trimCallee(expression.substring(4));
        }
        return Ops.describe(value);
    }

    private static boolean space(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    private static String trimCallee(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && space(value.charAt(start))) start++;
        while (end > start && space(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static int copyLiteral(String text, int start, int end, StringBuilder out) {
        char quote = text.charAt(start);
        out.append(quote);
        int index = start + 1;
        while (index < end) {
            char c = text.charAt(index++);
            out.append(c);
            if (c == '\\' && index < end) out.append(text.charAt(index++));
            else if (c == quote) return index;
        }
        return index;
    }

    private static String compactCallee(String text, int start, int end) {
        StringBuilder out = new StringBuilder();
        int index = start;
        while (index < end) {
            char c = text.charAt(index);
            if (c == '"' || c == '\'' || c == '`') {
                index = copyLiteral(text, index, end, out);
            } else if (space(c)) {
                while (index < end && space(text.charAt(index))) index++;
                if (out.length() != 0 && out.charAt(out.length() - 1) != '.' && index < end
                        && text.charAt(index) != '.' && text.charAt(index) != '?') out.append(' ');
            } else if (c == '(' && out.length() != 0 && (Character.isLetterOrDigit(out.charAt(out.length() - 1))
                    || "_$)]`".indexOf(out.charAt(out.length() - 1)) >= 0)) {
                int depth = 0;
                while (index < end) {
                    char current = text.charAt(index);
                    if (current == '"' || current == '\'' || current == '`') {
                        int mark = out.length();
                        index = copyLiteral(text, index, end, out);
                        out.setLength(mark);
                        continue;
                    }
                    index++;
                    if (current == '(') depth++;
                    else if (current == ')' && --depth == 0) break;
                }
                out.append("(...)");
            } else {
                out.append(c);
                index++;
            }
        }
        return trimCallee(out.toString());
    }

    public static JSException notCallable(Object value, CodeBlock code, int pc) {
        return JSException.typeError(calleeText(code, pc, value) + " is not a function");
    }

    public static Object catchValue(Throwable error, Realm realm, Frame frame, int pc) {
        if (error instanceof JSException guest) {
            if (frame != null) attachStack(guest, frame, pc);
            return guest.value;
        }
        if (error instanceof TerminationException termination) throw termination;
        if (error instanceof StackOverflowError) return realm.newError(ErrorKind.RANGE, "Maximum call stack size exceeded");
        if (error instanceof OutOfMemoryError) throw new ResourceLimitException("Out of memory");
        if (error instanceof JSSyntaxError syntax) return realm.newError(ErrorKind.SYNTAX, syntax.getRawMessage());
        if (error instanceof HostException host) return realm.agent.hostExceptionToJS(host, realm);
        if (error instanceof InterruptedException) throw new InterruptedExecutionException("interrupted");
        if (error instanceof LinkageError linkage) throw linkage;
        if (realm.agent.config.propagateInternalErrors) return Rt.<RuntimeException, Object>rethrow(error);
        JSErrorObject internal = realm.newError(ErrorKind.ERROR, "Internal error: " + error);
        internal.defineOwn("name", "InternalError", Attr.WC);
        return internal;
    }

    public static void attachStack(JSException error, Frame frame, int pc) {
        if (!(error.value instanceof JSErrorObject object)) return;
        boolean sameStep = object.capturedAt != null && object.capturedAt.get() == frame && object.capturedPc == frame.pc;
        if (object.stackTrace == null || sameStep) {
            frame.pc = pc;
            object.stackTrace = frame.realm.agent.captureStack();
        }
        object.capturedAt = null;
    }

    public static JSArray templateObject(Realm realm, TemplateSite site) {
        JSArray cached = realm.templateMap.get(site);
        if (cached != null) return cached;
        String[] raw = site.getRaw();
        String[] cooked = site.getCooked();
        JSArray rawArray = new JSArray(realm.getArrayPrototype());
        JSArray array = new JSArray(realm.getArrayPrototype());
        for (int i = 0; i < raw.length; i++) {
            array.pushInit(cooked[i] == null ? Undefined.INSTANCE : cooked[i]);
            rawArray.pushInit(raw[i]);
        }
        Builtins.freeze(rawArray);
        array.defineOwn("raw", rawArray, Attr.NONE);
        Builtins.freeze(array);
        realm.templateMap.put(site, array);
        return array;
    }

    public static JSObject newRegExp(Realm realm, RegExpSite site) {
        return realm.agent.regexpFactory.apply(realm, site);
    }

    public static JSObject importMeta(Frame frame) {
        return frame.realm.agent.moduleLoader.importMeta(frame);
    }

    public static Object dynamicImport(Frame frame, Object specifier, Object options) {
        return frame.realm.agent.moduleLoader.dynamicImport(frame, specifier, options);
    }

    public static Object promiseResolveForAwait(Realm realm, Object value) {
        return Promises.promiseResolve(realm, realm.getPromiseConstructor(), value);
    }

    public static Object promiseResolveOrNull(Realm realm, Object value) {
        try {
            return Promises.promiseResolve(realm, realm.getPromiseConstructor(), value);
        } catch (JSException error) {
            return Promises.rejected(realm, error.value);
        }
    }

    public static Object directEval(Frame frame, Object[] arguments, boolean strict) {
        return Evaluator.directEval(frame, arguments, strict);
    }

    public static void declareGlobals(Realm realm, DeclInfo info, Object[] functions) {
        Evaluator.globalDeclarationInstantiation(realm, info, functions);
    }

    public static void declareEval(Frame frame, DeclInfo info, Object[] functions) {
        Evaluator.evalDeclarationInstantiation(frame, info, functions);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T rethrow(Throwable error) throws E {
        throw (E) error;
    }
}
