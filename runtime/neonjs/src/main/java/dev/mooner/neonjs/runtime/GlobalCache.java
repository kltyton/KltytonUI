package dev.mooner.neonjs.runtime;

import dev.mooner.neonjs.vm.Rt;

/** Global binding inline caches; derived from NeonJS and adapted to Java. */
public final class GlobalCache {
    private static final int MAX_FILLS = 32;

    private GlobalCache() {
    }

    public static Object load(Realm realm, GlobalSite site, boolean forTypeof) {
        GlobalEnv environment = realm.getGlobalEnv();
        GlobalIC cache = site.cache;
        if (cache != null) {
            if (cache.shape == null) {
                if (cache.token == environment.token) {
                    Object value = environment.lexicalList.get(cache.index).value;
                    if (value != Uninitialized.INSTANCE) return value;
                }
            } else {
                PropertyMap map = environment.global.props;
                if (map != null && map.shape == cache.shape && environment.lexicalEpoch == cache.epoch) {
                    return map.values[cache.slot];
                }
            }
        }
        fill(environment, site, false);
        return Rt.loadGlobal(realm, site.name, forTypeof);
    }

    public static void store(Realm realm, GlobalSite site, Object value, boolean strict) {
        GlobalEnv environment = realm.getGlobalEnv();
        GlobalIC cache = site.cache;
        if (cache != null) {
            if (cache.shape == null) {
                if (cache.token == environment.token) {
                    GlobalBinding binding = environment.lexicalList.get(cache.index);
                    if (binding.mutable && binding.value != Uninitialized.INSTANCE) {
                        binding.value = value;
                        return;
                    }
                }
            } else {
                PropertyMap map = environment.global.props;
                if (map != null && map.shape == cache.shape && environment.lexicalEpoch == cache.epoch) {
                    map.values[cache.slot] = value;
                    return;
                }
            }
        }
        fill(environment, site, true);
        Rt.storeGlobal(realm, site.name, value, strict);
    }

    private static void fill(GlobalEnv environment, GlobalSite site, boolean forStore) {
        if (site.fills >= MAX_FILLS) return;
        site.fills++;
        GlobalBinding binding = environment.lexical.get(site.name);
        if (binding != null) {
            int index = environment.lexicalList.indexOf(binding);
            if (index >= 0) site.cache = new GlobalIC(environment.token, index, null, -1, 0);
            return;
        }
        JSObject global = environment.global;
        if ((global.special & JSObject.SPECIAL_ALL) != 0) return;
        PropertyMap map = global.props;
        if (map == null || !map.shape.cacheable) return;
        int slot = map.find(site.name);
        if (slot < 0 || (map.flags[slot] & Attr.ACCESSOR) != 0) return;
        if (forStore && (map.flags[slot] & Attr.WRITABLE) == 0) return;
        site.cache = new GlobalIC(null, -1, map.shape, slot, environment.lexicalEpoch);
    }
}
