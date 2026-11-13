package io.github.kltyton.kltytonui.script.neon.host;

import io.github.kltyton.kltytonui.script.neon.NeonDocumentRuntime;
import dev.mooner.neonjs.NeonValue;
import dev.mooner.neonjs.runtime.JSException;

/** A Java view which retains the original JavaScript value. */
public interface NeonScriptValue {
    NeonDocumentRuntime owner();
    NeonValue toGuestValue();

    default String toDisplayString() {
        return owner().accessValue(context -> {
            NeonValue value = toGuestValue();
            if (value.isError()) {
                JSException failure = new JSException(owner().engine().raw(value));
                String stack = failure.getJsStack();
                return stack == null || stack.isBlank() ? failure.describe() : failure.describe() + "\n" + stack;
            }
            return value.toString();
        });
    }
}

