package dev.mooner.neonjs.unicode;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Operations on code-point sets represented by sorted, half-open inversion lists.
 * Derived from NeonJS and adapted to Java.
 */
public final class CharRanges {
    public static final CharRanges INSTANCE = new CharRanges();
    public static final int MAX = 0x110000;
    public static final int[] EMPTY = new int[0];
    public static final int[] ALL = {0, MAX};
    public static final int OP_UNION = 0;
    public static final int OP_INTER = 1;
    public static final int OP_SUB = 2;

    private CharRanges() {
    }

    public int[] of(int lo, int hiInclusive) {
        return new int[] {lo, hiInclusive + 1};
    }

    public int[] single(int codePoint) {
        return new int[] {codePoint, codePoint + 1};
    }

    public static boolean contains(int[] set, int codePoint) {
        int lo = 0;
        int hi = set.length - 1;
        if (hi < 0 || codePoint < set[0] || codePoint >= set[hi]) {
            return false;
        }
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (set[mid] <= codePoint) lo = mid;
            else hi = mid - 1;
        }
        return (lo & 1) == 0;
    }

    public boolean isEmpty(int[] set) {
        return set.length == 0;
    }

    public long size(int[] set) {
        long size = 0;
        for (int i = 0; i < set.length; i += 2) {
            size += set[i + 1] - set[i];
        }
        return size;
    }

    public int[] op(int[] a, int[] b, int operation) {
        int[] out = new int[a.length + b.length];
        int n = 0;
        int ai = 0;
        int bi = 0;
        while (true) {
            int value;
            if (ai < a.length && bi < b.length) {
                int x = a[ai];
                int y = b[bi];
                if (x < y) {
                    value = x;
                    ai++;
                } else if (x == y) {
                    value = x;
                    ai++;
                    bi++;
                } else {
                    value = y;
                    bi++;
                }
            } else if (ai < a.length) {
                value = a[ai++];
            } else if (bi < b.length) {
                value = b[bi++];
            } else {
                break;
            }
            int inA = ai & 1;
            int inB = bi & 1;
            int included = switch (operation) {
                case OP_UNION -> inA | inB;
                case OP_INTER -> inA & inB;
                case OP_SUB -> inA & (inB ^ 1);
                default -> inA ^ inB;
            };
            if (included != (n & 1)) {
                if (n > 0 && out[n - 1] == value) n--;
                else out[n++] = value;
            }
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }

    public int[] union(int[] a, int[] b) {
        return a.length == 0 ? b : b.length == 0 ? a : op(a, b, OP_UNION);
    }

    public int[] subtract(int[] a, int[] b) {
        return a.length == 0 || b.length == 0 ? a : op(a, b, OP_SUB);
    }

    public int[] invert(int[] a) {
        return op(ALL, a, OP_SUB);
    }

    /** Builds an inversion list from possibly unsorted and overlapping inclusive ranges. */
    public static final class Builder {
        private int[] buffer = new int[16];
        private int count;
        private boolean sorted = true;

        public Builder add(int codePoint) {
            return addRange(codePoint, codePoint);
        }

        public Builder addRange(int lo, int hiInclusive) {
            if (count + 2 > buffer.length) buffer = Arrays.copyOf(buffer, buffer.length * 2);
            if (count > 0 && lo < buffer[count - 2]) sorted = false;
            buffer[count++] = lo;
            buffer[count++] = hiInclusive + 1;
            return this;
        }

        public Builder addSet(int[] set) {
            for (int i = 0; i < set.length; i += 2) {
                addRange(set[i], set[i + 1] - 1);
            }
            return this;
        }

        public int[] build() {
            if (!sorted) {
                Integer[] indices = new Integer[count / 2];
                for (int i = 0; i < indices.length; i++) indices[i] = i;
                Arrays.sort(indices, Comparator.comparingInt(i -> buffer[i * 2]));
                int[] sortedBuffer = new int[count];
                for (int i = 0; i < indices.length; i++) {
                    sortedBuffer[i * 2] = buffer[indices[i] * 2];
                    sortedBuffer[i * 2 + 1] = buffer[indices[i] * 2 + 1];
                }
                buffer = sortedBuffer;
                sorted = true;
            }
            int[] out = new int[count];
            int n = 0;
            for (int i = 0; i < count; i += 2) {
                int start = buffer[i];
                int end = buffer[i + 1];
                if (start >= end) continue;
                if (n > 0 && start <= out[n - 1]) {
                    if (end > out[n - 1]) out[n - 1] = end;
                } else {
                    out[n++] = start;
                    out[n++] = end;
                }
            }
            return Arrays.copyOf(out, n);
        }
    }
}
