/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.builtins.BuiltinsKt;
import dev.mooner.neonjs.builtins.JSDate;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;

/** Installs the Temporal namespace object, its constructors and Temporal.Now. */
public final class TemporalBuiltins {
    public static final TemporalBuiltins INSTANCE = new TemporalBuiltins();

    private TemporalBuiltins() {
    }

    public static void install(Realm realm) {
        JSObject temporal = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%Temporal%", temporal);
        temporal.defineOwn(JSSymbol.toStringTag, "Temporal", Attr.CONFIGURABLE);
        InstantBuiltins.install(realm, temporal);
        PlainDateTimeBuiltins.install(realm, temporal);
        PlainDateBuiltins.install(realm, temporal);
        PlainTimeBuiltins.install(realm, temporal);
        PlainYearMonthBuiltins.install(realm, temporal);
        PlainMonthDayBuiltins.install(realm, temporal);
        DurationBuiltins.install(realm, temporal);
        ZonedDateTimeBuiltins.install(realm, temporal);
        installNow(realm, temporal);
        BuiltinsKt.global(realm, "Temporal", temporal);
    }

    private static IsoDateTime systemDateTime(Realm realm, Object tzLike) {
        TimeZone tz = tzLike == Undefined.INSTANCE ? TZ.system(realm) : TZ.toTimeZone(tzLike);
        return TZ.isoDateTimeFor(tz, InstantBuiltins.systemNs(realm));
    }

    private static void installNow(Realm realm, JSObject temporal) {
        JSObject now = new JSObject(realm.getObjectPrototype());
        now.defineOwn(JSSymbol.toStringTag, "Temporal.Now", Attr.CONFIGURABLE);
        BuiltinsKt.method(now, realm, "timeZoneId", 0,
                (function, thisArg, args, newTarget) -> TZ.system(function.realm).id);
        BuiltinsKt.method(now, realm, "instant", 0,
                (function, thisArg, args, newTarget) -> TCreate.instant(InstantBuiltins.systemNs(function.realm)));
        BuiltinsKt.method(now, realm, "plainDateTimeISO", 0, (function, thisArg, args, newTarget) ->
                TCreate.plainDateTime(systemDateTime(function.realm, Values.arg(args, 0)), TCal.ISO));
        BuiltinsKt.method(now, realm, "zonedDateTimeISO", 0, (function, thisArg, args, newTarget) -> {
            Object v = Values.arg(args, 0);
            TimeZone tz = v == Undefined.INSTANCE ? TZ.system(function.realm) : TZ.toTimeZone(v);
            return TCreate.zoned(InstantBuiltins.systemNs(function.realm), tz, TCal.ISO);
        });
        BuiltinsKt.method(now, realm, "plainDateISO", 0, (function, thisArg, args, newTarget) ->
                TCreate.plainDate(systemDateTime(function.realm, Values.arg(args, 0)).date, TCal.ISO));
        BuiltinsKt.method(now, realm, "plainTimeISO", 0, (function, thisArg, args, newTarget) ->
                TCreate.plainTime(systemDateTime(function.realm, Values.arg(args, 0)).time));
        temporal.defineOwn("Now", now, Attr.WC);
    }

    /** Date.prototype.toTemporalInstant */
    public static Object dateToTemporalInstant(Object thisValue) {
        if (!(thisValue instanceof JSDate)) {
            throw JSException.typeError(
                    "Date.prototype.toTemporalInstant called on incompatible receiver");
        }
        JSDate d = (JSDate) thisValue;
        return TCreate.instant(InstantBuiltins.msToNs(d.timeValue));
    }
}
