/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.compiler.ExportEntry;
import dev.mooner.neonjs.compiler.ScopeInfo;
import dev.mooner.neonjs.compiler.ScopeKind;
import dev.mooner.neonjs.runtime.DeclEnv;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Realm;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Synthetic module with fixed exports (JSON modules: a single default export; host-defined modules). */
public final class SyntheticModule extends ModuleRecord {
    /** Set for host modules with a source-phase representation (see {@link ModuleSource#sourceClassName}). */
    public String sourceClassName;

    private final Map<String, Integer> slots;
    private final ScopeInfo info;
    private final List<ExportEntry> exports;

    public SyntheticModule(Realm realm, String key, List<String> names, Object[] values) {
        super(realm, key);
        this.slots = new HashMap<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            slots.put(names.get(i), i);
        }
        this.info = new ScopeInfo(names.toArray(new String[0]), new int[names.size()], ScopeKind.MODULE);
        this.exports = new ArrayList<>(names.size());
        for (String name : names) {
            exports.add(new ExportEntry(name, -1, null, name));
        }
        env = new DeclEnv(realm.getGlobalEnv(), info, values);
    }

    public SyntheticModule(Realm realm, String key, Object value) {
        this(realm, key, List.of("default"), new Object[] {value});
    }

    @Override
    public JSObject getModuleSource() {
        String className = sourceClassName;
        if (className == null) return super.getModuleSource();
        if (moduleSource != null) return moduleSource;
        // like WebAssembly.Module.prototype: a per-class prototype inheriting from %AbstractModuleSource%.prototype
        String protoKey = "%ModuleSource:" + className + ".prototype%";
        JSObject proto = realm.intrinsics.computeIfAbsent(protoKey,
                key -> new JSObject(realm.intrinsics.get("%AbstractModuleSource.prototype%")));
        JSModuleSource object = new JSModuleSource(proto, className);
        moduleSource = object;
        return object;
    }

    @Override
    public List<ExportEntry> getLocalExports() {
        return exports;
    }

    @Override
    public int slotOf(String name) {
        Integer slot = slots.get(name);
        return slot == null ? -1 : slot;
    }
}
