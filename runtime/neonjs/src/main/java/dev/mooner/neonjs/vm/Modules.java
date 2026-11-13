/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.compiler.Compiler;
import dev.mooner.neonjs.compiler.ExportEntry;
import dev.mooner.neonjs.compiler.ImportEntry;
import dev.mooner.neonjs.compiler.ModuleRequest;
import dev.mooner.neonjs.compiler.Source;
import dev.mooner.neonjs.parser.JSSyntaxError;
import dev.mooner.neonjs.parser.ParseOptions;
import dev.mooner.neonjs.parser.Parser;
import dev.mooner.neonjs.parser.Program;
import dev.mooner.neonjs.runtime.DeclEnv;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.TerminationException;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Uninitialized;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Module graph loading, linking and evaluation (ECMA-262 16.2.1.5). */
public final class Modules {
    private static final String MODULE_MAP_KEY = "%ModuleMap%";
    private static final String HOST_MODULES_KEY = "%HostModules%";
    private static final String HOST_MODULE_OBJECTS_KEY = "%HostModuleObjects%";
    private static final String BUILTINS_KEY = "%Builtins%";
    private static final String MODULE_LOADER_KEY = "%ModuleLoader%";

    private static long asyncOrderCounter;

    /** Module types accepted in {@code with { type: ... }} (JS modules have no type attribute). */
    private static final Set<String> SUPPORTED_TYPES = Set.of("json", "text", "bytes");

    private Modules() {
    }

    public static Object deref(Object value) {
        if (value instanceof ImportRef reference) {
            Object target = reference.env.slots[reference.slot];
            if (target == Uninitialized.INSTANCE) {
                throw JSException.referenceError("Cannot access '" + reference.name + "' before initialization");
            }
            return target;
        }
        return value;
    }

    public static Object importRef(ResolvedBinding binding) {
        DeclEnv environment = binding.module.env;
        if (environment == null) return binding;
        return new ImportRef(environment, binding.module.slotOf(binding.bindingName), binding.bindingName);
    }

    public static Object importRefLazy(ResolvedBinding binding) {
        return binding.module.env == null ? binding : importRef(binding);
    }

    /** Value (namespace / module source) or live reference for an import binding resolved to {@code binding}. */
    public static Object bindingValueOrRef(ResolvedBinding binding) {
        if (binding.bindingName == null) return namespaceOf(binding.module, binding.deferred);
        if (ImportEntry.MODULE_SOURCE.equals(binding.bindingName)) return binding.module.getModuleSource();
        return importRef(binding);
    }

    public static Object bindingValueOrRefLazy(ResolvedBinding binding) {
        if (binding.bindingName == null) return namespaceOf(binding.module, binding.deferred);
        if (ImportEntry.MODULE_SOURCE.equals(binding.bindingName)) return binding.module.getModuleSource();
        return importRefLazy(binding);
    }

    public static ModuleNamespace namespaceOf(ModuleRecord module) {
        return namespaceOf(module, false);
    }

    public static ModuleNamespace namespaceOf(ModuleRecord module, boolean deferred) {
        ModuleNamespace existing = deferred ? module.deferredNamespace : module.namespace;
        if (existing != null) return existing;
        List<String> names = new ArrayList<>();
        for (String name : module.getExportedNames()) {
            Object resolved = module.resolveExport(name);
            if (resolved instanceof ResolvedBinding) names.add(name);
        }
        names.sort(Modules::compareUtf16);
        ModuleNamespace namespace = new ModuleNamespace(module, names, deferred);
        if (deferred) {
            module.deferredNamespace = namespace;
        } else {
            module.namespace = namespace;
        }
        return namespace;
    }

    // ------------------------------------------------------------------ deferred evaluation (import defer)

    /**
     * GatherAsynchronousTransitiveDependencies: the modules a deferred import of {@code module} must still evaluate
     * eagerly - the nearest modules with top-level await (or still-running asynchronous cycles) in its
     * evaluation-phase graph.
     */
    public static List<ModuleRecord> gatherAsyncDependencies(ModuleRecord module) {
        return gatherAsyncDependencies(module, new HashSet<>(), new ArrayList<>());
    }

    public static List<ModuleRecord> gatherAsyncDependencies(ModuleRecord module, Set<ModuleRecord> seen,
                                                             List<ModuleRecord> out) {
        if (!seen.add(module)) return out;
        switch (module.status) {
            case EVALUATING:
                return out;
            case EVALUATING_ASYNC:
            case EVALUATED:
                ModuleRecord root = module.cycleRoot != null ? module.cycleRoot : module;
                if (root.asyncEvaluation && !root.hasError && !out.contains(module)) out.add(module);
                return out;
            default:
                break;
        }
        if (module.hasTLA) {
            if (!out.contains(module)) out.add(module);
            return out;
        }
        List<ModuleRequest> requests = module.getRequests();
        for (int i = 0; i < requests.size(); i++) {
            if (module.phaseOf(i) != null) continue;
            gatherAsyncDependencies(module.imported(i), seen, out);
        }
        return out;
    }

    /** ReadyForSyncExecution */
    private static boolean readyForSyncExecution(ModuleRecord module, Set<ModuleRecord> seen) {
        if (!seen.add(module)) return true;
        if (module.status == ModuleRecord.Status.EVALUATED) {
            ModuleRecord root = module.cycleRoot != null ? module.cycleRoot : module;
            return root.status == ModuleRecord.Status.EVALUATED;
        }
        if (module.status == ModuleRecord.Status.EVALUATING
                || module.status == ModuleRecord.Status.EVALUATING_ASYNC) {
            return false;
        }
        if (module.hasTLA) return false;
        // deferred dependencies count too: evaluating module evaluates their asynchronous parts
        List<ModuleRequest> requests = module.getRequests();
        for (int i = 0; i < requests.size(); i++) {
            if (ModuleRequest.PHASE_SOURCE.equals(module.phaseOf(i))) continue;
            if (!readyForSyncExecution(module.imported(i), seen)) return false;
        }
        return true;
    }

    /** EvaluateModuleSync: evaluation triggered by a deferred namespace access. */
    public static void evaluateSync(ModuleRecord module) {
        if (module.status == ModuleRecord.Status.EVALUATED && !module.hasError
                && (module.cycleRoot != null ? module.cycleRoot : module).status
                        == ModuleRecord.Status.EVALUATED) {
            return;
        }
        if (!readyForSyncExecution(module, new HashSet<>())) {
            throw JSException.typeError("Deferred module '" + module.key
                    + "' cannot be evaluated synchronously now (it is evaluating or depends on top-level await)");
        }
        JSPromise promise = evaluate(module);
        switch (promise.state) {
            case JSPromise.REJECTED:
                promise.isHandled = true;
                throw new JSException(promise.result);
            case JSPromise.PENDING:
                throw JSException.typeError("Deferred module '" + module.key
                        + "' did not evaluate synchronously");
            default:
                break;
        }
    }

    private static int compareUtf16(String first, String second) {
        return first.compareTo(second);
    }

    // ------------------------------------------------------------------ loading

    /** Per-realm module map (key -&gt; record). */
    @SuppressWarnings("unchecked")
    public static HashMap<String, ModuleRecord> moduleMap(Realm realm) {
        Object stored = realm.intrinsicsAny.get(MODULE_MAP_KEY);
        if (stored != null) return (HashMap<String, ModuleRecord>) stored;
        HashMap<String, ModuleRecord> map = new HashMap<>();
        realm.intrinsicsAny.put(MODULE_MAP_KEY, map);
        return map;
    }

    // ------------------------------------------------------------------ host modules

    @SuppressWarnings("unchecked")
    private static HashMap<String, Map<String, Object>> hostModules(Realm realm) {
        Object stored = realm.intrinsicsAny.get(HOST_MODULES_KEY);
        if (stored != null) return (HashMap<String, Map<String, Object>>) stored;
        HashMap<String, Map<String, Object>> map = new HashMap<>();
        realm.intrinsicsAny.put(HOST_MODULES_KEY, map);
        return map;
    }

    /** Defines a host module ({@code NeonContext.defineModule}): {@code exports} are JS values of {@code realm}. */
    public static void defineHostModule(Realm realm, String specifier, Map<String, Object> exports) {
        hostModules(realm).put(specifier, exports);
        Object objects = realm.intrinsicsAny.get(HOST_MODULE_OBJECTS_KEY);
        if (objects instanceof HashMap<?, ?> cache) cache.remove(specifier);
    }

    /** The exports of the host module {@code specifier}, or null. */
    public static Map<String, Object> hostModule(Realm realm, String specifier) {
        Object stored = realm.intrinsicsAny.get(HOST_MODULES_KEY);
        if (!(stored instanceof Map<?, ?> map)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> exports = (Map<String, Object>) map.get(specifier);
        return exports;
    }

    /** What {@code require} gives for the host module {@code specifier}: an object with its exports as properties. */
    public static JSObject hostModuleObject(Realm realm, String specifier) {
        Map<String, Object> exports = hostModule(realm, specifier);
        if (exports == null) return null;
        HashMap<String, JSObject> cache = hostModuleObjects(realm);
        return cache.computeIfAbsent(specifier, key -> {
            JSObject object = new JSObject(realm.getObjectPrototype());
            for (Map.Entry<String, Object> entry : exports.entrySet()) {
                object.createDataProperty(PK.fromString(entry.getKey()), entry.getValue());
            }
            return object;
        });
    }

    @SuppressWarnings("unchecked")
    private static HashMap<String, JSObject> hostModuleObjects(Realm realm) {
        Object stored = realm.intrinsicsAny.get(HOST_MODULE_OBJECTS_KEY);
        if (stored != null) return (HashMap<String, JSObject>) stored;
        HashMap<String, JSObject> map = new HashMap<>();
        realm.intrinsicsAny.put(HOST_MODULE_OBJECTS_KEY, map);
        return map;
    }

    // ------------------------------------------------------------------ built-in modules

    /**
     * A module that an engine module provides ({@code node:events}), under its canonical {@code name}: {@code create}
     * makes its exports (what {@code require} returns) the first time the realm imports or requires it.
     */
    public static final class Builtin {
        public final String name;
        private final Supplier<Object> create;
        private int state;
        private Object value;

        public Builtin(String name, Supplier<Object> create) {
            this.name = name;
            this.create = create;
        }

        /** Whether the module was made (a script required or imported it), without making it. */
        public boolean isLoaded() {
            return state == 2;
        }

        public Object exports() {
            if (state == 2) return value;
            if (state == 1) {
                throw JSException.typeError("Module '" + name + "' requires itself while it is being created");
            }
            state = 1;
            try {
                value = create.get();
                state = 2;
            } finally {
                if (state == 1) state = 0;
            }
            return value;
        }

        public String getName() {
            return name;
        }
    }

    @SuppressWarnings("unchecked")
    private static HashMap<String, Builtin> builtins(Realm realm) {
        Object stored = realm.intrinsicsAny.get(BUILTINS_KEY);
        if (stored != null) return (HashMap<String, Builtin>) stored;
        HashMap<String, Builtin> map = new HashMap<>();
        realm.intrinsicsAny.put(BUILTINS_KEY, map);
        return map;
    }

    /** Registers {@code builtin} in {@code realm} under its name and {@code aliases} ({@code events} for node:events). */
    public static void defineBuiltin(Realm realm, Builtin builtin, String... aliases) {
        HashMap<String, Builtin> map = builtins(realm);
        map.put(builtin.name, builtin);
        for (String alias : aliases) map.put(alias, builtin);
    }

    /** The built-in module {@code specifier} names in {@code realm}, or null. */
    public static Builtin builtin(Realm realm, String specifier) {
        Object stored = realm.intrinsicsAny.get(BUILTINS_KEY);
        if (!(stored instanceof Map<?, ?> map)) return null;
        return (Builtin) map.get(specifier);
    }

    public static boolean hasBuiltins(Realm realm) {
        Object stored = realm.intrinsicsAny.get(BUILTINS_KEY);
        return stored instanceof Map<?, ?> map && !map.isEmpty();
    }

    /** The canonical names of the realm's built-in modules, sorted. */
    public static List<String> builtinNames(Realm realm) {
        Object stored = realm.intrinsicsAny.get(BUILTINS_KEY);
        if (!(stored instanceof Map<?, ?> map)) return Collections.emptyList();
        Set<String> distinct = new LinkedHashSet<>();
        for (Object value : map.values()) {
            distinct.add(((Builtin) value).name);
        }
        List<String> names = new ArrayList<>(distinct);
        Collections.sort(names);
        return names;
    }

    /**
     * The ES module face of {@code builtin}: {@code default} is its exports, and the exports' own enumerable
     * string-keyed properties are named exports too (as Node's built-in modules have it). Bound to {@code realm}.
     */
    public static ModuleSource builtinSource(Realm realm, Builtin builtin) {
        Object exports = builtin.exports();
        LinkedHashMap<String, Object> named = new LinkedHashMap<>();
        named.put("default", exports);
        if (exports instanceof JSObject object) {
            for (Object key : object.ownPropertyKeys()) {
                if (key instanceof JSSymbol) continue;
                String name = PK.toStringKey(key);
                if ("default".equals(name)) continue;
                PropertyDescriptor descriptor = object.getOwnProperty(key);
                if (descriptor == null) continue;
                if (descriptor.enumerable) named.put(name, object.get(key, object));
            }
        }
        ModuleSource source = new ModuleSource(builtin.name, "");
        source.hostExports = named;
        source.hostRealm = realm;
        return source;
    }

    public static ModuleLoader loaderOf(Realm realm) {
        Object loader = realm.intrinsicsAny.get(MODULE_LOADER_KEY);
        if (loader == null) throw JSException.typeError("No module loader configured");
        return (ModuleLoader) loader;
    }

    public static void setLoader(Realm realm, ModuleLoader loader) {
        realm.intrinsicsAny.put(MODULE_LOADER_KEY, loader);
    }

    /** Parses and creates a module record for source; throws a JS SyntaxError on parse errors. */
    public static ModuleRecord parseModule(Realm realm, ModuleSource source) {
        if (source.hostExports != null) {
            List<String> names = new ArrayList<>(source.hostExports.keySet());
            Object[] values = new Object[names.size()];
            for (int i = 0; i < names.size(); i++) {
                values[i] = source.hostExports.get(names.get(i));
            }
            SyntheticModule module = new SyntheticModule(realm, source.key, names, values);
            module.sourceClassName = source.sourceClassName;
            return module;
        }
        if ("json".equals(source.type)) {
            Object value = new dev.mooner.neonjs.builtins.JSONBuiltins.JsonParser(realm, source.text)
                    .parseRoot().value;
            return new SyntheticModule(realm, source.key, value);
        }
        if ("text".equals(source.type)) return new SyntheticModule(realm, source.key, source.text);
        if ("bytes".equals(source.type)) {
            byte[] bytes = source.bytes != null ? source.bytes : source.text.getBytes(StandardCharsets.UTF_8);
            return new SyntheticModule(realm, source.key,
                    dev.mooner.neonjs.builtins.BufferOps.immutableBytes(realm, bytes));
        }
        Program program;
        try {
            program = Parser.parse(source.text, new ParseOptions(true, false, false, false, false, false, true,
                    false, Collections.emptySet(), source.key, false, true));
        } catch (JSSyntaxError error) {
            throw Evaluator.syntaxError(realm, error);
        }
        return new SourceTextModule(realm, source.key,
                Compiler.compileModule(program, new Source(source.key, source.text)));
    }

    /** Loads {@code root} and all its dependencies (synchronously) into the realm's module map. */
    public static void loadGraph(Realm realm, ModuleRecord root) {
        HashMap<String, ModuleRecord> map = moduleMap(realm);
        map.putIfAbsent(root.key, root);
        // only needed for imports: a self-contained module evaluates without a loader
        ModuleLoader[] loader = new ModuleLoader[1];
        ArrayDeque<ModuleRecord> stack = new ArrayDeque<>();
        Set<ModuleRecord> seen = new HashSet<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            ModuleRecord module = stack.removeLast();
            if (!seen.add(module)) continue;
            List<ModuleRequest> requests = module.getRequests();
            for (int i = 0; i < requests.size(); i++) {
                ModuleRequest request = requests.get(i);
                if (module.loaded.containsKey(i)) continue;
                if (loader[0] == null) loader[0] = loaderOf(realm);
                ModuleRecord dependency = loadOne(realm, loader[0], map, request, module.key);
                module.loaded.put(i, dependency);
                // a source-phase import only needs the module itself, not its graph
                if (!ModuleRequest.PHASE_SOURCE.equals(request.phase)) stack.add(dependency);
            }
        }
    }

    private static ModuleRecord loadOne(Realm realm, ModuleLoader loader, HashMap<String, ModuleRecord> map,
                                        ModuleRequest request, String referrer) {
        for (Map.Entry<String, String> attribute : request.attributes) {
            if (!"type".equals(attribute.getKey())) {
                throw JSException.syntaxError("Unsupported import attribute '" + attribute.getKey() + "'");
            }
        }
        String type = request.getType();
        if (type != null && !SUPPORTED_TYPES.contains(type)) {
            throw JSException.typeError("Unsupported module type '" + type + "'");
        }
        String key = loader.resolve(request.specifier, referrer);
        // the module map is keyed by (resolved specifier, module type): a file may be both JS and text
        String mapKey = type == null ? key : key + "\u0000" + type;
        ModuleRecord existing = map.get(mapKey);
        if (existing != null) return existing;
        ModuleSource source;
        try {
            source = loader.load(key, request);
        } catch (JSException error) {
            throw error;
        } catch (Exception error) {
            throw JSException.typeError("Cannot load module '" + request.specifier + "': " + error.getMessage());
        }
        if (source.hostExports != null && source.hostRealm != null && source.hostRealm != realm) {
            throw JSException.typeError("Host module '" + request.specifier
                    + "' is not available in this realm");
        }
        ModuleRecord record;
        if (type != null && source.hostExports == null) {
            ModuleSource typed = new ModuleSource(source.key, source.text, type);
            typed.bytes = source.bytes;
            record = parseModule(realm, typed);
        } else {
            record = parseModule(realm, source);
        }
        map.put(mapKey, record);
        return record;
    }

    // ------------------------------------------------------------------ linking

    public static void link(ModuleRecord module) {
        List<ModuleRecord> stack = new ArrayList<>();
        try {
            innerLink(module, stack, 0);
        } catch (RuntimeException | Error error) {
            for (ModuleRecord entry : stack) entry.status = ModuleRecord.Status.UNLINKED;
            module.status = ModuleRecord.Status.UNLINKED;
            throw error;
        }
    }

    private static int innerLink(ModuleRecord module, List<ModuleRecord> stack, int index0) {
        int index = index0;
        switch (module.status) {
            case LINKING:
            case LINKED:
            case EVALUATING_ASYNC:
            case EVALUATED:
                return index;
            default:
                break;
        }
        module.status = ModuleRecord.Status.LINKING;
        module.dfsIndex = index;
        module.dfsAncestorIndex = index;
        index++;
        stack.add(module);
        List<ModuleRequest> requests = module.getRequests();
        for (int i = 0; i < requests.size(); i++) {
            if (ModuleRequest.PHASE_SOURCE.equals(module.phaseOf(i))) continue;
            ModuleRecord dependency = module.imported(i);
            index = innerLink(dependency, stack, index);
            if (dependency.status == ModuleRecord.Status.LINKING) {
                module.dfsAncestorIndex = Math.min(module.dfsAncestorIndex, dependency.dfsAncestorIndex);
            }
        }
        module.initializeEnvironment();
        if (module.dfsAncestorIndex == module.dfsIndex) {
            while (true) {
                ModuleRecord entry = stack.remove(stack.size() - 1);
                entry.status = ModuleRecord.Status.LINKED;
                if (entry == module) break;
            }
        }
        return index;
    }

    // ------------------------------------------------------------------ evaluation

    /** Evaluate(): returns the top-level promise. */
    public static JSPromise evaluate(ModuleRecord module0) {
        ModuleRecord module = module0;
        Realm realm = module.realm;
        if (module.status == ModuleRecord.Status.EVALUATING_ASYNC
                || module.status == ModuleRecord.Status.EVALUATED) {
            module = module.cycleRoot != null ? module.cycleRoot : module;
        }
        if (module.topLevelCapability != null) return (JSPromise) module.topLevelCapability.promise;
        List<ModuleRecord> stack = new ArrayList<>();
        PromiseCapability capability = Promises.newPromiseCapability(realm, realm.getPromiseConstructor());
        module.topLevelCapability = capability;
        try {
            innerEvaluate(module, stack, 0);
            if (!module.asyncEvaluation) {
                Ops.call(capability.resolve, Undefined.INSTANCE, new Object[] {Undefined.INSTANCE});
            }
        } catch (Exception | StackOverflowError | OutOfMemoryError throwable) {
            if (throwable instanceof TerminationException termination) throw termination;
            Object value = throwable instanceof JSException error
                    ? error.value : Rt.catchValue(throwable, realm, null, 0);
            for (ModuleRecord entry : stack) {
                entry.status = ModuleRecord.Status.EVALUATED;
                entry.hasError = true;
                entry.evaluationError = value;
            }
            module.status = ModuleRecord.Status.EVALUATED;
            if (!module.hasError) {
                module.hasError = true;
                module.evaluationError = value;
            }
            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {value});
        }
        return (JSPromise) capability.promise;
    }

    private static int innerEvaluate(ModuleRecord module, List<ModuleRecord> stack, int index0) {
        int index = index0;
        if (module.status == ModuleRecord.Status.EVALUATING_ASYNC
                || module.status == ModuleRecord.Status.EVALUATED) {
            if (!module.hasError) return index;
            throw new JSException(module.evaluationError);
        }
        if (module.status == ModuleRecord.Status.EVALUATING) return index;
        module.status = ModuleRecord.Status.EVALUATING;
        module.dfsIndex = index;
        module.dfsAncestorIndex = index;
        module.pendingAsyncDependencies = 0;
        index++;
        stack.add(module);
        List<ModuleRequest> requests = module.getRequests();
        for (int i = 0; i < requests.size(); i++) {
            String phase = module.phaseOf(i);
            List<ModuleRecord> dependencies;
            if (ModuleRequest.PHASE_SOURCE.equals(phase)) {
                continue;
            } else if (ModuleRequest.PHASE_DEFER.equals(phase)) {
                // deferred: only the asynchronous parts of its graph are evaluated now
                dependencies = gatherAsyncDependencies(module.imported(i));
            } else {
                dependencies = List.of(module.imported(i));
            }
            for (ModuleRecord dependency : dependencies) {
                ModuleRecord resolved = dependency;
                index = innerEvaluate(resolved, stack, index);
                if (resolved.status == ModuleRecord.Status.EVALUATING) {
                    module.dfsAncestorIndex = Math.min(module.dfsAncestorIndex,
                            resolved.dfsAncestorIndex);
                } else {
                    resolved = resolved.cycleRoot != null ? resolved.cycleRoot : resolved;
                    if (resolved.hasError) throw new JSException(resolved.evaluationError);
                }
                if (resolved.asyncEvaluation && !resolved.asyncParentModules.contains(module)) {
                    module.pendingAsyncDependencies++;
                    resolved.asyncParentModules.add(module);
                }
            }
        }
        if (module.pendingAsyncDependencies > 0 || module.hasTLA) {
            module.asyncEvaluation = true;
            module.asyncEvaluationOrder = ++asyncOrderCounter;
            if (module.pendingAsyncDependencies == 0) executeAsync(module);
        } else {
            module.executeModule(null);
        }
        if (module.dfsAncestorIndex == module.dfsIndex) {
            while (true) {
                ModuleRecord entry = stack.remove(stack.size() - 1);
                entry.status = !entry.asyncEvaluation
                        ? ModuleRecord.Status.EVALUATED : ModuleRecord.Status.EVALUATING_ASYNC;
                entry.cycleRoot = module;
                if (entry == module) break;
            }
        }
        return index;
    }

    private static void executeAsync(ModuleRecord module) {
        Realm realm = module.realm;
        JSPromise promise = Promises.newPromise(realm);
        Promises.ResolvingFunctions functions = Promises.createResolvingFunctions(realm, promise);
        Promises.thenHost(realm, promise,
                value -> {
                    asyncFulfilled(module);
                    return Undefined.INSTANCE;
                },
                error -> {
                    asyncRejected(module, error);
                    return Undefined.INSTANCE;
                });
        module.executeModule(new PromiseCapability(promise, functions.resolve(), functions.reject()));
    }

    private static void gatherAncestors(ModuleRecord module, List<ModuleRecord> execList) {
        for (ModuleRecord parent : module.asyncParentModules) {
            if (!execList.contains(parent)
                    && !(parent.cycleRoot != null ? parent.cycleRoot : parent).hasError) {
                parent.pendingAsyncDependencies--;
                if (parent.pendingAsyncDependencies == 0) {
                    execList.add(parent);
                    if (!parent.hasTLA) gatherAncestors(parent, execList);
                }
            }
        }
    }

    private static void asyncFulfilled(ModuleRecord module) {
        if (module.status == ModuleRecord.Status.EVALUATED) return;
        module.asyncEvaluation = false;
        module.status = ModuleRecord.Status.EVALUATED;
        if (module.topLevelCapability != null) {
            Ops.call(module.topLevelCapability.resolve, Undefined.INSTANCE, new Object[] {Undefined.INSTANCE});
        }
        List<ModuleRecord> execList = new ArrayList<>();
        gatherAncestors(module, execList);
        execList.sort(Comparator.comparingLong((ModuleRecord entry) -> entry.asyncEvaluationOrder));
        for (ModuleRecord entry : execList) {
            if (entry.status == ModuleRecord.Status.EVALUATED) continue;
            if (entry.hasTLA) {
                executeAsync(entry);
            } else {
                try {
                    entry.executeModule(null);
                } catch (Exception | StackOverflowError | OutOfMemoryError throwable) {
                    if (throwable instanceof TerminationException termination) throw termination;
                    Object value = throwable instanceof JSException error
                            ? error.value : Rt.catchValue(throwable, entry.realm, null, 0);
                    asyncRejected(entry, value);
                    continue;
                }
                entry.asyncEvaluation = false;
                entry.status = ModuleRecord.Status.EVALUATED;
                if (entry.topLevelCapability != null) {
                    Ops.call(entry.topLevelCapability.resolve, Undefined.INSTANCE,
                            new Object[] {Undefined.INSTANCE});
                }
            }
        }
    }

    private static void asyncRejected(ModuleRecord module, Object error) {
        if (module.status == ModuleRecord.Status.EVALUATED) return;
        module.hasError = true;
        module.evaluationError = error;
        module.status = ModuleRecord.Status.EVALUATED;
        module.asyncEvaluation = false;
        // the module's own promise is rejected before those of its async parents (test262 rejection-order.js)
        if (module.topLevelCapability != null) {
            Ops.call(module.topLevelCapability.reject, Undefined.INSTANCE, new Object[] {error});
        }
        for (ModuleRecord parent : new ArrayList<>(module.asyncParentModules)) asyncRejected(parent, error);
    }

    // ------------------------------------------------------------------ host entry points

    /** Result of {@link #runModule}: the record and its top-level evaluation promise. */
    public record RunResult(ModuleRecord module, JSPromise promise) {
        public ModuleRecord getModule() {
            return module;
        }

        public JSPromise getPromise() {
            return promise;
        }
    }

    /** Loads, links and evaluates the module at {@code source.key}; returns the evaluation promise. */
    public static RunResult runModule(Realm realm, ModuleSource source) {
        HashMap<String, ModuleRecord> map = moduleMap(realm);
        ModuleRecord record = map.get(source.key);
        if (record == null) {
            record = parseModule(realm, source);
            map.put(source.key, record);
        }
        loadGraph(realm, record);
        link(record);
        return new RunResult(record, evaluate(record));
    }

    /** import.meta for the module owning the executing code. */
    public static JSObject importMeta(Frame frame) {
        Object owner = frame.code.source != null ? frame.code.source.owner : null;
        if (!(owner instanceof ModuleRecord module)) {
            throw JSException.syntaxError("import.meta is only valid in module code");
        }
        if (module.importMeta != null) return module.importMeta;
        JSObject object = new JSObject(null);
        try {
            for (Map.Entry<String, Object> entry
                    : loaderOf(module.realm).importMetaProperties(module.key).entrySet()) {
                object.createDataProperty(PK.fromString(entry.getKey()), entry.getValue());
            }
        } catch (JSException error) {
            // no loader: empty import.meta
        }
        module.importMeta = object;
        return object;
    }

    public static Object dynamicImport(Frame frame, Object specifier, Object options) {
        return dynamicImport(frame, specifier, options, null);
    }

    /** import(specifier, options) / import.defer(...) / import.source(...) (phase null = evaluation). */
    public static Object dynamicImport(Frame frame, Object specifier, Object options, String phase) {
        Realm realm = frame.realm;
        PromiseCapability capability = Promises.newPromiseCapability(realm, realm.getPromiseConstructor());
        Object owner = frame.code.source != null ? frame.code.source.owner : null;
        String referrer = owner instanceof ModuleRecord module ? module.key
                : owner instanceof String name ? name : null;
        try {
            String specifierText = Ops.toString(specifier);
            List<Map.Entry<String, String>> attributes = new ArrayList<>();
            if (options != Undefined.INSTANCE) {
                if (!(options instanceof JSObject optionsObject)) {
                    throw JSException.typeError("The second argument of import() must be an object");
                }
                Object with = optionsObject.get("with", optionsObject);
                if (with != Undefined.INSTANCE) {
                    if (!(with instanceof JSObject withObject)) {
                        throw JSException.typeError("The 'with' option must be an object");
                    }
                    for (Object key : withObject.ownPropertyKeys()) {
                        if (key instanceof JSSymbol) continue;
                        PropertyDescriptor descriptor = withObject.getOwnProperty(key);
                        if (descriptor == null) continue;
                        if (!descriptor.enumerable) continue;
                        Object value = withObject.get(key, withObject);
                        if (!(value instanceof CharSequence)) {
                            throw JSException.typeError("Import attribute values must be strings");
                        }
                        attributes.add(Map.entry(PK.toStringKey(key), value.toString()));
                    }
                }
            }
            ModuleRequest request = new ModuleRequest(specifierText, attributes, phase);
            // loading completes asynchronously (in a later job), as hosts do
            realm.agent.enqueueJob(() -> {
                try {
                    ModuleLoader loader = loaderOf(realm);
                    HashMap<String, ModuleRecord> map = moduleMap(realm);
                    ModuleRecord module = loadOne(realm, loader, map, request, referrer);
                    if (ModuleRequest.PHASE_SOURCE.equals(phase)) {
                        Ops.call(capability.resolve, Undefined.INSTANCE,
                                new Object[] {module.getModuleSource()});
                        return;
                    }
                    loadGraph(realm, module);
                    link(module);
                    if (ModuleRequest.PHASE_DEFER.equals(phase)) {
                        finishDeferredImport(realm, module, capability);
                        return;
                    }
                    JSPromise promise = evaluate(module);
                    Promises.thenHost(realm, promise,
                            value -> {
                                Ops.call(capability.resolve, Undefined.INSTANCE,
                                        new Object[] {namespaceOf(module)});
                                return Undefined.INSTANCE;
                            },
                            error -> {
                                Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error});
                                return Undefined.INSTANCE;
                            });
                } catch (JSException error) {
                    Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error.value});
                }
            });
        } catch (JSException error) {
            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error.value});
        }
        return capability.promise;
    }

    /** import.defer(): evaluate the asynchronous dependencies, then resolve with the deferred namespace. */
    private static void finishDeferredImport(Realm realm, ModuleRecord module, PromiseCapability capability) {
        List<ModuleRecord> dependencies = gatherAsyncDependencies(module);
        if (dependencies.isEmpty()) {
            Ops.call(capability.resolve, Undefined.INSTANCE,
                    new Object[] {namespaceOf(module, true)});
            return;
        }
        int[] remaining = {dependencies.size()};
        boolean[] settled = {false};
        for (ModuleRecord dependency : dependencies) {
            Promises.thenHost(realm, evaluate(dependency),
                    value -> {
                        if (!settled[0] && --remaining[0] == 0) {
                            settled[0] = true;
                            Ops.call(capability.resolve, Undefined.INSTANCE,
                                    new Object[] {namespaceOf(module, true)});
                        }
                        return Undefined.INSTANCE;
                    },
                    error -> {
                        if (!settled[0]) {
                            settled[0] = true;
                            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error});
                        }
                        return Undefined.INSTANCE;
                    });
        }
    }
}
