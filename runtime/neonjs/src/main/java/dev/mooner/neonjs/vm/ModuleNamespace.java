/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.DeclEnv;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Uninitialized;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Module namespace exotic object. A <em>deferred</em> namespace ({@code import defer * as ns}) evaluates its module
 * synchronously the first time a string-keyed property other than "then" is inspected.
 */
public final class ModuleNamespace extends JSObject {
    public final ModuleRecord module;
    private final List<String> exportList;
    public final boolean deferred;

    private final HashMap<String, Object> resolved = new HashMap<>();

    public ModuleNamespace(ModuleRecord module, List<String> exportList, boolean deferred) {
        super(null);
        this.module = module;
        this.exportList = exportList;
        this.deferred = deferred;
        special |= SPECIAL_ALL;
        extensible = false;
        defineOwn(JSSymbol.toStringTag, deferred ? "Deferred Module" : "Module", Attr.NONE);
    }

    @Override
    public String getClassName() {
        return "Module";
    }

    /** GetModuleExportsList: evaluates a deferred module first. */
    public List<String> getExports() {
        if (deferred) Modules.evaluateSync(module);
        return exportList;
    }

    /** Keys handled like an ordinary object (symbols; "then" on deferred namespaces, which are never thenables). */
    private boolean symbolLike(Object key) {
        return key instanceof JSSymbol || (deferred && "then".equals(key));
    }

    private Object binding(String name) {
        Object cached = resolved.get(name);
        if (cached != null) return cached;
        ResolvedBinding binding = (ResolvedBinding) module.resolveExport(name);
        Object value = Modules.bindingValueOrRefLazy(binding);
        resolved.put(name, value);
        return value;
    }

    private Object valueOf(String name) {
        Object binding = binding(name);
        if (binding instanceof ModuleNamespace namespace) return namespace;
        if (binding instanceof ResolvedBinding resolvedBinding) {
            DeclEnv environment = resolvedBinding.module.env;
            if (environment == null) {
                throw JSException.referenceError("Cannot access '" + name + "' before initialization");
            }
            Object value = environment.slots[resolvedBinding.module.slotOf(resolvedBinding.bindingName)];
            if (value == Uninitialized.INSTANCE) {
                throw JSException.referenceError("Cannot access '" + name + "' before initialization");
            }
            return Modules.deref(value);
        }
        return Modules.deref(binding);
    }

    @Override
    public PropertyDescriptor getOwnProperty(Object key) {
        if (symbolLike(key)) return ordinaryGetOwnProperty(key);
        String name = PK.toStringKey(key);
        if (!getExports().contains(name)) return null;
        return PropertyDescriptor.data(valueOf(name), Attr.WRITABLE | Attr.ENUMERABLE);
    }

    @Override
    public Object getOwnValue(Object key, Object receiver) {
        if (symbolLike(key)) return super.getOwnValue(key, receiver);
        String name = PK.toStringKey(key);
        if (!getExports().contains(name)) return NotFound.INSTANCE;
        return valueOf(name);
    }

    @Override
    public boolean hasOwnProperty(Object key) {
        return getOwnProperty(key) != null;
    }

    @Override
    public boolean hasProperty(Object key) {
        return symbolLike(key) ? super.hasOwnProperty(key) : getExports().contains(PK.toStringKey(key));
    }

    @Override
    public Object get(Object key, Object receiver) {
        if (symbolLike(key)) {
            PropertyDescriptor descriptor = ordinaryGetOwnProperty(key);
            return descriptor == null ? Undefined.INSTANCE : descriptor.value;
        }
        String name = PK.toStringKey(key);
        if (!getExports().contains(name)) return Undefined.INSTANCE;
        return valueOf(name);
    }

    @Override
    public boolean set(Object key, Object value, Object receiver) {
        return false;
    }

    @Override
    public boolean defineOwnProperty(Object key, PropertyDescriptor descriptor) {
        if (symbolLike(key)) return ordinaryDefineOwnProperty(key, descriptor);
        PropertyDescriptor current = getOwnProperty(key);
        if (current == null) return false;
        if (descriptor.getHasConfigurable() && descriptor.configurable) return false;
        if (descriptor.getHasEnumerable() && !descriptor.enumerable) return false;
        if (descriptor.isAccessor()) return false;
        if (descriptor.getHasWritable() && !descriptor.writable) return false;
        if (descriptor.getHasValue()) return Ops.sameValue(descriptor.value, current.value);
        return true;
    }

    @Override
    public boolean delete(Object key) {
        if (symbolLike(key)) return super.delete(key);
        return !getExports().contains(PK.toStringKey(key));
    }

    @Override
    public List<Object> ownPropertyKeys() {
        List<Object> out = new ArrayList<>();
        for (String name : getExports()) out.add(PK.fromString(name));
        out.add(JSSymbol.toStringTag);
        return out;
    }

    @Override
    public JSObject getPrototypeOf() {
        return null;
    }

    @Override
    public boolean setPrototypeOf(JSObject prototype) {
        return prototype == null;
    }

    @Override
    public boolean isExtensible() {
        return false;
    }

    @Override
    public boolean preventExtensions() {
        return true;
    }
}
