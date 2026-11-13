package dev.mooner.neonjs.runtime;

import dev.mooner.neonjs.compiler.ScopeInfo;
import dev.mooner.neonjs.vm.ImportRef;
import dev.mooner.neonjs.vm.Modules;
import java.util.LinkedHashMap;
import java.util.Set;

/** Dynamic binding resolution and access; derived from NeonJS and adapted to Java. */
public final class Names {
    private static final Set<String> PSEUDO = Set.of("this", "new.target", "%home", "%fn");

    private Names() {
    }

    public static NameRef resolve(Env environment, String name) {
        for (Env current = environment; current != null; current = current.parent) {
            if (current instanceof DeclEnv declared) {
                int index = declared.info.lookup(name);
                if (index >= 0) return new NameRef(declared, name, index, false);
                if (declared.extension != null && declared.extension.containsKey(name)) {
                    return new NameRef(declared, name, -1, false);
                }
            } else if (current instanceof ObjectEnv object) {
                if (!PSEUDO.contains(name) && hasObjectBinding(object, name)) {
                    return new NameRef(object, name, -1, false);
                }
            } else if (current instanceof GlobalEnv global) {
                if (PSEUDO.contains(name)) return new NameRef(global, name, -2, false);
                if (global.lexical.containsKey(name)) return new NameRef(global, name, -3, false);
                if (global.global.hasProperty(name)) return new NameRef(global, name, -4, false);
                return null;
            }
        }
        return null;
    }

    private static boolean hasObjectBinding(ObjectEnv environment, String name) {
        JSObject object = environment.obj;
        if (!object.hasProperty(name)) return false;
        if (!environment.isWith) return true;
        Object unscopables = object.get(JSSymbol.unscopables, object);
        return !(unscopables instanceof JSObject excluded && Ops.toBoolean(excluded.get(name, excluded)));
    }

    public static Object getValue(NameRef reference, String name, boolean strict, boolean forTypeof) {
        if (reference == null) {
            if (forTypeof) return Undefined.INSTANCE;
            throw JSException.referenceError(name + " is not defined");
        }
        Env environment = reference.env;
        if (environment instanceof DeclEnv declared) {
            Object value = reference.slot >= 0 ? declared.slots[reference.slot] : declared.extension.get(name);
            if (value == Uninitialized.INSTANCE) {
                throw JSException.referenceError("Cannot access '" + name + "' before initialization");
            }
            return value instanceof ImportRef imported ? Modules.deref(imported) : value;
        }
        if (environment instanceof ObjectEnv object) {
            if (!object.obj.hasProperty(name)) {
                if (!strict) return Undefined.INSTANCE;
                throw JSException.referenceError(name + " is not defined");
            }
            return object.obj.get(name, object.obj);
        }
        if (environment instanceof GlobalEnv global) {
            if (reference.slot == -2) return name.equals("this") ? global.thisValue : Undefined.INSTANCE;
            if (reference.slot == -3) {
                GlobalBinding binding = global.lexical.get(name);
                if (binding.value == Uninitialized.INSTANCE) {
                    throw JSException.referenceError("Cannot access '" + name + "' before initialization");
                }
                return binding.value;
            }
            if (!global.global.hasProperty(name)) {
                if (forTypeof) return Undefined.INSTANCE;
                throw JSException.referenceError(name + " is not defined");
            }
            return global.global.get(name, global.global);
        }
        throw new IllegalStateException();
    }

    public static Object load(Env environment, String name, boolean strict, boolean forTypeof) {
        return getValue(resolve(environment, name), name, strict, forTypeof);
    }

    public static void putValue(NameRef reference, String name, Object value, boolean strict, GlobalEnv globalEnv) {
        if (reference == null) {
            if (strict) throw JSException.referenceError(name + " is not defined");
            globalEnv.global.set(name, value, globalEnv.global);
            return;
        }
        Env environment = reference.env;
        if (environment instanceof DeclEnv declared) {
            if (reference.slot >= 0) {
                int flags = declared.info.flags[reference.slot];
                if (declared.slots[reference.slot] == Uninitialized.INSTANCE) {
                    throw JSException.referenceError("Cannot access '" + name + "' before initialization");
                }
                if ((flags & ScopeInfo.F_CONST) != 0) throw JSException.typeError("Assignment to constant variable.");
                if ((flags & ScopeInfo.F_CALLEE) != 0) {
                    if (strict) throw JSException.typeError("Assignment to constant variable.");
                    return;
                }
                declared.slots[reference.slot] = value;
            } else {
                LinkedHashMap<String, Object> extension = declared.extension;
                if (!extension.containsKey(name)) {
                    if (strict) throw JSException.referenceError(name + " is not defined");
                    globalEnv.global.set(name, value, globalEnv.global);
                    return;
                }
                extension.put(name, value);
            }
            return;
        }
        if (environment instanceof ObjectEnv object) {
            if (!object.obj.hasProperty(name) && strict) throw JSException.referenceError(name + " is not defined");
            if (!object.obj.set(name, value, object.obj) && strict) {
                throw JSException.typeError("Cannot assign to read only property '" + name + "'");
            }
            return;
        }
        if (environment instanceof GlobalEnv global) {
            if (reference.slot == -3) {
                GlobalBinding binding = global.lexical.get(name);
                if (binding.value == Uninitialized.INSTANCE) {
                    throw JSException.referenceError("Cannot access '" + name + "' before initialization");
                }
                if (!binding.mutable) throw JSException.typeError("Assignment to constant variable.");
                binding.value = value;
            } else {
                if (strict && !global.global.hasProperty(name)) throw JSException.referenceError(name + " is not defined");
                if (!global.global.set(name, value, global.global) && strict) {
                    throw JSException.typeError("Cannot assign to read only property '" + name + "' of object");
                }
            }
            return;
        }
        throw new IllegalStateException();
    }

    public static void store(Env environment, String name, Object value, boolean strict, GlobalEnv global) {
        putValue(resolve(environment, name), name, value, strict, global);
    }

    public static void initialize(Env environment, String name, Object value) {
        for (Env current = environment; current != null; current = current.parent) {
            if (current instanceof DeclEnv declared) {
                int index = declared.info.lookup(name);
                if (index >= 0) {
                    declared.slots[index] = value;
                    return;
                }
            } else if (current instanceof GlobalEnv global) {
                GlobalBinding binding = global.lexical.get(name);
                if (binding != null) binding.value = value;
                else global.global.set(name, value, global.global);
                return;
            }
        }
    }

    public static boolean delete(Env environment, String name) {
        NameRef reference = resolve(environment, name);
        if (reference == null) return true;
        if (reference.env instanceof DeclEnv declared) {
            if (reference.slot >= 0) return false;
            declared.extension.remove(name);
            return true;
        }
        if (reference.env instanceof ObjectEnv object) return object.obj.delete(name);
        if (reference.env instanceof GlobalEnv global) {
            if (reference.slot == -3 || reference.slot == -2) return false;
            boolean removed = global.global.delete(name);
            if (removed) global.varNames.remove(name);
            return removed;
        }
        return false;
    }

    public static CallTarget loadForCall(Env environment, String name, boolean strict) {
        NameRef reference = resolve(environment, name);
        Object value = getValue(reference, name, strict, false);
        Object receiver = reference != null && reference.env instanceof ObjectEnv object && object.isWith
                ? object.obj : Undefined.INSTANCE;
        return new CallTarget(value, receiver);
    }

    public record CallTarget(Object value, Object receiver) {
    }
}
