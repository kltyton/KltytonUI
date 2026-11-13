/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.regexp;

import java.util.Arrays;

/** Growable IntArray supporting insertion, used to emit bytecode. */
final class IntBuf {
    static final int MAX_CODE_SIZE = 1 << 26;

    int[] a = new int[64];
    int size;

    private void ensure(int n) {
        if (n > a.length) {
            if (n > MAX_CODE_SIZE) throw new RegExpSyntaxError("regular expression too large");
            a = Arrays.copyOf(a, Math.max(n, a.length * 2));
        }
    }

    void add(int value) {
        ensure(size + 1);
        a[size++] = value;
    }

    /** Inserts {@code n} zero ints at {@code position}. */
    void insert(int position, int n) {
        ensure(size + n);
        System.arraycopy(a, position, a, position + n, size - position);
        Arrays.fill(a, position, position + n, 0);
        size += n;
    }

    int get(int index) {
        return a[index];
    }

    void set(int index, int value) {
        a[index] = value;
    }

    int[] toArray() {
        return Arrays.copyOf(a, size);
    }
}
