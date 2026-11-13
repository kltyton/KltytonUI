/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.ext;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import static dev.mooner.neonjs.runtime.Values.arg;

/** {@code performance} (High Resolution Time). */
final class Performance {
    private static final double RESOLUTION_MS = 0.005;

    private Performance() {
    }

    static final class JSPerformance extends Events.JSEventTarget {
        JSPerformance(JSObject prototype) {
            super(prototype);
        }

        @Override
        public String getClassName() {
            return "Performance";
        }
    }

    /** Starts the realm's clock. */
    static void startClock(Realm realm) {
        realm.intrinsicsAny.put("%TimeOrigin%", System.nanoTime());
        realm.intrinsicsAny.put("%TimeOriginMillis%", realm.agent.currentTimeMillis());
    }

    /** DOMHighResTimeStamp: milliseconds since the realm's time origin, coarsened. */
    static double now(Realm realm) {
        if (realm.agent.clock != null) return 0.0;
        Object stored = realm.intrinsicsAny.get("%TimeOrigin%");
        long origin;
        if (stored instanceof Long number) origin = number;
        else {
            origin = System.nanoTime();
            realm.intrinsicsAny.put("%TimeOrigin%", origin);
        }
        double milliseconds = (System.nanoTime() - origin) / 1e6;
        return Math.floor(milliseconds / RESOLUTION_MS) * RESOLUTION_MS;
    }

    static void install(Realm realm) {
        WebInterface iface = WebInterface.define(realm, "Performance", 0, "EventTarget");
        iface.operation("now", 0, (function, receiver, arguments, newTarget) -> {
            Idl.self(JSPerformance.class, receiver, "Performance", "now");
            return now(function.realm);
        });
        iface.attribute("timeOrigin", (function, receiver, arguments, newTarget) -> {
            Idl.self(JSPerformance.class, receiver, "Performance", "timeOrigin");
            return function.realm.intrinsicsAny.get("%TimeOriginMillis%");
        });
        iface.operation("toJSON", 0, (function, receiver, arguments, newTarget) -> {
            Idl.self(JSPerformance.class, receiver, "Performance", "toJSON");
            JSObject object = new JSObject(function.realm.getObjectPrototype());
            object.createDataProperty("timeOrigin", function.realm.intrinsicsAny.get("%TimeOriginMillis%"));
            return object;
        });
        JSPerformance performance = new JSPerformance(iface.proto);
        NativeFunction getter = new NativeFunction(realm, "performance", 0,
                (function, receiver, arguments, newTarget) -> performance,
                false, realm.getFunctionPrototype(), "get");
        NativeFunction setter = new NativeFunction(realm, "performance", 1,
                (function, receiver, arguments, newTarget) -> {
                    JSObject target = receiver instanceof JSObject object ? object : realm.getGlobalObject();
                    target.defineOwnProperty("performance",
                            PropertyDescriptor.data(arg(arguments, 0), Attr.ALL));
                    return Undefined.INSTANCE;
                }, false, realm.getFunctionPrototype(), "set");
        realm.getGlobalObject().defineAccessor("performance", getter, setter,
                Attr.ENUMERABLE | Attr.CONFIGURABLE);
    }
}
