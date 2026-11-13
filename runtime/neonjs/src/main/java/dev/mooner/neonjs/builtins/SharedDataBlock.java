/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Shared Data Block of a SharedArrayBuffer. One block may be referenced by SharedArrayBuffer objects living in
 * different agents (threads); all of them see the same {@link #data}. Growable blocks are allocated at their maximum
 * size up front so {@link #data} never changes; only {@link #byteLength} grows.
 */
public final class SharedDataBlock {
    public final byte[] data;
    public volatile int byteLength;
    public final int maxByteLength;

    /** Critical section for atomic operations, the waiter lists (Atomics.wait / Atomics.notify) and grow. */
    public final ReentrantLock lock = new ReentrantLock();

    /** WaiterList per byte index; accessed only while holding {@link #lock}. */
    final HashMap<Integer, ArrayDeque<AtomicsBuiltins.Waiter>> waiters = new HashMap<>();

    SharedDataBlock(byte[] data, int byteLength, int maxByteLength) {
        this.data = data;
        this.byteLength = byteLength;
        this.maxByteLength = maxByteLength;
    }
}
