/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs;

import dev.mooner.neonjs.interop.HostClassInfo;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Policy deciding which host (Java/Kotlin) classes and members JS code may access.
 *
 * <p>Even in {@link Level#ALL} mode a built-in deny list blocks reflection, class loading,
 * process/thread control and similar capabilities; add more with {@link Builder#denyClass} or a
 * custom {@link Builder#classFilter}. Embedders that trust their scripts with some of these can
 * lift entries of the built-in list with {@link Builder#allowClass} and
 * {@link Builder#allowPackage}, or drop it with {@link Builder#defaultDenyList}.
 *
 * <p>Derived from NeonJS and adapted to Java. Kotlin function types are exposed as JDK
 * {@link Predicate} filters.
 */
public final class HostAccess {
    public enum Level {
        /** No host values can be accessed (they are opaque). */
        NONE,
        /** Only members annotated with {@link HostExport} (or members of classes annotated with it). */
        EXPLICIT,
        /** All public members of allowed classes. */
        ALL
    }

    public final Level level;
    private final Predicate<Class<?>> classFilter;
    private final Predicate<Member> memberFilter;
    private final Predicate<String> lookupFilter;
    private final Set<String> deniedClasses;
    private final List<String> deniedPrefixes;
    private final boolean defaultDenyList;
    private final Set<String> allowedClasses;
    private final List<String> allowedPrefixes;
    public final boolean allowArrayAccess;
    public final boolean allowListAccess;
    public final boolean allowMapAccess;
    public final boolean allowIterableAccess;
    /** Allow JS objects/functions to be converted to host interfaces (callbacks, listeners). */
    public final boolean allowImplementations;

    /**
     * What JS sees of each class under this policy, shared by the engines using it and collected
     * with it (classes generated per context, {@code Java.extend} adapters, are kept by their
     * context instead).
     */
    public final ConcurrentHashMap<Class<?>, HostClassInfo> classInfos = new ConcurrentHashMap<>();

    private HostAccess(Builder builder) {
        this.level = builder.level;
        this.classFilter = builder.classFilter;
        this.memberFilter = builder.memberFilter;
        this.lookupFilter = builder.lookupFilter;
        this.deniedClasses = new HashSet<>(builder.deniedClasses);
        this.deniedPrefixes = new ArrayList<>(builder.deniedPrefixes);
        this.defaultDenyList = builder.defaultDenyList;
        this.allowedClasses = new HashSet<>(builder.allowedClasses);
        this.allowedPrefixes = new ArrayList<>(builder.allowedPrefixes);
        this.allowArrayAccess = builder.allowArrayAccess;
        this.allowListAccess = builder.allowListAccess;
        this.allowMapAccess = builder.allowMapAccess;
        this.allowIterableAccess = builder.allowIterableAccess;
        this.allowImplementations = builder.allowImplementations;
    }

    public Level getLevel() {
        return level;
    }

    public boolean getAllowArrayAccess() {
        return allowArrayAccess;
    }

    public boolean getAllowListAccess() {
        return allowListAccess;
    }

    public boolean getAllowMapAccess() {
        return allowMapAccess;
    }

    public boolean getAllowIterableAccess() {
        return allowIterableAccess;
    }

    public boolean getAllowImplementations() {
        return allowImplementations;
    }

    /**
     * Whether the class named {@code n} is on the deny list: denied by the embedder
     * ({@link Builder#denyClass}, {@link Builder#denyPackage}), or on the built-in list and not
     * lifted. {@code packages} also applies the package entries.
     */
    private boolean isNameDenied(String n, boolean packages) {
        if (deniedClasses.contains(n) || (packages && startsWithAny(n, deniedPrefixes))) {
            return true;
        }
        if (!defaultDenyList || allowedClasses.contains(n) || startsWithAny(n, allowedPrefixes)) {
            return false;
        }
        return defaultDenied.contains(n) || isEngineApi(n)
                || (packages && startsWithAny(n, defaultDeniedPrefixes));
    }

    private boolean isNameDenied(String n) {
        return isNameDenied(n, true);
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code n} is one of {@link #defaultDeniedEngineClasses} or a class nested in one. */
    private static boolean isEngineApi(String n) {
        if (!n.startsWith(ENGINE_PACKAGE) || n.indexOf('.', ENGINE_PACKAGE.length()) >= 0) {
            return false;
        }
        int end = n.indexOf('$');
        if (end < 0) {
            end = n.length();
        }
        return defaultDeniedEngineClasses.contains(n.substring(ENGINE_PACKAGE.length(), end));
    }

    /** Whether {@code c}, one of its superclasses or one of its interfaces is on the deny list. */
    public boolean isClassDenied(Class<?> c) {
        return isDenied(c, ignored -> false);
    }

    /**
     * {@link #isClassDenied}, but for the superclasses {@code base} accepts only where the embedder
     * denied them: the built-in list denies those for what they offer themselves, not for what
     * their subclasses' instances are.
     */
    private boolean isDenied(Class<?> c, Predicate<Class<?>> base) {
        Class<?> k = c;
        while (k != null) {
            String n = k.getName();
            if (isNameDenied(n) && (k == c || !base.test(k) || deniedClasses.contains(n)
                    || startsWithAny(n, deniedPrefixes))) {
                return true;
            }
            k = k.getSuperclass();
        }
        for (Class<?> iface : c.getInterfaces()) {
            if (isNameDenied(iface.getName(), false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether instances of {@code c} may not be called as functions: {@code c} or a superclass is
     * on the deny list, except the bases of proxies ({@code java.lang.reflect.Proxy}) and of the
     * classes Kotlin compiles lambdas and function references to ({@code kotlin.jvm.internal}):
     * those are denied for their own members (reflection), not for calls.
     */
    public boolean isCallDenied(Class<?> c) {
        return isDenied(c, k -> k == Proxy.class || k.getName().startsWith("kotlin.jvm.internal."));
    }

    /**
     * Can JS see the instance members of an object of class {@code c}? Those of a class that is not
     * public (what {@code listOf}, {@code Collections.unmodifiableList}, {@code Map.of} or
     * {@code CharBuffer.wrap} return) are the methods of its public superclasses and interfaces,
     * called through those types, as Java code outside its package would call them. A proxy shows
     * the methods of its interfaces: its base class {@code java.lang.reflect.Proxy} has no public
     * instance methods of its own.
     */
    public boolean isInstanceAccessible(Class<?> c) {
        if (level == Level.NONE) {
            return false;
        }
        if (c.isArray()) {
            return isClassAccessible(c);
        }
        if (isDenied(c, k -> k == Proxy.class)) {
            return false;
        }
        Predicate<Class<?>> filter = classFilter;
        return filter == null || filter.test(c);
    }

    /** Can JS see members of {@code c} at all? */
    public boolean isClassAccessible(Class<?> c) {
        if (level == Level.NONE) {
            return false;
        }
        if (c.isArray()) {
            // arrays are judged by their element type (int[] is fine, Class[] is not)
            Class<?> e = c;
            while (e.isArray()) {
                e = e.getComponentType();
            }
            return e.isPrimitive() || isClassAccessible(e);
        }
        if (!Modifier.isPublic(c.getModifiers())) {
            return false;
        }
        if (isClassDenied(c)) {
            return false;
        }
        Predicate<Class<?>> filter = classFilter;
        return filter == null || filter.test(c);
    }

    /**
     * Whether JS may use member {@code m} of class {@code c}: public, not declared by a denied
     * class, exported under {@link Level#EXPLICIT}, accepted by the member filter. Synthetic
     * methods are not members, except the bridges through which a method is called by its name.
     */
    public boolean isMemberAccessible(Class<?> c, Member m) {
        if (!Modifier.isPublic(m.getModifiers())) {
            return false;
        }
        if (m instanceof Method) {
            Method method = (Method) m;
            if (method.getName().equals("getClass") && method.getParameterCount() == 0) {
                // it hands out java.lang.Class: visible only where that class is
                if (isNameDenied("java.lang.Class")) {
                    return false;
                }
            } else if (deniedMethods.contains(method.getName())
                    && method.getDeclaringClass() == Object.class) {
                return false;
            }
            if ((method.isSynthetic() || method.isBridge()) && !isEntryBridge(method)) {
                return false;
            }
            if (isDenied(method.getDeclaringClass(), k -> k == Proxy.class)) {
                return false;
            }
        }
        switch (level) {
            case NONE:
                return false;
            case EXPLICIT: {
                boolean annotated = (m instanceof AnnotatedElement
                        && ((AnnotatedElement) m).isAnnotationPresent(HostExport.class))
                        || c.isAnnotationPresent(HostExport.class)
                        || m.getDeclaringClass().isAnnotationPresent(HostExport.class)
                        || (m instanceof Method && overridesExported((Method) m));
                if (!annotated) {
                    return false;
                }
                break;
            }
            case ALL:
                break;
            default:
                return false;
        }
        Predicate<Member> filter = memberFilter;
        return filter == null || filter.test(m);
    }

    /**
     * Whether bridge method {@code m} is how a method is called by this name, rather than a
     * forwarder to a visible method of its own class.
     */
    private static boolean isEntryBridge(Method m) {
        if (!m.isBridge()) {
            return false;
        }
        Class<?>[] params = m.getParameterTypes();
        for (Method other : m.getDeclaringClass().getDeclaredMethods()) {
            if (!other.getName().equals(m.getName()) || other.isBridge() || other.isSynthetic()
                    || other.getParameterCount() != params.length) {
                continue;
            }
            Class<?>[] otherParams = other.getParameterTypes();
            boolean moreSpecific = true;
            for (int i = 0; i < params.length; i++) {
                if (!params[i].isAssignableFrom(otherParams[i])) {
                    moreSpecific = false;
                    break;
                }
            }
            if (moreSpecific) {
                return false;
            }
        }
        return true;
    }

    /**
     * True if {@code m} overrides or implements a method that is exported (annotated, or declared
     * by an annotated type) in a superclass or interface.
     */
    private static boolean overridesExported(Method m) {
        Set<Class<?>> seen = new HashSet<>();
        Class<?> declaring = m.getDeclaringClass();
        return checkExported(declaring.getSuperclass(), m, seen)
                || anyExported(declaring.getInterfaces(), m, seen);
    }

    private static boolean anyExported(Class<?>[] types, Method m, Set<Class<?>> seen) {
        for (Class<?> type : types) {
            if (checkExported(type, m, seen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkExported(Class<?> c, Method m, Set<Class<?>> seen) {
        if (c == null || !seen.add(c)) {
            return false;
        }
        try {
            Method sm = c.getDeclaredMethod(m.getName(), m.getParameterTypes());
            if (sm.isAnnotationPresent(HostExport.class) || c.isAnnotationPresent(HostExport.class)) {
                return true;
            }
        } catch (NoSuchMethodException ignored) {
            // not declared here
        }
        return checkExported(c.getSuperclass(), m, seen)
                || anyExported(c.getInterfaces(), m, seen);
    }

    /** Whether {@code Java.type(name)} may load the class. */
    public boolean isLookupAllowed(String className) {
        if (level == Level.NONE) {
            return false;
        }
        Predicate<String> filter = lookupFilter;
        return filter != null && filter.test(className);
    }

    public static final class Builder {
        public Level level;
        public Predicate<Class<?>> classFilter;
        public Predicate<Member> memberFilter;
        public Predicate<String> lookupFilter;
        public final Set<String> deniedClasses = new HashSet<>();
        public final List<String> deniedPrefixes = new ArrayList<>();
        public final Set<String> allowedClasses = new HashSet<>();
        public final List<String> allowedPrefixes = new ArrayList<>();
        public boolean defaultDenyList = true;
        public boolean allowArrayAccess = true;
        public boolean allowListAccess = true;
        public boolean allowMapAccess = true;
        public boolean allowIterableAccess = true;
        public boolean allowImplementations = true;

        public Builder(Level level) {
            this.level = level;
        }

        public Builder classFilter(Predicate<Class<?>> filter) {
            this.classFilter = filter;
            return this;
        }

        public Builder memberFilter(Predicate<Member> filter) {
            this.memberFilter = filter;
            return this;
        }

        /** Permits {@code Java.type(name)} for class names accepted by {@code filter}. */
        public Builder allowLookup(Predicate<String> filter) {
            this.lookupFilter = filter;
            return this;
        }

        /** Additional predicate for classes (applied after the deny list). */
        public Builder filterClasses(Predicate<Class<?>> filter) {
            this.classFilter = filter;
            return this;
        }

        /** Additional predicate for members (methods, fields, constructors). */
        public Builder filterMembers(Predicate<Member> filter) {
            this.memberFilter = filter;
            return this;
        }

        /** Denies the class {@code name} (binary name), its subclasses and the classes implementing it. */
        public Builder denyClass(String name) {
            this.deniedClasses.add(name);
            return this;
        }

        /** Denies the classes of the package {@code prefix} and its subpackages. */
        public Builder denyPackage(String prefix) {
            this.deniedPrefixes.add(packagePrefix(prefix));
            return this;
        }

        /**
         * Lifts the built-in deny list for the class {@code name} (binary name). Its members become
         * visible like those of any other class, so narrow them with {@link #filterMembers} where
         * needed. Classes denied with {@link #denyClass} or {@link #denyPackage} stay denied.
         */
        public Builder allowClass(String name) {
            this.allowedClasses.add(name);
            return this;
        }

        /** Lifts the built-in deny list for the classes of the package {@code prefix} and its subpackages. */
        public Builder allowPackage(String prefix) {
            this.allowedPrefixes.add(packagePrefix(prefix));
            return this;
        }

        /**
         * Applies the built-in deny list; {@code true} by default. Turning it off leaves only the
         * classes denied with {@link #denyClass} / {@link #denyPackage} and the filters. {@code wait},
         * {@code notify}, {@code notifyAll} and {@code finalize} stay hidden either way.
         */
        public Builder defaultDenyList(boolean enabled) {
            this.defaultDenyList = enabled;
            return this;
        }

        private static String packagePrefix(String p) {
            return p.endsWith(".") ? p : p + ".";
        }

        public Builder allowArrayAccess(boolean b) {
            this.allowArrayAccess = b;
            return this;
        }

        public Builder allowListAccess(boolean b) {
            this.allowListAccess = b;
            return this;
        }

        public Builder allowMapAccess(boolean b) {
            this.allowMapAccess = b;
            return this;
        }

        public Builder allowIterableAccess(boolean b) {
            this.allowIterableAccess = b;
            return this;
        }

        public Builder allowImplementations(boolean b) {
            this.allowImplementations = b;
            return this;
        }

        public HostAccess build() {
            return new HostAccess(this);
        }
    }

    public static final Set<String> defaultDenied = Set.of(
            "java.lang.Class", "java.lang.ClassLoader", "java.lang.Thread", "java.lang.ThreadGroup",
            "java.lang.Runtime", "java.lang.System", "java.lang.ProcessBuilder", "java.lang.Process",
            "java.lang.ProcessHandle", "java.lang.SecurityManager", "java.lang.Module",
            "java.lang.ModuleLayer", "java.lang.StackWalker", "java.lang.ref.Cleaner", "sun.misc.Unsafe",
            "java.lang.invoke.MethodHandles", "java.lang.invoke.MethodHandles$Lookup",
            "java.io.ObjectInputStream", "java.io.ObjectOutputStream");

    public static final List<String> defaultDeniedPrefixes = List.of(
            "java.lang.reflect.", "java.lang.invoke.", "sun.", "com.sun.", "jdk.internal.",
            "java.security.", "javax.script.", "java.lang.instrument.", "java.lang.management.",
            "kotlin.reflect.", "kotlin.jvm.internal.", "com.ibm.icu.", "okhttp3.", "okio.",
            // the engine's own packages
            "dev.mooner.neonjs.android.", "dev.mooner.neonjs.builtins.", "dev.mooner.neonjs.cli.",
            "dev.mooner.neonjs.compiler.", "dev.mooner.neonjs.ext.", "dev.mooner.neonjs.interop.",
            "dev.mooner.neonjs.intl.", "dev.mooner.neonjs.jit.", "dev.mooner.neonjs.net.",
            "dev.mooner.neonjs.node.", "dev.mooner.neonjs.parser.", "dev.mooner.neonjs.regexp.",
            "dev.mooner.neonjs.runtime.", "dev.mooner.neonjs.unicode.", "dev.mooner.neonjs.vm.");

    private static final String ENGINE_PACKAGE = "dev.mooner.neonjs.";

    /**
     * The classes of the engine's API package that control engines, contexts, policies and values,
     * or read files (simple names; classes nested in them are denied too). The rest of the package
     * (annotations, exceptions, {@link HostFunction}, {@link NeonConsole}, {@link NeonModuleLoader})
     * is used or implemented by host code, and stays visible.
     */
    public static final Set<String> defaultDeniedEngineClasses = Set.of(
            "NeonEngine", "NeonContext", "NeonValue", "NeonScript", "HostAccess", "SandboxPolicy",
            "FileSystemModuleLoader", "MapModuleLoader");

    /** java.lang.Object methods never exposed ({@code getClass} only while {@code java.lang.Class} is denied). */
    public static final Set<String> deniedMethods = Set.of(
            "getClass", "wait", "notify", "notifyAll", "finalize");

    /** No host access. */
    public static final HostAccess NONE = new Builder(Level.NONE).build();
    /** Only {@link HostExport} annotated members. */
    public static final HostAccess EXPLICIT = new Builder(Level.EXPLICIT).build();
    /** All public members (minus the deny list); no class lookup by name. */
    public static final HostAccess ALL = new Builder(Level.ALL).build();

    public static Builder builder(Level level) {
        return new Builder(level);
    }
}
