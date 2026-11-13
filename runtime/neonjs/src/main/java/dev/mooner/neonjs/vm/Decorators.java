package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSFunction;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import static dev.mooner.neonjs.runtime.Values.EMPTY_ARGS;
import static dev.mooner.neonjs.runtime.Values.arg;

/** Class and element decorator execution; derived from NeonJS and adapted to Java. */
public final class Decorators {
    private Decorators() {
    }

    public static ClassDef newClassDef(Object constructor, Object prototype) {
        return new ClassDef((JSClosure) constructor, (JSObject) prototype);
    }

    public static void addElement(Object definition, Object decorators, Object key, Object function, int flags) {
        ClassDef def = (ClassDef) definition;
        int kind = flags & 7;
        boolean isStatic = (flags & ClassDef.STATIC) != 0;
        Object[] values = null;
        if (decorators instanceof JSArray array) {
            values = new Object[(int) array.length];
            for (int i = 0; i < values.length; i++) values[i] = array.get(i, array);
        }
        ClassDef.Element element = new ClassDef.Element(kind, isStatic, Objects.requireNonNull(key), function, values);
        if (function instanceof JSFunction fn) {
            if (kind == ClassDef.METHOD) nameFunction(fn, key, null);
            else if (kind == ClassDef.GETTER) nameFunction(fn, key, "get");
            else if (kind == ClassDef.SETTER) nameFunction(fn, key, "set");
        }
        if (kind == ClassDef.ACCESSOR) makeAutoAccessor(def, element);
        def.elements.add(element);
    }

    private static void nameFunction(JSFunction function, Object key, String prefix) {
        if (key instanceof PrivateName name) {
            function.defineOwn("name", prefix == null ? name.description : prefix + " " + name.description, Attr.CONFIGURABLE);
        } else {
            function.setFunctionName(key, prefix);
        }
    }

    private static void makeAutoAccessor(ClassDef definition, ClassDef.Element element) {
        Realm realm = definition.ctor.realm;
        String name = element.key instanceof PrivateName privateName ? privateName.description : PK.toStringKey(element.key);
        PrivateName storage = new PrivateName(name + " accessor storage");
        element.storage = storage;
        NativeFunction getter = new NativeFunction(realm, "", 0,
                (function, receiver, arguments, target) -> Rt.privateGet(receiver, storage));
        NativeFunction setter = new NativeFunction(realm, "", 1, (function, receiver, arguments, target) -> {
            Rt.privateSet(receiver, storage, arg(arguments, 0));
            return Undefined.INSTANCE;
        });
        nameFunction(getter, element.key, "get");
        nameFunction(setter, element.key, "set");
        element.getter = getter;
        element.setter = setter;
    }

    public static void finish(Object definition) {
        ClassDef def = (ClassDef) definition;
        for (ClassDef.Element element : def.elements) {
            if (element.isStatic && element.kind != ClassDef.FIELD && element.kind != ClassDef.BLOCK) decorateAndDefine(def, element);
        }
        for (ClassDef.Element element : def.elements) {
            if (!element.isStatic && element.kind != ClassDef.FIELD) decorateAndDefine(def, element);
        }
        for (ClassDef.Element element : def.elements) {
            if (element.isStatic && element.kind == ClassDef.FIELD && decorated(element)) applyElementDecorators(def, element);
        }
        for (ClassDef.Element element : def.elements) {
            if (!element.isStatic && element.kind == ClassDef.FIELD && decorated(element)) applyElementDecorators(def, element);
        }
        for (ClassDef.Element element : def.elements) {
            if (element.isStatic) continue;
            if (element.kind == ClassDef.ACCESSOR || element.kind == ClassDef.FIELD) {
                FieldRecord field = fieldRecord(element);
                if (def.ctor.fields == null) def.ctor.fields = new ArrayList<>();
                def.ctor.fields.add(field);
            }
        }
        if (!def.instanceExtraInitializers.isEmpty()) def.ctor.extraInitializers = new ArrayList<>(def.instanceExtraInitializers);
        for (ClassDef.Element element : def.elements) {
            if (!element.isStatic || !(element.key instanceof PrivateName name)) continue;
            if (element.kind == ClassDef.METHOD || element.kind == ClassDef.GETTER || element.kind == ClassDef.SETTER) {
                Rt.staticPrivateMethod(def.ctor, name, element.fn, element.kind, false);
            } else if (element.kind == ClassDef.ACCESSOR) {
                Rt.staticPrivateMethod(def.ctor, name, element.getter, ClassDef.GETTER, false);
                Rt.staticPrivateMethod(def.ctor, name, element.setter, ClassDef.SETTER, false);
            }
        }
    }

    private static boolean decorated(ClassDef.Element element) {
        return element.decorators != null && element.decorators.length != 0;
    }

    private static FieldRecord fieldRecord(ClassDef.Element element) {
        Object key = element.kind == ClassDef.ACCESSOR ? Objects.requireNonNull(element.storage) : element.key;
        FieldRecord field = new FieldRecord(key, element.fn);
        field.decoratorInitializers = element.initializers;
        field.extraInitializers = element.extraInitializers;
        return field;
    }

    private static void decorateAndDefine(ClassDef definition, ClassDef.Element element) {
        if (decorated(element)) applyElementDecorators(definition, element);
        JSObject target = element.isStatic ? definition.ctor : definition.proto;
        Object key = element.key;
        if (element.kind == ClassDef.METHOD || element.kind == ClassDef.GETTER || element.kind == ClassDef.SETTER) {
            if (key instanceof PrivateName name) {
                if (!element.isStatic) Rt.addPrivateMethod(definition.ctor, name, element.fn, element.kind, false);
            } else {
                PropertyDescriptor descriptor;
                if (element.kind == ClassDef.GETTER) descriptor = new PropertyDescriptor().getter(element.fn).enumerable(false).configurable(true);
                else if (element.kind == ClassDef.SETTER) descriptor = new PropertyDescriptor().setter(element.fn).enumerable(false).configurable(true);
                else descriptor = PropertyDescriptor.data(element.fn, Attr.WC);
                target.definePropertyOrThrow(key, descriptor);
            }
        } else if (element.kind == ClassDef.ACCESSOR) {
            if (key instanceof PrivateName name) {
                if (!element.isStatic) {
                    Rt.addPrivateMethod(definition.ctor, name, element.getter, ClassDef.GETTER, false);
                    Rt.addPrivateMethod(definition.ctor, name, element.setter, ClassDef.SETTER, false);
                }
            } else {
                target.definePropertyOrThrow(key, new PropertyDescriptor().getter(element.getter)
                        .setter(element.setter).enumerable(false).configurable(true));
            }
        }
    }

    private static String kindName(int kind) {
        return switch (kind) {
            case ClassDef.METHOD -> "method";
            case ClassDef.GETTER -> "getter";
            case ClassDef.SETTER -> "setter";
            case ClassDef.FIELD -> "field";
            case ClassDef.ACCESSOR -> "accessor";
            default -> "class";
        };
    }

    private static void applyElementDecorators(ClassDef definition, ClassDef.Element element) {
        Realm realm = definition.ctor.realm;
        Object[] decorators = element.decorators;
        for (int i = decorators.length - 1; i >= 0; i--) {
            Object decorator = decorators[i];
            if (!Ops.isCallable(decorator)) throw JSException.typeError("Decorator is not a function");
            boolean[] finished = new boolean[1];
            JSObject context = elementContext(definition, element, realm, finished);
            Object value;
            if (element.kind == ClassDef.FIELD) value = Undefined.INSTANCE;
            else if (element.kind == ClassDef.ACCESSOR) {
                JSObject accessor = new JSObject(realm.getObjectPrototype());
                accessor.createDataProperty("get", element.getter);
                accessor.createDataProperty("set", element.setter);
                value = accessor;
            } else value = element.fn;
            Object result;
            try {
                result = Ops.call(decorator, Undefined.INSTANCE, new Object[] {value, context});
            } finally {
                finished[0] = true;
            }
            if (result == Undefined.INSTANCE) continue;
            if (element.kind == ClassDef.METHOD || element.kind == ClassDef.GETTER || element.kind == ClassDef.SETTER) {
                if (!Ops.isCallable(result)) throw JSException.typeError("Method decorators must return a function or undefined");
                element.fn = result;
            } else if (element.kind == ClassDef.FIELD) {
                if (!Ops.isCallable(result)) throw JSException.typeError("Field decorators must return a function or undefined");
                if (element.initializers == null) element.initializers = new ArrayList<>();
                element.initializers.add((JSObject) result);
            } else if (element.kind == ClassDef.ACCESSOR) {
                if (!(result instanceof JSObject accessor)) throw JSException.typeError("Accessor decorators must return an object or undefined");
                Object getter = accessor.get("get", accessor);
                if (getter != Undefined.INSTANCE) {
                    if (!Ops.isCallable(getter)) throw JSException.typeError("Accessor decorator 'get' must be a function");
                    element.getter = getter;
                }
                Object setter = accessor.get("set", accessor);
                if (setter != Undefined.INSTANCE) {
                    if (!Ops.isCallable(setter)) throw JSException.typeError("Accessor decorator 'set' must be a function");
                    element.setter = setter;
                }
                Object initializer = accessor.get("init", accessor);
                if (initializer != Undefined.INSTANCE) {
                    if (!Ops.isCallable(initializer)) throw JSException.typeError("Accessor decorator 'init' must be a function");
                    if (element.initializers == null) element.initializers = new ArrayList<>();
                    element.initializers.add((JSObject) initializer);
                }
            }
        }
    }

    private static JSObject elementContext(ClassDef definition, ClassDef.Element element, Realm realm, boolean[] finished) {
        JSObject context = new JSObject(realm.getObjectPrototype());
        Object key = element.key;
        context.createDataProperty("kind", kindName(element.kind));
        context.createDataProperty("name", key instanceof PrivateName name ? name.description : PK.toValue(key));
        JSObject access = new JSObject(realm.getObjectPrototype());
        access.createDataProperty("has", new NativeFunction(realm, "has", 1, (function, receiver, arguments, target) -> {
            Object value = arg(arguments, 0);
            if (!(value instanceof JSObject object)) throw JSException.typeError("access.has requires an object");
            return key instanceof PrivateName name ? Rt.privateIn(name, object) : object.hasProperty(key);
        }));
        if (element.kind != ClassDef.SETTER) {
            access.createDataProperty("get", new NativeFunction(realm, "get", 1, (function, receiver, arguments, target) -> {
                Object object = arg(arguments, 0);
                return key instanceof PrivateName name ? Rt.privateGet(object, name) : Ops.getV(object, key);
            }));
        }
        if (element.kind == ClassDef.SETTER || element.kind == ClassDef.FIELD || element.kind == ClassDef.ACCESSOR) {
            access.createDataProperty("set", new NativeFunction(realm, "set", 2, (function, receiver, arguments, target) -> {
                Object object = arg(arguments, 0);
                if (key instanceof PrivateName name) Rt.privateSet(object, name, arg(arguments, 1));
                else if (!Ops.putV(function.realm, object, key, arg(arguments, 1))) throw JSException.typeError("Cannot assign to '" + PK.toStringKey(key) + "'");
                return Undefined.INSTANCE;
            }));
        }
        context.createDataProperty("access", access);
        context.createDataProperty("static", element.isStatic);
        context.createDataProperty("private", key instanceof PrivateName);
        List<JSObject> initializers;
        if (element.kind == ClassDef.FIELD || element.kind == ClassDef.ACCESSOR) {
            if (element.extraInitializers == null) element.extraInitializers = new ArrayList<>();
            initializers = element.extraInitializers;
        } else initializers = element.isStatic ? definition.staticExtraInitializers : definition.instanceExtraInitializers;
        context.createDataProperty("addInitializer", addInitializerFunction(realm, initializers, finished));
        context.createDataProperty("metadata", definition.getMetadata());
        return context;
    }

    private static NativeFunction addInitializerFunction(Realm realm, List<JSObject> initializers, boolean[] finished) {
        return new NativeFunction(realm, "addInitializer", 1, (function, receiver, arguments, target) -> {
            if (finished[0]) throw JSException.typeError("addInitializer can only be called while the decorator runs");
            Object initializer = arg(arguments, 0);
            if (!Ops.isCallable(initializer)) throw JSException.typeError("Initializers must be functions");
            initializers.add((JSObject) initializer);
            return Undefined.INSTANCE;
        });
    }

    public static Object decorateClass(Object definition, Object decorators) {
        ClassDef def = (ClassDef) definition;
        Object value = def.ctor;
        JSArray array = decorators instanceof JSArray existing ? existing : null;
        if (array != null) {
            Realm realm = def.ctor.realm;
            PropertyDescriptor nameProperty = def.ctor.getOwnProperty("name");
            Object name = nameProperty != null && !nameProperty.isAccessor()
                    && nameProperty.value instanceof CharSequence text && !text.isEmpty()
                    ? nameProperty.value : Undefined.INSTANCE;
            for (int i = (int) array.length - 1; i >= 0; i--) {
                Object decorator = array.get(i, array);
                if (!Ops.isCallable(decorator)) throw JSException.typeError("Decorator is not a function");
                boolean[] finished = new boolean[1];
                JSObject context = new JSObject(realm.getObjectPrototype());
                context.createDataProperty("kind", "class");
                context.createDataProperty("name", name);
                context.createDataProperty("addInitializer", addInitializerFunction(realm, def.classExtraInitializers, finished));
                context.createDataProperty("metadata", def.getMetadata());
                Object result;
                try {
                    result = Ops.call(decorator, Undefined.INSTANCE, new Object[] {value, context});
                } finally {
                    finished[0] = true;
                }
                if (result != Undefined.INSTANCE) {
                    if (!Ops.isCallable(result)) throw JSException.typeError("Class decorators must return a function or undefined");
                    value = result;
                }
            }
        }
        boolean metadataRequired = array != null && array.length > 0;
        if (!metadataRequired) {
            for (ClassDef.Element element : def.elements) {
                if (decorated(element)) {
                    metadataRequired = true;
                    break;
                }
            }
        }
        if (metadataRequired) {
            ((JSObject) value).definePropertyOrThrow(JSSymbol.metadata, PropertyDescriptor.data(def.getMetadata(), Attr.ALL));
        }
        def.finalClass = value;
        return value;
    }

    public static void staticInit(Object definition) {
        ClassDef def = (ClassDef) definition;
        JSObject constructor = (JSObject) (def.finalClass == null ? def.ctor : def.finalClass);
        for (JSObject initializer : def.staticExtraInitializers) initializer.call(constructor, EMPTY_ARGS);
        for (ClassDef.Element element : def.elements) {
            if (!element.isStatic) continue;
            if (element.kind == ClassDef.FIELD || element.kind == ClassDef.ACCESSOR) Rt.defineField(constructor, fieldRecord(element));
            else if (element.kind == ClassDef.BLOCK) ((JSObject) element.fn).call(constructor, EMPTY_ARGS);
        }
        for (JSObject initializer : def.classExtraInitializers) initializer.call(constructor, EMPTY_ARGS);
    }
}
