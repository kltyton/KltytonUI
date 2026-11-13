package dev.mooner.neonjs.unicode;

import dev.mooner.neonjs.runtime.concurrent.LazyValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Lazily decodes Unicode 17 identifier, property and case-folding data.
 * Derived from NeonJS and adapted to Java.
 */
public final class UnicodeTables {
    public static final UnicodeTables INSTANCE = new UnicodeTables();
    private static final int HALF = 46;
    private static final int[] DIGIT_VALUE = digitValues();
    private static final LazyValue<Table> GC = new LazyValue<>(
            () -> new Table(UnicodeData.GC_NAMES, UnicodeData.GC_DATA));
    private static final LazyValue<Table> SCRIPTS = new LazyValue<>(
            () -> new Table(UnicodeData.SCRIPT_NAMES, UnicodeData.SCRIPT_DATA));
    private static final LazyValue<Table> SCRIPT_EXTENSIONS = new LazyValue<>(
            () -> new Table(UnicodeData.SCRIPT_NAMES, UnicodeData.SCX_DATA));
    private static final LazyValue<Table> BINARY = new LazyValue<>(
            () -> new Table(UnicodeData.BINARY_NAMES, UnicodeData.BINARY_DATA));
    private static final LazyValue<int[]> ID_START = new LazyValue<>(() -> BINARY.get().lookup("ID_Start"));
    private static final LazyValue<int[]> ID_CONTINUE = new LazyValue<>(() -> BINARY.get().lookup("ID_Continue"));
    private static final LazyValue<int[]> SPACE_SEPARATOR = new LazyValue<>(() -> GC.get().lookup("Zs"));
    private static final AtomicReferenceArray<StringProperty> STRING_PROPERTIES =
            new AtomicReferenceArray<>(UnicodeData.SEQ_NAMES.length + 1);
    private static final LazyValue<CaseMap> SIMPLE_FOLDING = new LazyValue<>(
            () -> decodeMap(UnicodeData.SIMPLE_CASE_FOLDING));
    private static final LazyValue<CaseMap> NON_UNICODE_CANONICAL = new LazyValue<>(
            () -> decodeMap(UnicodeData.NON_UNICODE_CANONICALIZE));

    private UnicodeTables() {
    }

    private static int[] digitValues() {
        int[] values = new int[128];
        int digit = 0;
        for (int c = 0x20; c < 0x7F; c++) {
            if (c == '"' || c == '$' || c == '\\') continue;
            values[c] = digit++;
        }
        return values;
    }

    public static int[] decodeInts(String encoded) {
        int[] out = new int[Math.max(16, encoded.length() / 2)];
        int count = 0;
        int value = 0;
        for (int i = 0; i < encoded.length(); i++) {
            int digit = DIGIT_VALUE[encoded.charAt(i)];
            if (digit >= HALF) {
                value = value * HALF + digit - HALF;
            } else {
                if (count == out.length) out = Arrays.copyOf(out, out.length * 2);
                out[count++] = value * HALF + digit;
                value = 0;
            }
        }
        return Arrays.copyOf(out, count);
    }

    public static int[] decodeSet(String encoded) {
        int[] values = decodeInts(encoded);
        for (int i = 1; i < values.length; i++) values[i] += values[i - 1] + 1;
        return values;
    }

    public static boolean isIdStart(int codePoint) {
        return CharRanges.contains(ID_START.get(), codePoint);
    }

    public static boolean isIdContinue(int codePoint) {
        return CharRanges.contains(ID_CONTINUE.get(), codePoint);
    }

    public static boolean isSpaceSeparator(int codePoint) {
        return CharRanges.contains(SPACE_SEPARATOR.get(), codePoint);
    }

    public static int[] generalCategory(String value) {
        return GC.get().lookup(value);
    }

    public static int[] script(String value, boolean extensions) {
        return (extensions ? SCRIPT_EXTENSIONS : SCRIPTS).get().lookup(value);
    }

    public static int[] binaryProperty(String name) {
        return BINARY.get().lookup(name);
    }

    public static StringProperty stringProperty(String name) {
        int index = name.equals("RGI_Emoji") ? UnicodeData.SEQ_NAMES.length
                : Arrays.asList(UnicodeData.SEQ_NAMES).indexOf(name);
        if (index < 0) return null;
        StringProperty known = STRING_PROPERTIES.get(index);
        if (known != null) return known;
        StringProperty property;
        if (index == UnicodeData.SEQ_NAMES.length) {
            int[] characters = CharRanges.EMPTY;
            List<int[]> strings = new ArrayList<>();
            for (String sequenceName : UnicodeData.SEQ_NAMES) {
                StringProperty sequence = stringProperty(sequenceName);
                characters = CharRanges.INSTANCE.union(characters, sequence.chars);
                strings.addAll(sequence.strings);
            }
            property = new StringProperty(characters, strings);
        } else {
            int[] values = decodeInts(UnicodeData.SEQ_STRINGS[index]);
            List<int[]> strings = new ArrayList<>(values[0]);
            int offset = 1;
            for (int i = 0; i < values[0]; i++) {
                int length = values[offset++];
                strings.add(Arrays.copyOfRange(values, offset, offset + length));
                offset += length;
            }
            property = new StringProperty(decodeSet(UnicodeData.SEQ_CHARS[index]), strings);
        }
        STRING_PROPERTIES.compareAndSet(index, null, property);
        return STRING_PROPERTIES.get(index);
    }

    private static CaseMap decodeMap(String encoded) {
        int[] values = decodeInts(encoded);
        int count = values.length / 2;
        int[] keys = new int[count];
        int[] mapped = new int[count];
        int previous = -1;
        for (int i = 0; i < count; i++) {
            int key = previous + 1 + values[2 * i];
            int zigzag = values[2 * i + 1];
            int delta = (zigzag & 1) == 0 ? zigzag >>> 1 : -((zigzag + 1) >>> 1);
            keys[i] = key;
            mapped[i] = key + delta;
            previous = key;
        }
        return new CaseMap(keys, mapped);
    }

    public static CaseMap getSimpleFolding() {
        return SIMPLE_FOLDING.get();
    }

    public static CaseMap getNonUnicodeCanonical() {
        return NON_UNICODE_CANONICAL.get();
    }

    public static int canonicalize(int codePoint, boolean unicode) {
        if (codePoint < 128) {
            if (unicode) return codePoint >= 'A' && codePoint <= 'Z' ? codePoint + 32 : codePoint;
            return codePoint >= 'a' && codePoint <= 'z' ? codePoint - 32 : codePoint;
        }
        return unicode ? SIMPLE_FOLDING.get().map(codePoint)
                : codePoint < 0x10000 ? NON_UNICODE_CANONICAL.get().map(codePoint) : codePoint;
    }

    public static int[] canonicalizeSet(int[] set, boolean unicode) {
        if (set.length == 0) return set;
        CaseMap map = (unicode ? SIMPLE_FOLDING : NON_UNICODE_CANONICAL).get();
        CharRanges.Builder builder = new CharRanges.Builder();
        boolean changed = false;
        if (CharRanges.INSTANCE.size(set) <= map.keys.length) {
            int runStart = -1;
            int runEnd = -1;
            for (int i = 0; i < set.length; i += 2) {
                for (int c = set[i]; c < set[i + 1]; c++) {
                    int mapped = canonicalize(c, unicode);
                    if (mapped != c) changed = true;
                    if (mapped == runEnd) {
                        runEnd++;
                    } else {
                        if (runStart >= 0) builder.addRange(runStart, runEnd - 1);
                        runStart = mapped;
                        runEnd = mapped + 1;
                    }
                }
            }
            if (runStart >= 0) builder.addRange(runStart, runEnd - 1);
            return changed ? builder.build() : set;
        }
        CharRanges.Builder removed = new CharRanges.Builder();
        for (int i = 0; i < map.keys.length; i++) {
            int codePoint = map.keys[i];
            if (CharRanges.contains(set, codePoint)) {
                removed.add(codePoint);
                builder.add(map.values[i]);
            }
        }
        int[] removedSet = removed.build();
        if (removedSet.length == 0) return set;
        return CharRanges.INSTANCE.union(CharRanges.INSTANCE.subtract(set, removedSet), builder.build());
    }

    private static final class Table {
        private final Map<String, Integer> index = new HashMap<>();
        private final String[] data;
        private final AtomicReferenceArray<int[]> cache;

        private Table(String[] names, String[] data) {
            this.data = data;
            cache = new AtomicReferenceArray<>(data.length);
            for (int i = 0; i < names.length; i++) {
                for (String alias : names[i].split(",", -1)) index.put(alias, i);
            }
        }

        private int[] get(int i) {
            int[] known = cache.get(i);
            if (known != null) return known;
            int[] decoded = decodeSet(data[i]);
            cache.compareAndSet(i, null, decoded);
            return cache.get(i);
        }

        private int[] lookup(String name) {
            Integer i = index.get(name);
            return i == null ? null : get(i);
        }
    }

    public static final class StringProperty {
        public final int[] chars;
        public final List<int[]> strings;

        public StringProperty(int[] chars, List<int[]> strings) {
            this.chars = chars;
            this.strings = strings;
        }
    }

    public static final class CaseMap {
        public final int[] keys;
        public final int[] values;
        private final char[] bmp = new char[0x10000];
        private final boolean bmpOverflow;

        public CaseMap(int[] keys, int[] values) {
            this.keys = keys;
            this.values = values;
            for (int i = 0; i < bmp.length; i++) bmp[i] = (char) i;
            boolean overflow = false;
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] >= 0x10000) continue;
                if (values[i] >= 0x10000) overflow = true;
                else bmp[keys[i]] = (char) values[i];
            }
            bmpOverflow = overflow;
        }

        public int map(int codePoint) {
            if (codePoint < 0x10000 && !bmpOverflow) return bmp[codePoint];
            int index = Arrays.binarySearch(keys, codePoint);
            return index >= 0 ? values[index] : codePoint;
        }
    }
}
