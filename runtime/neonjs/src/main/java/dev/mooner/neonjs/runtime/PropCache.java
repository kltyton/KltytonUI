package dev.mooner.neonjs.runtime;

import java.util.ArrayList;
import static dev.mooner.neonjs.runtime.Values.EMPTY_ARGS;

/** Polymorphic property inline caches; derived from NeonJS and adapted to Java. */
public final class PropCache {
    public static final int DATA = 0;
    public static final int GETTER = 1;
    public static final int ABSENT = 2;
    public static final int STORE = 3;
    public static final int ADD = 4;
    public static final int SETTER = 5;
    private static final int MAX_POLY = 4;
    private static final int MAX_FILLS = 32;
    private static final int MAX_DEPTH = 8;
    private static final int SPECIAL_ACCESS = JSObject.SPECIAL_GET | JSObject.SPECIAL_SET | JSObject.SPECIAL_HAS;
    private static final Shape[] EMPTY_CHAIN = new Shape[0];

    private PropCache() {
    }

    public static Shape shapeOf(JSObject object) {
        PropertyMap map = object.props;
        return map == null ? Shape.emptyShapeOf(object) : map.shape;
    }

    private static JSObject walk(JSObject object, Shape[] chain) {
        JSObject holder = object;
        for (Shape expected : chain) {
            holder = holder.proto;
            if (holder == null) return null;
            PropertyMap map = holder.props;
            Shape shape = map == null ? Shape.emptyShapeOf(holder) : map.shape;
            if (shape != expected) return null;
        }
        return holder;
    }

    public static Object get(JSObject object, PropSite site) {
        PropertyMap map = object.props;
        Shape shape = map == null ? Shape.emptyShapeOf(object) : map.shape;
        for (PropIC entry = site.entry; entry != null; entry = entry.link) {
            if (entry.shape != shape) continue;
            Shape[] chain = entry.chain;
            if (chain == null) {
                Object value = map.values[entry.slot];
                return entry.kind == DATA ? value : callGetter(value, object);
            }
            JSObject holder = walk(object, chain);
            if (holder != null) {
                if (entry.kind == ABSENT) return Undefined.INSTANCE;
                Object value = holder.props.values[entry.slot];
                return entry.kind == DATA ? value : callGetter(value, object);
            }
        }
        return getMiss(object, site, shape);
    }

    private static Object callGetter(Object accessor, Object receiver) {
        Object getter = ((Accessor) accessor).getter;
        return getter instanceof JSObject function ? function.call(receiver, EMPTY_ARGS) : Undefined.INSTANCE;
    }

    private static Object getMiss(JSObject object, PropSite site, Shape shape) {
        Object key = site.key;
        if (object instanceof JSArray array && key.equals("length")) return Ops.num(array.length);
        if (shape != null && shape.cacheable && site.fills < MAX_FILLS) {
            site.fills++;
            PropIC entry = buildGet(object, key, shape);
            if (entry != null) install(site, entry);
        }
        return object.get(key, object);
    }

    private static boolean cacheableFor(JSObject holder, Object key, Shape shape) {
        return shape != null && shape.cacheable && (holder.special & SPECIAL_ACCESS) == 0
                && ((holder.special & JSObject.EXOTIC_OWN) == 0 || holder.icSafeKey(key));
    }

    private static PropIC buildGet(JSObject object, Object key, Shape receiverShape) {
        JSObject holder = object;
        Shape shape = receiverShape;
        ArrayList<Shape> chain = null;
        while (true) {
            if (!cacheableFor(holder, key, shape)) return null;
            PropertyMap map = holder.props;
            int slot = map == null ? -1 : map.find(key);
            if (slot >= 0) {
                int kind = (map.flags[slot] & Attr.ACCESSOR) != 0 ? GETTER : DATA;
                return new PropIC(receiverShape, chain == null ? null : chain.toArray(Shape[]::new),
                        slot, kind, null, null);
            }
            JSObject parent = holder.proto;
            if (parent == null) {
                return new PropIC(receiverShape, chain == null ? EMPTY_CHAIN : chain.toArray(Shape[]::new),
                        -1, ABSENT, null, null);
            }
            if (chain == null) chain = new ArrayList<>(4);
            if (chain.size() >= MAX_DEPTH) return null;
            shape = shapeOf(parent);
            if (shape == null) {
                parent.ensureProps();
                shape = parent.props.shape;
            }
            chain.add(shape);
            holder = parent;
        }
    }

    private static void install(PropSite site, PropIC entry) {
        ArrayList<PropIC> previous = new ArrayList<>(MAX_POLY);
        for (PropIC current = site.entry; current != null && previous.size() < MAX_POLY - 1; current = current.link) {
            if (current.shape != entry.shape) previous.add(current);
        }
        PropIC tail = null;
        for (int i = previous.size() - 1; i >= 0; i--) tail = previous.get(i).withLink(tail);
        site.entry = tail == null ? entry : entry.withLink(tail);
    }

    public static boolean put(JSObject object, PropSite site, Object value) {
        PropertyMap map = object.props;
        Shape shape = map == null ? Shape.emptyShapeOf(object) : map.shape;
        for (PropIC entry = site.entry; entry != null; entry = entry.link) {
            if (entry.shape != shape) continue;
            if (entry.kind == STORE) {
                map.values[entry.slot] = value;
                return true;
            }
            if (entry.kind == ADD && object.extensible) {
                Shape[] chain = entry.chain;
                if (chain == null || walk(object, chain) != null) {
                    object.ensureProps().addTransition(entry.next, value);
                    return true;
                }
            }
            if (entry.kind == SETTER) {
                JSObject holder = walk(object, entry.chain);
                if (holder != null) {
                    Object setter = ((Accessor) holder.props.values[entry.slot]).setter;
                    if (setter instanceof JSObject function) {
                        function.call(object, new Object[] {value});
                        return true;
                    }
                }
            }
        }
        return putMiss(object, site, value, shape);
    }

    private static boolean putMiss(JSObject object, PropSite site, Object value, Shape shape) {
        if (shape != null && shape.cacheable && site.fills < MAX_FILLS) {
            site.fills++;
            PropIC entry = buildPut(object, site.key, shape);
            if (entry != null) install(site, entry);
        }
        return object.set(site.key, value, object);
    }

    private static PropIC buildPut(JSObject object, Object key, Shape receiverShape) {
        if (!cacheableFor(object, key, receiverShape)) return null;
        PropertyMap map = object.props;
        int slot = map == null ? -1 : map.find(key);
        if (slot >= 0) {
            if ((map.flags[slot] & (Attr.ACCESSOR | Attr.WRITABLE)) != Attr.WRITABLE) return null;
            return new PropIC(receiverShape, null, slot, STORE, null, null);
        }
        if (!object.extensible || !receiverShape.shared) return null;
        JSObject holder = object;
        ArrayList<Shape> chain = new ArrayList<>(4);
        while (true) {
            JSObject parent = holder.proto;
            if (parent == null) break;
            if (chain.size() >= MAX_DEPTH) return null;
            Shape parentShape = shapeOf(parent);
            if (parentShape == null || !cacheableFor(parent, key, parentShape)) return null;
            chain.add(parentShape);
            PropertyMap parentMap = parent.props;
            int parentSlot = parentMap == null ? -1 : parentMap.find(key);
            if (parentSlot >= 0) {
                int attributes = parentMap.flags[parentSlot];
                if ((attributes & Attr.ACCESSOR) != 0) {
                    Object setter = ((Accessor) parentMap.values[parentSlot]).setter;
                    if (!(setter instanceof JSObject)) return null;
                    return new PropIC(receiverShape, chain.toArray(Shape[]::new), parentSlot, SETTER, null, null);
                }
                if ((attributes & Attr.WRITABLE) == 0) return null;
                break;
            }
            holder = parent;
        }
        Shape next = receiverShape.append(key, Attr.ALL);
        if (!next.shared) return null;
        return new PropIC(receiverShape, chain.isEmpty() ? null : chain.toArray(Shape[]::new), -1, ADD, next, null);
    }
}
