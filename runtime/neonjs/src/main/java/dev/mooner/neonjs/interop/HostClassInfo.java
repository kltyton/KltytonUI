/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.interop;

import dev.mooner.neonjs.HostAccess;
import dev.mooner.neonjs.HostName;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/** Reflection metadata of a host class as seen from JS under a {@link HostAccess} policy. */
public final class HostClassInfo {
    public final Class<?> cls;
    public final HostAccess access;

    /** Whether JS may use the class itself: static members, constructors (and see {@link #instancesVisible}). */
    public final boolean accessible;
    /** Whether instances show members: those of a non-public class are the ones of its public supertypes. */
    public final boolean instancesVisible;

    public final HashMap<String, Field> instanceFields = new HashMap<>();
    public final HashMap<String, List<Method>> instanceMethods = new HashMap<>();
    public final HashMap<String, Method> instanceGetters = new HashMap<>();
    public final HashMap<String, List<Method>> instanceSetters = new HashMap<>();
    public final HashMap<String, Field> staticFields = new HashMap<>();
    public final HashMap<String, List<Method>> staticMethods = new HashMap<>();
    public final HashMap<String, Method> staticGetters = new HashMap<>();
    public final HashMap<String, List<Method>> staticSetters = new HashMap<>();
    public final ArrayList<Constructor<?>> constructors = new ArrayList<>();
    public final HashMap<String, Class<?>> memberClasses = new HashMap<>();
    private final ConcurrentHashMap<Field, HostFieldAccess> fieldAccessors = new ConcurrentHashMap<>();

    HostFieldAccess fieldAccess(Field field) {
        return fieldAccessors.computeIfAbsent(field, HostFieldAccess::new);
    }

    /** The method a call of an instance runs, if instances are functions (see {@link #findFunctionalMethod}). */
    public Method functionalMethod;
    /** The overloads of {@link #functionalMethod} a call chooses from (set by the first call). */
    volatile List<Method> callOverloads;

    private static final boolean HAS_MODULES = hasModules();

    private HostClassInfo(Class<?> cls, HostAccess access) {
        this.cls = cls;
        this.access = access;
        accessible = access.isClassAccessible(cls);
        instancesVisible = access.isInstanceAccessible(cls);
        if (instancesVisible) collect();
        Method functional;
        try {
            functional = findFunctionalMethod(cls, access);
        } catch (LinkageError unavailable) {
            functional = null;
        }
        functionalMethod = functional;
    }

    private static boolean hasModules() {
        try {
            Class.class.getMethod("getModule");
            return true;
        } catch (NoSuchMethodException unavailable) {
            return false;
        }
    }

    private static String jsName(AccessibleObject member, String fallback) {
        HostName annotation = member.getAnnotation(HostName.class);
        return annotation == null ? fallback : annotation.value();
    }

    /**
     * Members whose signature names a class missing at run time (an Android API of a later level, an optional
     * library) are left out, rather than failing the whole class: each member is skipped on its own where the platform
     * resolves its types one member at a time (ART), the declared members of a class together where it resolves them
     * all at once (HotSpot), keeping those inherited from the other classes of the hierarchy.
     */
    private void collect() {
        for (Field field : this.<Field>publicMembers(c -> c.getFields(), c -> c.getDeclaredFields())) {
            try {
                if (!access.isMemberAccessible(cls, field) || !isCallable(field)
                        || (Modifier.isStatic(field.getModifiers()) && !accessible)) continue;
                field.getType(); // resolves it (ART resolves types when first asked for)
                String name = jsName(field, field.getName());
                if (Modifier.isStatic(field.getModifiers())) staticFields.put(name, field);
                else instanceFields.put(name, field);
            } catch (LinkageError missingType) {
                // a missing type
            }
        }
        for (Method original : this.<Method>publicMembers(c -> c.getMethods(), c -> c.getDeclaredMethods())) {
            try {
                if (!access.isMemberAccessible(cls, original)
                        || (Modifier.isStatic(original.getModifiers()) && !accessible)) continue;
                Method method = publicVersion(original);
                if (method == null) continue;
                // reached through a supertype: that type is what JS calls, so it must not be denied either
                if (method != original && access.isClassDenied(method.getDeclaringClass())) continue;
                // resolves its types (ART resolves them when first asked for)
                method.getParameterTypes();
                method.getReturnType();
                String name = jsName(original, original.getName());
                HashMap<String, List<Method>> target = Modifier.isStatic(method.getModifiers())
                        ? staticMethods : instanceMethods;
                List<Method> overloads = target.get(name);
                if (overloads == null) {
                    overloads = new ArrayList<>();
                    target.put(name, overloads);
                }
                boolean present = false;
                for (Method candidate : overloads) {
                    if (sameSignature(candidate, method)) {
                        present = true;
                        break;
                    }
                }
                if (!present) overloads.add(method);
            } catch (LinkageError missingType) {
                // a missing type
            }
        }
        // bean-style properties (Kotlin properties compile to getX/isX/setX)
        collectProperties(false, instanceMethods, instanceGetters, instanceSetters, instanceFields);
        collectProperties(true, staticMethods, staticGetters, staticSetters, staticFields);
        if (!accessible) return;
        if (!Modifier.isAbstract(cls.getModifiers()) && !cls.isInterface()) {
            for (Constructor<?> constructor : HostClassInfo.<Constructor<?>>orNone(() -> cls.getConstructors())) {
                try {
                    constructor.getParameterTypes(); // resolves them
                    if (access.isMemberAccessible(cls, constructor)) constructors.add(constructor);
                } catch (LinkageError missingType) {
                    // a missing type
                }
            }
        }
        for (Class<?> memberClass : HostClassInfo.<Class<?>>orNone(() -> cls.getClasses())) {
            if (Modifier.isPublic(memberClass.getModifiers()) && Modifier.isStatic(memberClass.getModifiers())
                    && access.isClassAccessible(memberClass)) {
                memberClasses.put(memberClass.getSimpleName(), memberClass);
            }
        }
    }

    private void collectProperties(boolean isStatic, HashMap<String, List<Method>> methods,
                                   HashMap<String, Method> getters, HashMap<String, List<Method>> setters,
                                   HashMap<String, Field> fields) {
        for (String name : new ArrayList<>(methods.keySet())) {
            List<Method> overloads = methods.get(name);
            for (Method method : overloads) {
                String property = null;
                if (name.length() > 3 && name.startsWith("get") && method.getParameterCount() == 0
                        && method.getReturnType() != Void.TYPE) {
                    property = decap(name.substring(3));
                } else if (name.length() > 2 && name.startsWith("is") && method.getParameterCount() == 0
                        && (method.getReturnType() == Boolean.TYPE || method.getReturnType() == Boolean.class)) {
                    property = decap(name.substring(2));
                }
                if (property == null) continue;
                if (methods.containsKey(property) || fields.containsKey(property)) continue;
                getters.putIfAbsent(property, method);
            }
            if (name.length() > 3 && name.startsWith("set")) {
                String property = decap(name.substring(3));
                if (methods.containsKey(property) || fields.containsKey(property)) continue;
                for (Method method : overloads) {
                    if (method.getParameterCount() != 1) continue;
                    List<Method> setterOverloads = setters.get(property);
                    if (setterOverloads == null) {
                        setterOverloads = new ArrayList<>();
                        setters.put(property, setterOverloads);
                    }
                    setterOverloads.add(method);
                }
            }
        }
    }

    private static <T> List<T> orNone(Supplier<T[]> supplier) {
        try {
            return Arrays.asList(supplier.get());
        } catch (LinkageError missingType) {
            return List.of();
        }
    }

    /**
     * The public fields or methods of {@link #cls} ({@code all}: {@code getFields}, {@code getMethods}). Where those
     * fail because a type of one of them is missing, the public members of each class of the hierarchy
     * ({@code declared}) whose members load, the nearest declaration of each signature first, without the static
     * methods of supertypes' interfaces.
     */
    private <M extends Member> List<M> publicMembers(Function<Class<?>, M[]> all,
                                                     Function<Class<?>, M[]> declared) {
        try {
            return Arrays.asList(all.apply(cls));
        } catch (LinkageError missingType) {
            // collect them class by class below
        }
        LinkedHashMap<String, M> found = new LinkedHashMap<>();
        HashSet<Class<?>> seen = new HashSet<>();
        collectDeclared(cls, declared, found, seen);
        Class<?> current = cls;
        while (current != null) {
            addHierarchy(current, declared, found, seen);
            current = current.getSuperclass();
        }
        current = cls;
        while (current != null) {
            addInterfaces(current, declared, found, seen);
            current = current.getSuperclass();
        }
        return new ArrayList<>(found.values());
    }

    private <M extends Member> void addHierarchy(Class<?> type, Function<Class<?>, M[]> declared,
                                                 LinkedHashMap<String, M> found, Set<Class<?>> seen) {
        if (!seen.add(type)) return;
        collectDeclared(type, declared, found, seen);
    }

    private <M extends Member> void collectDeclared(Class<?> type, Function<Class<?>, M[]> declared,
                                                    LinkedHashMap<String, M> found, Set<Class<?>> seen) {
        M[] members;
        try {
            members = declared.apply(type);
        } catch (LinkageError missingType) {
            return;
        }
        for (M member : members) {
            if (!Modifier.isPublic(member.getModifiers())) continue;
            if (type != cls && type.isInterface() && member instanceof Method method
                    && Modifier.isStatic(method.getModifiers())) continue;
            String key;
            try {
                if (member instanceof Method method) {
                    StringBuilder builder = new StringBuilder(method.getName()).append('(');
                    Class<?>[] parameters = method.getParameterTypes();
                    for (int i = 0; i < parameters.length; i++) {
                        if (i > 0) builder.append(',');
                        builder.append(parameters[i].getName());
                    }
                    key = builder.append(')').toString();
                } else {
                    key = member.getName();
                }
            } catch (LinkageError missingType) {
                continue;
            }
            found.putIfAbsent(key, member);
        }
    }

    private <M extends Member> void addInterfaces(Class<?> type, Function<Class<?>, M[]> declared,
                                                  LinkedHashMap<String, M> found, Set<Class<?>> seen) {
        for (Class<?> iface : type.getInterfaces()) {
            addHierarchy(iface, declared, found, seen);
            addInterfaces(iface, declared, found, seen);
        }
    }

    private static String decap(String value) {
        if (value.isEmpty()) return value;
        if (value.length() > 1 && Character.isUpperCase(value.charAt(0)) && Character.isUpperCase(value.charAt(1))) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static boolean sameSignature(Method a, Method b) {
        return a.getName().equals(b.getName()) && Arrays.equals(a.getParameterTypes(), b.getParameterTypes());
    }

    /**
     * The overload a signature key names, as in Rhino, Nashorn and GraalJS: {@code append(java.lang.String)},
     * {@code append(char[])}, {@code max(int,int)}; parameter types by binary, canonical or simple name. Null if
     * {@code key} is not a signature or names no single overload.
     */
    public Method overload(String key, boolean isStatic) {
        int open = key.indexOf('(');
        if (open <= 0 || !key.endsWith(")")) return null;
        List<Method> overloads = (isStatic ? staticMethods : instanceMethods).get(key.substring(0, open));
        if (overloads == null) return null;
        String body = key.substring(open + 1, key.length() - 1).trim();
        List<String> types = body.isEmpty() ? List.of() : Arrays.asList(body.split(",", -1));
        List<Method> found = new ArrayList<>();
        for (Method method : overloads) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != types.size()) continue;
            boolean matches = true;
            for (int i = 0; i < parameters.length; i++) {
                String type = types.get(i).trim();
                Class<?> parameter = parameters[i];
                if (!(type.equals(parameter.getName()) || type.equals(parameter.getTypeName())
                        || type.equals(parameter.getCanonicalName()) || type.equals(parameter.getSimpleName()))) {
                    matches = false;
                    break;
                }
            }
            if (matches) found.add(method);
        }
        return found.size() == 1 ? found.get(0) : null;
    }

    /** The (cached) view of {@code cls} under {@code access}. */
    public static HostClassInfo of(Class<?> cls, HostAccess access) {
        return access.classInfos.computeIfAbsent(cls, key -> new HostClassInfo(key, access));
    }

    /** A view of {@code cls} under {@code access} that nothing caches: for classes that live only as long as a context. */
    public static HostClassInfo uncached(Class<?> cls, HostAccess access) {
        return new HostClassInfo(cls, access);
    }

    /** Finds a version of {@code m} declared in a public, exported type so it can be invoked reflectively. */
    public static Method publicVersion(Method method) {
        if (isCallable(method)) return method;
        HashSet<Class<?>> seen = new HashSet<>();
        return searchPublicVersion(method.getDeclaringClass(), method, seen);
    }

    private static Method searchPublicVersion(Class<?> type, Method method, Set<Class<?>> seen) {
        if (type == null || !seen.add(type)) return null;
        if (Modifier.isPublic(type.getModifiers()) && isExported(type)) {
            try {
                Method candidate = type.getMethod(method.getName(), method.getParameterTypes());
                if (isCallable(candidate)) return candidate;
            } catch (NoSuchMethodException missing) {
                // continue
            }
        }
        for (Class<?> iface : type.getInterfaces()) {
            Method found = searchPublicVersion(iface, method, seen);
            if (found != null) return found;
        }
        return searchPublicVersion(type.getSuperclass(), method, seen);
    }

    /** Whether {@code c}'s package is exported by its module (always true where there are no modules, e.g. Android). */
    private static boolean isExported(Class<?> type) {
        if (!HAS_MODULES) return true;
        Module module = type.getModule();
        String name = type.getName();
        int dot = name.lastIndexOf('.');
        String pkg = dot < 0 ? "" : name.substring(0, dot);
        return !module.isNamed() || module.isExported(pkg);
    }

    /** Whether {@code m} can be used reflectively: its declaring class is public and exported. */
    private static boolean isCallable(Member member) {
        Class<?> declaring = member.getDeclaringClass();
        return Modifier.isPublic(declaring.getModifiers()) && isExported(declaring)
                && (declaring.getEnclosingClass() == null
                || Modifier.isPublic(declaring.getEnclosingClass().getModifiers()));
    }

    /**
     * The method a call of an instance of {@code cls} runs, or null if instances are not functions. An object is a
     * function when that is what its class is for:
     * <ul>
     *   <li>a lambda, a method reference or an anonymous class, through the one single-method interface it implements
     *   (any: Kotlin {@code fun interface}s, Android listeners);</li>
     *   <li>otherwise through an interface declared functional, {@code @FunctionalInterface} as in GraalJS
     *   ({@code Runnable}, {@code Comparator}, {@code java.util.function.*}) or a Kotlin function type, provided that
     *   is the class's only role: every other interface it implements has no abstract methods or is a super- or
     *   subinterface of that one.</li>
     * </ul>
     *
     * <p>So an {@code ArrayList} ({@code Iterable}), a {@code File} ({@code Comparable}), a {@code LocalDate} (a
     * {@code TemporalAdjuster}, but also a {@code Temporal}) or a named class implementing a Kotlin {@code fun
     * interface} are objects, whose methods are members. Nothing is a function under {@link HostAccess.Level#NONE},
     * nor an instance of a denied class.
     */
    public static Method findFunctionalMethod(Class<?> cls, HostAccess access) {
        if (access.level == HostAccess.Level.NONE || access.isCallDenied(cls)) return null;
        if ((cls.isSynthetic() || cls.isAnonymousClass()) && cls.getSuperclass() == Object.class) {
            List<Method> sams = new ArrayList<>();
            for (Class<?> iface : cls.getInterfaces()) {
                Method sam = samIn(iface, access);
                if (sam != null) sams.add(sam);
            }
            if (sams.size() == 1) return sams.get(0);
        }
        // declared functional, nearest first: the class's interfaces in declaration order and theirs, then the superclass's
        HashSet<Class<?>> seen = new HashSet<>();
        Class<?> functionalInterface = null;
        Method sam = null;
        Class<?> type = cls;
        search:
        while (type != null) {
            ArrayDeque<Class<?>> queue = new ArrayDeque<>(Arrays.asList(type.getInterfaces()));
            while (!queue.isEmpty()) {
                Class<?> iface = queue.removeFirst();
                if (!seen.add(iface)) continue;
                if (iface.isAnnotationPresent(FunctionalInterface.class) || isKotlinFunction(iface)) {
                    sam = samIn(iface, access);
                    if (sam != null) {
                        functionalInterface = iface;
                        break search;
                    }
                }
                for (Class<?> parent : iface.getInterfaces()) queue.addLast(parent);
            }
            type = type.getSuperclass();
        }
        if (functionalInterface == null) return null;
        // its only role; the interfaces every enum has, and those of the classes Kotlin compiles lambdas and
        // references to, are not roles of the class
        type = cls;
        while (type != null && type != Enum.class && !type.getName().startsWith("kotlin.jvm.internal.")) {
            for (Class<?> iface : type.getInterfaces()) {
                boolean related = iface.isAssignableFrom(functionalInterface)
                        || (functionalInterface.isAssignableFrom(iface) && samOf(iface) != null)
                        || (isKotlinFunction(iface) && isKotlinFunction(functionalInterface));
                if (!related && hasAbstractMethod(iface)) return null;
            }
            type = type.getSuperclass();
        }
        return sam;
    }

    private static Method samIn(Class<?> iface, HostAccess access) {
        if (!Modifier.isPublic(iface.getModifiers()) || access.isClassDenied(iface)) return null;
        Method sam = samOf(iface);
        return sam != null && isCallable(sam) ? sam : null;
    }

    private static boolean hasAbstractMethod(Class<?> iface) {
        for (Method method : iface.getMethods()) {
            if (Modifier.isAbstract(method.getModifiers()) && !isObjectMethod(method)) return true;
        }
        return false;
    }

    /**
     * Whether a type is one of Kotlin's function interfaces ({@code kotlin.jvm.functions.FunctionN} and
     * {@code kotlin.Function}, directly or through a superinterface). Adapters expose JDK functional interfaces; this
     * name-based test keeps the original detection without linking the Kotlin runtime.
     */
    private static boolean isKotlinFunction(Class<?> type) {
        if (type == null) return false;
        String name = type.getName();
        if (name.equals("kotlin.Function") || name.startsWith("kotlin.jvm.functions.Function")) return true;
        for (Class<?> iface : type.getInterfaces()) {
            if (isKotlinFunction(iface)) return true;
        }
        return false;
    }

    /** Single abstract method of an interface, or null. */
    public static Method samOf(Class<?> iface) {
        if (!iface.isInterface()) return null;
        Method found = null;
        for (Method method : iface.getMethods()) {
            if (!Modifier.isAbstract(method.getModifiers())) continue;
            if (isObjectMethod(method)) continue;
            if (found != null && !(found.getName().equals(method.getName())
                    && Arrays.equals(found.getParameterTypes(), method.getParameterTypes()))) {
                return null;
            }
            found = method;
        }
        return found;
    }

    private static boolean isObjectMethod(Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }
}
