package dev.mooner.neonjs.runtime;

/** Function with an owning realm; derived from NeonJS and adapted to Java. */
public abstract class JSFunction extends JSObject {
    public final Realm realm;

    public JSFunction(Realm realm, JSObject prototype) {
        super(prototype);
        this.realm = realm;
        special |= CALLABLE;
    }

    @Override
    public String getClassName() {
        return "Function";
    }

    public String debugName() {
        PropertyMap map = props;
        if (map == null) return "";
        int entry = map.find("name");
        if (entry < 0 || (map.flags[entry] & Attr.ACCESSOR) != 0) return "";
        Object value = map.values[entry];
        return value instanceof CharSequence ? value.toString() : "";
    }

    public final void setFunctionName(Object key) {
        setFunctionName(key, null);
    }

    public final void setFunctionName(Object key, String prefix) {
        String name = PK.functionName(key);
        if (prefix != null) name = prefix + " " + name;
        defineOwn("name", name, Attr.CONFIGURABLE);
    }

    public String sourceText() {
        return "function " + debugName() + "() { [native code] }";
    }
}
