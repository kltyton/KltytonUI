/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import java.util.ArrayList;
import java.util.List;

/** Option tables of CreateDateTimeFormat (Table 15, in table order). */
final class DtfTables {
    private DtfTables() {
    }

    /** One table row: component name and its allowed string values (null for a numeric option). */
    static final class Component {
        final String name;
        final String[] values;

        Component(String name, String[] values) {
            this.name = name;
            this.values = values;
        }
    }

    static final List<Component> COMPONENTS = List.of(
            new Component("weekday", new String[]{"narrow", "short", "long"}),
            new Component("era", new String[]{"narrow", "short", "long"}),
            new Component("year", new String[]{"2-digit", "numeric"}),
            new Component("month", new String[]{"2-digit", "numeric", "narrow", "short", "long"}),
            new Component("day", new String[]{"2-digit", "numeric"}),
            new Component("dayPeriod", new String[]{"narrow", "short", "long"}),
            new Component("hour", new String[]{"2-digit", "numeric"}),
            new Component("minute", new String[]{"2-digit", "numeric"}),
            new Component("second", new String[]{"2-digit", "numeric"}),
            new Component("fractionalSecondDigits", null),
            new Component("timeZoneName", new String[]{"short", "long", "shortOffset", "longOffset",
                    "shortGeneric", "longGeneric"}));

    static final List<String> ORDER;

    static {
        List<String> order = new ArrayList<>();
        for (Component component : COMPONENTS) {
            order.add(component.name);
        }
        ORDER = List.copyOf(order);
    }

    static final String[] STYLES = {"full", "long", "medium", "short"};
    static final String[] HOUR_CYCLES = {"h11", "h12", "h23", "h24"};
    static final String[] MATCHERS = {"basic", "best fit"};

    static final List<String> DATE_FIELDS = List.of("weekday", "year", "month", "day");
    static final List<String> TIME_FIELDS = List.of("dayPeriod", "hour", "minute", "second",
            "fractionalSecondDigits");
}
