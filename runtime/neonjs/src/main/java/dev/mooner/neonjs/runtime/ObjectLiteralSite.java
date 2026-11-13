package dev.mooner.neonjs.runtime;

/**
 * Caches an object literal's property layout for its current context.
 * Derived from NeonJS and adapted to Java.
 */
public final class ObjectLiteralSite {
    public final Object[] keys;
    public PropertyMap.Layout layout;

    public ObjectLiteralSite(Object[] keys) {
        this.keys = keys;
    }

    public JSObject create(JSObject prototype, Object[] values) {
        JSObject object = new JSObject(prototype);
        Shape root = Shape.emptyShapeOf(object);
        PropertyMap.Layout current = layout;
        if (current == null || current.root != root) {
            current = PropertyMap.layoutOf(root, keys);
            if (current == null) {
                for (int i = 0; i < keys.length; i++) {
                    object.createDataProperty(keys[i], values[i]);
                }
                return object;
            }
            layout = current;
        }
        object.props = PropertyMap.withLayout(current, values);
        return object;
    }
}
