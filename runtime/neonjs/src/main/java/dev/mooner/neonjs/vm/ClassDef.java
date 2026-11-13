package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Undefined;
import java.util.ArrayList;

/** Decorated class definition state; derived from NeonJS and adapted to Java. */
public final class ClassDef {
    public static final int METHOD = 0;
    public static final int GETTER = 1;
    public static final int SETTER = 2;
    public static final int FIELD = 3;
    public static final int ACCESSOR = 4;
    public static final int BLOCK = 5;
    public static final int STATIC = 8;
    public final JSClosure ctor;
    public final JSObject proto;
    public final ArrayList<Element> elements = new ArrayList<>();
    public final ArrayList<JSObject> staticExtraInitializers = new ArrayList<>();
    public final ArrayList<JSObject> instanceExtraInitializers = new ArrayList<>();
    public final ArrayList<JSObject> classExtraInitializers = new ArrayList<>();
    public Object finalClass;
    private JSObject metadata;

    public ClassDef(JSClosure constructor, JSObject prototype) {
        ctor = constructor;
        proto = prototype;
    }

    public JSObject getMetadata() {
        if (metadata != null) return metadata;
        JSObject superclass = ctor.code.isDerived() && ctor.proto != ctor.realm.getFunctionPrototype() ? ctor.proto : null;
        Object inherited = superclass == null ? null : superclass.get(JSSymbol.metadata, superclass);
        JSObject parent;
        if (inherited == null || inherited == Undefined.INSTANCE || inherited == Null.INSTANCE) parent = null;
        else if (inherited instanceof JSObject object) parent = object;
        else throw JSException.typeError("The superclass's Symbol.metadata is not an object");
        metadata = new JSObject(parent);
        return metadata;
    }

    public static final class Element {
        public final int kind;
        public final boolean isStatic;
        public final Object key;
        public Object fn;
        public final Object[] decorators;
        public Object getter;
        public Object setter;
        public PrivateName storage;
        public ArrayList<JSObject> initializers;
        public ArrayList<JSObject> extraInitializers;

        public Element(int kind, boolean isStatic, Object key, Object function, Object[] decorators) {
            this.kind = kind;
            this.isStatic = isStatic;
            this.key = key;
            fn = function;
            this.decorators = decorators;
        }
    }
}
