package dev.mooner.neonjs.unicode;

import dev.mooner.neonjs.runtime.concurrent.LazyValue;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** URL-standard UTS #46 processing; derived from NeonJS and adapted to Java. */
public final class Idna {
    private static final int VALID = 0;
    private static final int MAPPED = 1;
    private static final int IGNORED = 3;
    private static final int DEVIATION = 4;

    private static final LazyValue<Runs> STATUS = new LazyValue<>(() -> runs(IdnaData.STATUS, 3));
    private static final LazyValue<int[][]> MAPPINGS = new LazyValue<>(Idna::mappings);
    private static final LazyValue<Runs> BIDI = new LazyValue<>(() -> runs(IdnaData.BIDI, 2));
    private static final LazyValue<Runs> JOINING = new LazyValue<>(() -> runs(IdnaData.JOINING, 2));
    private static final LazyValue<int[]> VIRAMA = new LazyValue<>(() -> UnicodeTables.decodeSet(IdnaData.VIRAMA));
    private static final LazyValue<int[]> MARKS = new LazyValue<>(() -> UnicodeTables.generalCategory("M"));

    private Idna() {
    }

    private static Runs runs(String encoded, int width) {
        int[] values = UnicodeTables.decodeInts(encoded);
        int count = values.length / width;
        int[] starts = new int[count];
        int[] a = new int[count];
        int[] b = width == 3 ? new int[count] : null;
        int at = 0;
        for (int i = 0; i < count; i++) {
            at += values[i * width];
            starts[i] = at;
            a[i] = values[i * width + 1];
            if (b != null) b[i] = values[i * width + 2];
        }
        return new Runs(starts, a, b);
    }

    private static int[][] mappings() {
        int[] values = UnicodeTables.decodeInts(IdnaData.MAPPINGS);
        int[][] mappings = new int[values[0]][];
        int offset = 1;
        for (int i = 0; i < mappings.length; i++) {
            int length = values[offset++];
            mappings[i] = Arrays.copyOfRange(values, offset, offset + length);
            offset += length;
        }
        return mappings;
    }

    private static int statusOf(int codePoint) {
        Runs status = STATUS.get();
        return status.a[status.index(codePoint)];
    }

    private static String bidiOf(int codePoint) {
        Runs bidi = BIDI.get();
        return IdnaData.BIDI_CLASSES[bidi.a[bidi.index(codePoint)]];
    }

    private static String joiningOf(int codePoint) {
        Runs joining = JOINING.get();
        return IdnaData.JOINING_TYPES[joining.a[joining.index(codePoint)]];
    }

    private static boolean ascii(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 0x7F) return false;
        return true;
    }

    public static String domainToAscii(String domain) {
        String result = ascii(domain) ? domain.toLowerCase(Locale.ROOT) : toAscii(domain);
        return result == null || result.isEmpty() ? null : result;
    }

    public static String toAscii(String domain) {
        boolean error = false;
        StringBuilder mapped = new StringBuilder(domain.length());
        for (int i = 0; i < domain.length();) {
            int cp = domain.codePointAt(i);
            i += Character.charCount(cp);
            int status = statusOf(cp);
            if (status == MAPPED) {
                Runs table = STATUS.get();
                for (int replacement : MAPPINGS.get()[table.b[table.index(cp)]]) mapped.appendCodePoint(replacement);
            } else if (status != IGNORED) {
                mapped.appendCodePoint(cp);
            }
        }
        String[] labels = Normalizer.normalize(mapped, Normalizer.Form.NFC).split("\\.", -1);
        for (int i = 0; i < labels.length; i++) {
            String label = labels[i];
            if (label.startsWith("xn--")) {
                if (!ascii(label)) {
                    error = true;
                    continue;
                }
                String decoded = Punycode.decode(label.substring(4));
                if (decoded == null || decoded.isEmpty() || ascii(decoded)) {
                    error = true;
                    continue;
                }
                labels[i] = decoded;
            }
        }
        boolean bidiDomain = false;
        for (String label : labels) {
            if (label.codePoints().anyMatch(cp -> {
                String bidi = bidiOf(cp);
                return bidi.equals("R") || bidi.equals("AL") || bidi.equals("AN");
            })) {
                bidiDomain = true;
                break;
            }
        }
        for (String label : labels) if (!label.isEmpty() && !valid(label, bidiDomain)) error = true;
        if (error) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) out.append('.');
            String label = labels[i];
            if (!ascii(label)) {
                String encoded = Punycode.encode(label);
                if (encoded == null) return null;
                out.append("xn--").append(encoded);
            } else {
                out.append(label);
            }
        }
        return out.toString();
    }

    private static boolean valid(String label, boolean bidiDomain) {
        if (!Normalizer.isNormalized(label, Normalizer.Form.NFC)) return false;
        if (label.startsWith("xn--") || label.indexOf('.') >= 0) return false;
        int[] points = label.codePoints().toArray();
        if (CharRanges.contains(MARKS.get(), points[0])) return false;
        for (int point : points) {
            int status = statusOf(point);
            if (status != VALID && status != DEVIATION) return false;
        }
        return contextJ(points) && (!bidiDomain || bidiRule(points));
    }

    private static boolean contextJ(int[] points) {
        for (int i = 0; i < points.length; i++) {
            int point = points[i];
            if (point != 0x200C && point != 0x200D) continue;
            if (i > 0 && CharRanges.contains(VIRAMA.get(), points[i - 1])) continue;
            if (point == 0x200D) return false;
            int j = i - 1;
            while (j >= 0 && joiningOf(points[j]).equals("T")) j--;
            if (j < 0) return false;
            String before = joiningOf(points[j]);
            if (!before.equals("L") && !before.equals("D")) return false;
            j = i + 1;
            while (j < points.length && joiningOf(points[j]).equals("T")) j++;
            if (j >= points.length) return false;
            String after = joiningOf(points[j]);
            if (!after.equals("R") && !after.equals("D")) return false;
        }
        return true;
    }

    private static boolean bidiRule(int[] points) {
        String[] classes = new String[points.length];
        for (int i = 0; i < points.length; i++) classes[i] = bidiOf(points[i]);
        String first = classes[0];
        if (!first.equals("L") && !first.equals("R") && !first.equals("AL")) return false;
        int end = classes.length - 1;
        while (end > 0 && classes[end].equals("NSM")) end--;
        if (first.equals("R") || first.equals("AL")) {
            Set<String> allowed = Set.of("R", "AL", "AN", "EN", "ES", "CS", "ET", "ON", "BN", "NSM");
            for (String bidi : classes) if (!allowed.contains(bidi)) return false;
            if (!Set.of("R", "AL", "EN", "AN").contains(classes[end])) return false;
            if (Arrays.asList(classes).contains("EN") && Arrays.asList(classes).contains("AN")) return false;
        } else {
            Set<String> allowed = Set.of("L", "EN", "ES", "CS", "ET", "ON", "BN", "NSM");
            for (String bidi : classes) if (!allowed.contains(bidi)) return false;
            if (!classes[end].equals("L") && !classes[end].equals("EN")) return false;
        }
        return true;
    }

    private record Runs(int[] starts, int[] a, int[] b) {
        private int index(int codePoint) {
            int index = Arrays.binarySearch(starts, codePoint);
            return index < 0 ? -index - 2 : index;
        }
    }
}
