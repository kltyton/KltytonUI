/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.builtins.temporal.JSTemporalDuration;
import dev.mooner.neonjs.builtins.temporal.JSTemporalInstant;
import dev.mooner.neonjs.builtins.temporal.JSTemporalPlainDate;
import dev.mooner.neonjs.builtins.temporal.JSTemporalPlainDateTime;
import dev.mooner.neonjs.builtins.temporal.JSTemporalPlainMonthDay;
import dev.mooner.neonjs.builtins.temporal.JSTemporalPlainTime;
import dev.mooner.neonjs.builtins.temporal.JSTemporalPlainYearMonth;
import dev.mooner.neonjs.builtins.temporal.JSTemporalZonedDateTime;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;

import java.util.function.Predicate;

/**
 * The ECMA-402 {@code toLocaleString} of the Temporal types, replacing core's locale-independent versions: the date-time
 * types format through a DateTimeFormat created with the type's required / default fields, ZonedDateTime in its own
 * time zone, and Duration through DurationFormat.
 */
final class TemporalLocale {
    private TemporalLocale() {
    }

    static void install(Realm realm) {
        installViaDateTimeFormat(realm, "%Temporal.PlainDate.prototype%", "PlainDate", "date", "date",
                value -> value instanceof JSTemporalPlainDate);
        installViaDateTimeFormat(realm, "%Temporal.PlainDateTime.prototype%", "PlainDateTime", "any", "all",
                value -> value instanceof JSTemporalPlainDateTime);
        installViaDateTimeFormat(realm, "%Temporal.PlainTime.prototype%", "PlainTime", "time", "time",
                value -> value instanceof JSTemporalPlainTime);
        installViaDateTimeFormat(realm, "%Temporal.PlainYearMonth.prototype%", "PlainYearMonth", "date", "date",
                value -> value instanceof JSTemporalPlainYearMonth);
        installViaDateTimeFormat(realm, "%Temporal.PlainMonthDay.prototype%", "PlainMonthDay", "date", "date",
                value -> value instanceof JSTemporalPlainMonthDay);
        installViaDateTimeFormat(realm, "%Temporal.Instant.prototype%", "Instant", "any", "all",
                value -> value instanceof JSTemporalInstant);

        JSObject zonedPrototype = realm.getIntrinsics().get("%Temporal.ZonedDateTime.prototype%");
        if (zonedPrototype != null) {
            Dsl.method(zonedPrototype, realm, "toLocaleString", 0, (function, thisArg, args, newTarget) -> {
                if (!(thisArg instanceof JSTemporalZonedDateTime)) {
                    throw Dsl.typeErr("Temporal.ZonedDateTime.prototype.toLocaleString"
                            + " called on incompatible receiver " + Ops.describe(thisArg));
                }
                JSTemporalZonedDateTime zoned = (JSTemporalZonedDateTime) thisArg;
                Realm r = function.realm;
                // the time zone is the ZonedDateTime's own; a timeZone option is a TypeError
                JSIntlDateTimeFormat dtf = DtfCreate.create(r, r.intrinsic("%Intl.DateTimeFormat%"),
                        Values.arg(args, 0), Values.arg(args, 1), "any", "all", zoned.timeZone.id);
                if (!"iso8601".equals(zoned.calendar) && !zoned.calendar.equals(dtf.calendar)) {
                    throw Dsl.rangeErr("Calendar " + zoned.calendar
                            + " does not match the DateTimeFormat calendar " + dtf.calendar);
                }
                return DtfFormatting.format(r, dtf, new JSTemporalInstant(null, zoned.epochNs));
            });
        }

        JSObject durationPrototype = realm.getIntrinsics().get("%Temporal.Duration.prototype%");
        if (durationPrototype != null) {
            Dsl.method(durationPrototype, realm, "toLocaleString", 0, (function, thisArg, args, newTarget) -> {
                if (!(thisArg instanceof JSTemporalDuration)) {
                    throw Dsl.typeErr("Temporal.Duration.prototype.toLocaleString"
                            + " called on incompatible receiver " + Ops.describe(thisArg));
                }
                return DurationFormatBuiltins.format(function.realm, Values.arg(args, 0),
                        Values.arg(args, 1), thisArg);
            });
        }
    }

    private static void installViaDateTimeFormat(Realm realm, String protoName, String type, String required,
                                                 String defaults, Predicate<Object> isType) {
        JSObject proto = realm.getIntrinsics().get(protoName);
        if (proto == null) {
            return;
        }
        Dsl.method(proto, realm, "toLocaleString", 0, (function, thisArg, args, newTarget) -> {
            if (!isType.test(thisArg)) {
                throw Dsl.typeErr("Temporal." + type + ".prototype.toLocaleString"
                        + " called on incompatible receiver " + Ops.describe(thisArg));
            }
            Realm r = function.realm;
            Object locales = Values.arg(args, 0);
            Object options = Values.arg(args, 1);
            JSObject constructor = r.intrinsic("%Intl.DateTimeFormat%");
            JSIntlDateTimeFormat dtf;
            if (locales == Undefined.INSTANCE && options == Undefined.INSTANCE) {
                dtf = IntlState.of(r).cached("dtf:" + required + ":" + defaults,
                        () -> DtfCreate.create(r, constructor, Undefined.INSTANCE, Undefined.INSTANCE,
                                required, defaults));
            } else {
                dtf = DtfCreate.create(r, constructor, locales, options, required, defaults);
            }
            return DtfFormatting.format(r, dtf, thisArg);
        });
    }
}
