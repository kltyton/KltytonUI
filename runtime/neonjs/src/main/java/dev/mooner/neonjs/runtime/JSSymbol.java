package dev.mooner.neonjs.runtime;

import java.util.List;
import java.util.Map;

/** Identity-based JavaScript symbol; derived from NeonJS and adapted to Java. */
public final class JSSymbol {
    public static final JSSymbol asyncIterator = new JSSymbol("Symbol.asyncIterator");
    public static final JSSymbol hasInstance = new JSSymbol("Symbol.hasInstance");
    public static final JSSymbol isConcatSpreadable = new JSSymbol("Symbol.isConcatSpreadable");
    public static final JSSymbol iterator = new JSSymbol("Symbol.iterator");
    public static final JSSymbol match = new JSSymbol("Symbol.match");
    public static final JSSymbol matchAll = new JSSymbol("Symbol.matchAll");
    public static final JSSymbol replace = new JSSymbol("Symbol.replace");
    public static final JSSymbol search = new JSSymbol("Symbol.search");
    public static final JSSymbol species = new JSSymbol("Symbol.species");
    public static final JSSymbol split = new JSSymbol("Symbol.split");
    public static final JSSymbol toPrimitive = new JSSymbol("Symbol.toPrimitive");
    public static final JSSymbol toStringTag = new JSSymbol("Symbol.toStringTag");
    public static final JSSymbol unscopables = new JSSymbol("Symbol.unscopables");
    public static final JSSymbol dispose = new JSSymbol("Symbol.dispose");
    public static final JSSymbol asyncDispose = new JSSymbol("Symbol.asyncDispose");
    public static final JSSymbol metadata = new JSSymbol("Symbol.metadata");

    public static final List<Map.Entry<String, JSSymbol>> wellKnown = List.of(
            Map.entry("asyncIterator", asyncIterator), Map.entry("hasInstance", hasInstance),
            Map.entry("isConcatSpreadable", isConcatSpreadable), Map.entry("iterator", iterator),
            Map.entry("match", match), Map.entry("matchAll", matchAll), Map.entry("replace", replace),
            Map.entry("search", search), Map.entry("species", species), Map.entry("split", split),
            Map.entry("toPrimitive", toPrimitive), Map.entry("toStringTag", toStringTag),
            Map.entry("unscopables", unscopables), Map.entry("dispose", dispose),
            Map.entry("asyncDispose", asyncDispose), Map.entry("metadata", metadata));

    private final String description;
    private final String registryKey;

    public JSSymbol(String description) {
        this(description, null);
    }

    public JSSymbol(String description, String registryKey) {
        this.description = description;
        this.registryKey = registryKey;
    }

    public String getDescription() {
        return description;
    }

    public String getRegistryKey() {
        return registryKey;
    }

    public String functionName() {
        return description == null ? "" : "[" + description + "]";
    }

    @Override
    public String toString() {
        return "Symbol(" + (description == null ? "" : description) + ")";
    }
}
