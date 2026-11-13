/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Realm;
import java.util.Arrays;

/** ArrayBuffer or SharedArrayBuffer object. */
public final class JSArrayBuffer extends JSObject {
    /** Backing bytes (capacity may exceed the byte length; never use {@code data.length} as the length). */
    public byte[] data;
    /** {@code [[ArrayBufferMaxByteLength]]}, or -1 for fixed-length buffers. */
    public final int maxByteLength;
    /** Non-null for SharedArrayBuffers. */
    public final SharedDataBlock block;

    private int ownByteLength;
    public boolean detached;
    /**
     * {@code [[ArrayBufferIsImmutable]]} (proposal-immutable-arraybuffer): set only on buffers created by
     * AllocateImmutableArrayBuffer, which are fixed-length and can be neither detached, resized nor written.
     */
    public boolean immutable;

    public JSArrayBuffer(JSObject prototype, byte[] data, int byteLength, int maxByteLength, SharedDataBlock block) {
        super(prototype);
        this.data = data;
        this.ownByteLength = byteLength;
        this.maxByteLength = maxByteLength;
        this.block = block;
    }

    public boolean isShared() {
        return block != null;
    }

    public boolean isFixedLength() {
        return maxByteLength < 0;
    }

    /** ArrayBufferByteLength (0 when detached). */
    public int byteLength() {
        SharedDataBlock shared = block;
        return shared != null ? shared.byteLength : ownByteLength;
    }

    public void detach() {
        data = BufferOps.EMPTY;
        ownByteLength = 0;
        detached = true;
    }

    /** Resizes a resizable (non-shared) buffer; newly exposed bytes are zero. */
    public void resize(int newLength) {
        int old = ownByteLength;
        if (newLength > data.length) {
            int capacity = (int) Math.min(Math.max((long) newLength, (long) data.length + (data.length >> 1)),
                    (long) maxByteLength);
            byte[] next = BufferOps.allocate(capacity);
            System.arraycopy(data, 0, next, 0, old);
            data = next;
        } else if (newLength < old) {
            Arrays.fill(data, newLength, old, (byte) 0);
        }
        ownByteLength = newLength;
    }

    /** Creates a SharedArrayBuffer object in {@code realm} viewing {@code block}. */
    public static JSArrayBuffer wrapShared(Realm realm, SharedDataBlock block) {
        return new JSArrayBuffer(realm.intrinsic("%SharedArrayBuffer.prototype%"), block.data,
                block.byteLength, block.maxByteLength, block);
    }

    /** The Shared Data Block of a SharedArrayBuffer value, or null. */
    public static SharedDataBlock sharedBlockOf(Object value) {
        return value instanceof JSArrayBuffer buffer ? buffer.block : null;
    }
}
