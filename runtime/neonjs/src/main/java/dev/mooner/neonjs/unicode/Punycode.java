package dev.mooner.neonjs.unicode;

import dev.mooner.neonjs.runtime.Agent;
import java.util.ArrayList;
import java.util.List;

/** RFC 3492 encoding with execution-limit checks; derived from NeonJS and adapted to Java. */
public final class Punycode {
    private static final int BASE = 36;
    private static final int TMIN = 1;
    private static final int TMAX = 26;
    private static final int SKEW = 38;
    private static final int DAMP = 700;
    private static final int INITIAL_BIAS = 72;
    private static final int INITIAL_N = 128;

    private Punycode() {
    }

    private static int adapt(int original, int count, boolean first) {
        int delta = first ? original / DAMP : original / 2;
        delta += delta / count;
        int k = 0;
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta /= BASE - TMIN;
            k += BASE;
        }
        return k + (BASE - TMIN + 1) * delta / (delta + SKEW);
    }

    private static int digit(char c) {
        if (c >= '0' && c <= '9') return c - '0' + 26;
        if (c >= 'a' && c <= 'z') return c - 'a';
        if (c >= 'A' && c <= 'Z') return c - 'A';
        return -1;
    }

    private static char digitChar(int value) {
        return (char) (value < 26 ? 'a' + value : '0' + value - 26);
    }

    private static void tick(int i) {
        if ((i & 0x3FF) == 0x3FF) {
            Agent agent = Agent.current.get();
            if (agent != null) agent.checkInterrupt();
        }
    }

    public static String decode(String input) {
        for (int i = 0; i < input.length(); i++) if (input.charAt(i) > 0x7F) return null;
        List<Integer> out = new ArrayList<>();
        int basicEnd = input.lastIndexOf('-');
        if (basicEnd > 0) for (int i = 0; i < basicEnd; i++) out.add((int) input.charAt(i));
        int n = INITIAL_N;
        long i = 0;
        int bias = INITIAL_BIAS;
        int position = basicEnd > 0 ? basicEnd + 1 : 0;
        while (position < input.length()) {
            long old = i;
            long weight = 1;
            int k = BASE;
            while (true) {
                if (position >= input.length()) return null;
                int value = digit(input.charAt(position++));
                if (value < 0) return null;
                i += value * weight;
                if (i > Integer.MAX_VALUE) return null;
                int threshold = k <= bias ? TMIN : k >= bias + TMAX ? TMAX : k - bias;
                if (value < threshold) break;
                weight *= BASE - threshold;
                if (weight > Integer.MAX_VALUE) return null;
                k += BASE;
            }
            bias = adapt((int) (i - old), out.size() + 1, old == 0);
            n += (int) (i / (out.size() + 1));
            if (n > 0x10FFFF || n < 0) return null;
            i %= out.size() + 1;
            out.add((int) i, n);
            i++;
            tick(out.size());
        }
        StringBuilder result = new StringBuilder(out.size());
        for (int point : out) {
            if (point >= 0xD800 && point <= 0xDFFF) return null;
            result.appendCodePoint(point);
        }
        return result.toString();
    }

    public static String encode(String input) {
        int[] points = input.codePoints().toArray();
        StringBuilder out = new StringBuilder();
        for (int point : points) if (point < 0x80) out.append((char) point);
        int basic = out.length();
        int handled = basic;
        if (basic > 0) out.append('-');
        int n = INITIAL_N;
        long delta = 0;
        int bias = INITIAL_BIAS;
        while (handled < points.length) {
            int minimum = Integer.MAX_VALUE;
            for (int point : points) if (point >= n && point < minimum) minimum = point;
            if (minimum == Integer.MAX_VALUE) break;
            delta += (long) (minimum - n) * (handled + 1);
            if (delta > Integer.MAX_VALUE) return null;
            n = minimum;
            for (int index = 0; index < points.length; index++) {
                int point = points[index];
                tick(index);
                if (point < n) delta++;
                if (delta > Integer.MAX_VALUE) return null;
                if (point == n) {
                    long q = delta;
                    int k = BASE;
                    while (true) {
                        int threshold = k <= bias ? TMIN : k >= bias + TMAX ? TMAX : k - bias;
                        if (q < threshold) break;
                        out.append(digitChar((int) (threshold + (q - threshold) % (BASE - threshold))));
                        q = (q - threshold) / (BASE - threshold);
                        k += BASE;
                    }
                    out.append(digitChar((int) q));
                    bias = adapt((int) delta, handled + 1, handled == basic);
                    delta = 0;
                    handled++;
                }
            }
            delta++;
            n++;
        }
        return out.toString();
    }
}
