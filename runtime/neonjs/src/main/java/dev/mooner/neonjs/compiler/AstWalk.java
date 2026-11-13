/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

import dev.mooner.neonjs.parser.Node;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Generic AST child traversal (reflection-based, cached per node class). */
public final class AstWalk {
    public static final AstWalk INSTANCE = new AstWalk();

    private static final ConcurrentHashMap<Class<?>, List<Field>> CACHE = new ConcurrentHashMap<>();

    private AstWalk() {
    }

    private static List<Field> fields(Class<?> type) {
        return CACHE.computeIfAbsent(type, cls -> {
            List<Field> out = new ArrayList<>();
            Class<?> current = cls;
            while (current != null && current != Object.class) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (field.getName().equals("scope") || field.getName().equals("ref")) {
                        continue;
                    }
                    if (Node.class.isAssignableFrom(field.getType())
                            || List.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        out.add(field);
                    }
                }
                current = current.getSuperclass();
            }
            return out;
        });
    }

    public static void forEachChild(Node node, Consumer<Node> consumer) {
        for (Field field : fields(node.getClass())) {
            Object value;
            try {
                value = field.get(node);
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("Cannot read AST field " + field.getName(), error);
            }
            if (value instanceof Node child) {
                consumer.accept(child);
            } else if (value instanceof List<?> list) {
                for (Object element : list) {
                    if (element instanceof Node child) {
                        consumer.accept(child);
                    }
                }
            }
        }
    }
}
