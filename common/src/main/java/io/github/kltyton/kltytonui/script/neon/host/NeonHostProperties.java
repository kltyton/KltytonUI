package io.github.kltyton.kltytonui.script.neon.host;

import io.github.kltyton.kltytonui.script.host.HideFromScript;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Dynamic browser keys supplement the SDK's public field and bean properties. */
record NeonHostProperties(boolean genericGetter, boolean genericSetter) {
    private static final ClassValue<NeonHostProperties> CACHE = new ClassValue<>() {
        @Override protected NeonHostProperties computeValue(Class<?> type) {
            boolean getter = false;
            boolean setter = false;
            for (Method method : type.getMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.isAnnotationPresent(HideFromScript.class)) continue;
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 0 || parameters[0] != String.class && parameters[0] != Object.class) continue;
                getter |= method.getName().equals("get") && parameters.length == 1;
                setter |= method.getName().equals("set") && parameters.length == 2;
            }
            return new NeonHostProperties(getter, setter);
        }
    };
    static NeonHostProperties of(Object value) {
        return value instanceof Class<?> ? new NeonHostProperties(false, false) : CACHE.get(value.getClass());
    }
}

