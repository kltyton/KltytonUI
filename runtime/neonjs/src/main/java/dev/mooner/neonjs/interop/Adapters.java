/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.interop;

import dev.mooner.neonjs.jit.ArtCha;
import dev.mooner.neonjs.jit.CodeDefiner;
import dev.mooner.neonjs.jit.CodeDefiners;
import dev.mooner.neonjs.runtime.JSException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Generates JVM subclasses of host classes / implementations of several interfaces whose methods are implemented by
 * JS objects ({@code Java.extend}). Each class lives in its own class loader so it can be unloaded with its context
 * (except classes implementing interfaces on Android 8.0 and 8.1, which stay loaded).
 */
public final class Adapters {
    public static final Adapters INSTANCE = new Adapters();

    private static final AtomicInteger COUNTER = new AtomicInteger();
    /** The package of adapter classes: outside the engine's denied packages. */
    private static final String PREFIX = "dev.mooner.neonjs.adapters.";
    private static final String DELEGATE_FIELD = "__neonDelegate";
    private static final String DELEGATE = Type.getInternalName(AdapterDelegate.class);
    private static final String SUPPORT = Type.getInternalName(AdapterSupport.class);

    private Adapters() {
    }

    /** Whether {@code c} is an adapter class (generated for one context). */
    public static boolean isAdapter(Class<?> cls) {
        return cls.getName().startsWith(PREFIX);
    }

    public AdapterClass generate(List<Class<?>> types) {
        return generate(types, CodeDefiners.getDefault());
    }

    /**
     * Parent of an adapter class's loader: the extended types' loader, except for the engine types the adapter calls,
     * which that loader may not see.
     */
    private static final class BridgeLoader extends ClassLoader {
        BridgeLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(AdapterDelegate.class.getName())) return AdapterDelegate.class;
            if (name.equals(AdapterSupport.class.getName())) return AdapterSupport.class;
            return super.loadClass(name, resolve);
        }
    }

    /** Validates {@code types} (at most one class, the rest interfaces) and generates the adapter, defined by {@code definer}. */
    public AdapterClass generate(List<Class<?>> types, CodeDefiner definer) {
        Class<?> base = Object.class;
        for (Class<?> type : types) {
            if (!type.isInterface()) {
                base = type;
                break;
            }
        }
        int classes = 0;
        for (Class<?> type : types) if (!type.isInterface()) classes++;
        if (classes > 1) throw JSException.typeError("Java.extend: at most one class may be extended");
        if (Modifier.isFinal(base.getModifiers())) {
            throw JSException.typeError("Java.extend: " + base.getName() + " is final");
        }
        if (!Modifier.isPublic(base.getModifiers())) {
            throw JSException.typeError("Java.extend: " + base.getName() + " is not public");
        }
        LinkedHashSet<Class<?>> ifaces = new LinkedHashSet<>();
        for (Class<?> type : types) {
            if (type.isInterface()) addInterface(type, ifaces);
        }
        List<Constructor<?>> constructors = new ArrayList<>();
        for (Constructor<?> constructor : base.getDeclaredConstructors()) {
            if (Modifier.isPublic(constructor.getModifiers()) || Modifier.isProtected(constructor.getModifiers())) {
                constructors.add(constructor);
            }
        }
        if (constructors.isEmpty()) {
            throw JSException.typeError("Java.extend: " + base.getName() + " has no accessible constructor");
        }

        List<Method> methods = collectMethods(base, ifaces);
        String name = PREFIX + "NeonAdapter" + COUNTER.incrementAndGet();
        String internal = name.replace('.', '/');
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            // the generated code never merges distinct reference types; avoid loading classes here
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        String[] interfaces = new String[ifaces.size()];
        int ifaceIndex = 0;
        for (Class<?> iface : ifaces) interfaces[ifaceIndex++] = Type.getInternalName(iface);
        writer.visit(Math.min(Opcodes.V21, definer.getClassFileVersion()),
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
                internal, null, Type.getInternalName(base), interfaces);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, DELEGATE_FIELD,
                "L" + DELEGATE + ";", null, null).visitEnd();

        for (Constructor<?> constructor : constructors) {
            Type[] parameters = new Type[constructor.getParameterCount()];
            for (int i = 0; i < parameters.length; i++) parameters[i] = Type.getType(constructor.getParameterTypes()[i]);
            Type[] descriptorParameters = new Type[parameters.length + 1];
            descriptorParameters[0] = Type.getObjectType(DELEGATE);
            System.arraycopy(parameters, 0, descriptorParameters, 1, parameters.length);
            String descriptor = Type.getMethodDescriptor(Type.VOID_TYPE, descriptorParameters);
            MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", descriptor, null, null);
            visitor.visitCode();
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            int slot = 2;
            for (Type parameter : parameters) {
                visitor.visitVarInsn(parameter.getOpcode(Opcodes.ILOAD), slot);
                slot += parameter.getSize();
            }
            visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(base), "<init>",
                    Type.getConstructorDescriptor(constructor), false);
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            visitor.visitVarInsn(Opcodes.ALOAD, 1);
            visitor.visitFieldInsn(Opcodes.PUTFIELD, internal, DELEGATE_FIELD, "L" + DELEGATE + ";");
            visitor.visitInsn(Opcodes.RETURN);
            visitor.visitMaxs(0, 0);
            visitor.visitEnd();
        }

        for (int index = 0; index < methods.size(); index++) {
            Method method = methods.get(index);
            emitOverride(writer, internal, base, method, index);
            if (!Modifier.isAbstract(method.getModifiers())) emitSuperBridge(writer, base, method, index);
        }
        writer.visitEnd();

        ClassLoader parent = base.getClassLoader();
        if (parent == null) {
            for (Class<?> iface : ifaces) {
                if (iface.getClassLoader() != null) {
                    parent = iface.getClassLoader();
                    break;
                }
            }
        }
        if (parent == null) parent = Adapters.class.getClassLoader();
        Class<?> cls;
        try {
            cls = definer.define(name, writer.toByteArray(), new BridgeLoader(parent));
        } catch (LinkageError error) {
            throw JSException.typeError("Java.extend: cannot create adapter for " + base.getName()
                    + ": " + error.getMessage());
        } catch (RuntimeException error) {
            if (error instanceof JSException) throw error;
            throw JSException.typeError("Java.extend: cannot create adapter for " + base.getName()
                    + " on this platform: " + error.getMessage());
        }
        // it may be the only implementation of an interface method
        if (!ifaces.isEmpty()) ArtCha.INSTANCE.pinIfNeeded(cls);
        HashMap<List<Class<?>>, Constructor<?>> generated = new HashMap<>();
        for (Constructor<?> constructor : cls.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            generated.put(new ArrayList<>(Arrays.asList(parameters).subList(1, parameters.length)), constructor);
        }
        List<AdapterClass.ConstructorPair> pairs = new ArrayList<>(constructors.size());
        for (Constructor<?> constructor : constructors) {
            Constructor<?> adapter = generated.get(new ArrayList<>(Arrays.asList(constructor.getParameterTypes())));
            if (adapter == null) throw new NoSuchElementException(constructor.toString());
            pairs.add(new AdapterClass.ConstructorPair(constructor, adapter));
        }
        HashMap<String, List<Method>> supers = new HashMap<>();
        for (int index = 0; index < methods.size(); index++) {
            Method method = methods.get(index);
            if (Modifier.isAbstract(method.getModifiers())) continue;
            Method superMethod;
            try {
                superMethod = cls.getDeclaredMethod(superName(method, index), method.getParameterTypes());
            } catch (NoSuchMethodException missing) {
                throw new IllegalStateException("Missing generated super bridge for " + method, missing);
            }
            List<Method> list = supers.get(method.getName());
            if (list == null) {
                list = new ArrayList<>();
                supers.put(method.getName(), list);
            }
            list.add(superMethod);
        }
        return new AdapterClass(cls, methods, pairs, supers);
    }

    private static void addInterface(Class<?> iface, LinkedHashSet<Class<?>> ifaces) {
        if (!ifaces.add(iface)) return;
        for (Class<?> parent : iface.getInterfaces()) addInterface(parent, ifaces);
    }

    private static String superName(Method method, int index) {
        return "super$" + method.getName() + "$" + index;
    }

    /** Public/protected overridable instance methods (Object methods limited to toString/hashCode/equals). */
    private static List<Method> collectMethods(Class<?> base, Iterable<Class<?>> ifaces) {
        HashMap<String, Method> seen = new HashMap<>();
        Class<?> current = base;
        HashSet<String> finals = new HashSet<>();
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                String key = method.getName() + Type.getMethodDescriptor(method);
                if (Modifier.isFinal(method.getModifiers()) || Modifier.isPrivate(method.getModifiers())) {
                    finals.add(key);
                }
                if (finals.contains(key) && !Modifier.isFinal(method.getModifiers())) continue;
                consider(seen, base, method);
            }
            current = current.getSuperclass();
        }
        for (Class<?> iface : ifaces) {
            for (Method method : iface.getMethods()) {
                String key = method.getName() + Type.getMethodDescriptor(method);
                if (!finals.contains(key)) consider(seen, base, method);
            }
        }
        List<Method> methods = new ArrayList<>(seen.values());
        methods.sort(Comparator.comparing((Method method) -> method.getName() + Type.getMethodDescriptor(method)));
        return methods;
    }

    private static void consider(HashMap<String, Method> seen, Class<?> base, Method method) {
        int modifiers = method.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || method.isSynthetic() || method.isBridge()) {
            return;
        }
        if (!(Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers))) return;
        if (method.getDeclaringClass() == Object.class
                && !(method.getName().equals("toString") || method.getName().equals("hashCode")
                || method.getName().equals("equals"))) return;
        String key = method.getName() + Type.getMethodDescriptor(method);
        seen.putIfAbsent(key, method);
    }

    private static void emitOverride(ClassWriter writer, String internal, Class<?> base, Method method, int index) {
        String descriptor = Type.getMethodDescriptor(method);
        int access = Modifier.isPublic(method.getModifiers()) ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED;
        String[] exceptions = new String[method.getExceptionTypes().length];
        for (int i = 0; i < exceptions.length; i++) exceptions[i] = Type.getInternalName(method.getExceptionTypes()[i]);
        MethodVisitor visitor = writer.visitMethod(access, method.getName(), descriptor, null, exceptions);
        visitor.visitCode();
        Type[] args = Type.getArgumentTypes(descriptor);
        Type returnType = Type.getReturnType(descriptor);
        int resultSlot = 1;
        for (Type argument : args) resultSlot += argument.getSize();
        Label callSuper = new Label();
        // delegate is null while the base constructor runs
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitFieldInsn(Opcodes.GETFIELD, internal, DELEGATE_FIELD, "L" + DELEGATE + ";");
        visitor.visitJumpInsn(Opcodes.IFNULL, callSuper);
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitFieldInsn(Opcodes.GETFIELD, internal, DELEGATE_FIELD, "L" + DELEGATE + ";");
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        pushInt(visitor, index);
        pushInt(visitor, args.length);
        visitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        int slot = 1;
        for (int i = 0; i < args.length; i++) {
            Type argument = args[i];
            visitor.visitInsn(Opcodes.DUP);
            pushInt(visitor, i);
            visitor.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
            box(visitor, argument);
            visitor.visitInsn(Opcodes.AASTORE);
            slot += argument.getSize();
        }
        visitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, DELEGATE, "invoke",
                "(Ljava/lang/Object;I[Ljava/lang/Object;)Ljava/lang/Object;", true);
        visitor.visitVarInsn(Opcodes.ASTORE, resultSlot);
        visitor.visitVarInsn(Opcodes.ALOAD, resultSlot);
        visitor.visitFieldInsn(Opcodes.GETSTATIC, SUPPORT, "NOT_HANDLED", "Ljava/lang/Object;");
        visitor.visitJumpInsn(Opcodes.IF_ACMPEQ, callSuper);
        if (returnType == Type.VOID_TYPE) {
            visitor.visitInsn(Opcodes.RETURN);
        } else {
            visitor.visitVarInsn(Opcodes.ALOAD, resultSlot);
            unbox(visitor, returnType);
            visitor.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
        }
        visitor.visitLabel(callSuper);
        if (Modifier.isAbstract(method.getModifiers())) {
            visitor.visitTypeInsn(Opcodes.NEW, "java/lang/AbstractMethodError");
            visitor.visitInsn(Opcodes.DUP);
            visitor.visitLdcInsn(method.getDeclaringClass().getSimpleName() + "." + method.getName()
                    + " is not implemented by the JS adapter");
            visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AbstractMethodError", "<init>",
                    "(Ljava/lang/String;)V", false);
            visitor.visitInsn(Opcodes.ATHROW);
        } else {
            invokeSuper(visitor, base, method, args);
            visitor.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
        }
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void invokeSuper(MethodVisitor visitor, Class<?> base, Method method, Type[] args) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        int slot = 1;
        for (Type argument : args) {
            visitor.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
            slot += argument.getSize();
        }
        Class<?> declaring = method.getDeclaringClass();
        if (declaring.isInterface()) {
            visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(declaring), method.getName(),
                    Type.getMethodDescriptor(method), true);
        } else {
            visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(base), method.getName(),
                    Type.getMethodDescriptor(method), false);
        }
    }

    private static void emitSuperBridge(ClassWriter writer, Class<?> base, Method method, int index) {
        String descriptor = Type.getMethodDescriptor(method);
        // synthetic: not visible as a JS member (HostAccess skips synthetic methods); reached through Java.super()
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                superName(method, index), descriptor, null, null);
        visitor.visitCode();
        invokeSuper(visitor, base, method, Type.getArgumentTypes(descriptor));
        visitor.visitInsn(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN));
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void pushInt(MethodVisitor visitor, int value) {
        if (value >= -1 && value <= 5) {
            visitor.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            visitor.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            visitor.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            visitor.visitLdcInsn(value);
        }
    }

    private static String[] boxType(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> new String[]{"java/lang/Boolean", "booleanValue"};
            case Type.BYTE -> new String[]{"java/lang/Byte", "byteValue"};
            case Type.CHAR -> new String[]{"java/lang/Character", "charValue"};
            case Type.SHORT -> new String[]{"java/lang/Short", "shortValue"};
            case Type.INT -> new String[]{"java/lang/Integer", "intValue"};
            case Type.LONG -> new String[]{"java/lang/Long", "longValue"};
            case Type.FLOAT -> new String[]{"java/lang/Float", "floatValue"};
            case Type.DOUBLE -> new String[]{"java/lang/Double", "doubleValue"};
            default -> null;
        };
    }

    private static void box(MethodVisitor visitor, Type type) {
        String[] boxed = boxType(type);
        if (boxed == null) return;
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, boxed[0], "valueOf",
                "(" + type.getDescriptor() + ")L" + boxed[0] + ";", false);
    }

    private static void unbox(MethodVisitor visitor, Type type) {
        String[] boxed = boxType(type);
        if (boxed == null) {
            visitor.visitTypeInsn(Opcodes.CHECKCAST, type.getInternalName());
            return;
        }
        visitor.visitTypeInsn(Opcodes.CHECKCAST, boxed[0]);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, boxed[0], boxed[1],
                "()" + type.getDescriptor(), false);
    }
}
